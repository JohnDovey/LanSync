package com.lansync.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.SavedSettings
import com.lansync.SettingsStore
import com.lansync.manager.ConflictResolution
import com.lansync.manager.UploadManager
import com.lansync.manager.UploadProgress
import com.lansync.manager.UploadStatus
import com.lansync.network.FileInfo
import com.lansync.network.ServerConfig
import com.lansync.network.SyncClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.security.MessageDigest

// ============================================================================
// VIEW MODEL
// ============================================================================

class SyncViewModel(private val context: Context) : ViewModel() {
    private val settingsStore = SettingsStore(context)

    private val _uiState = mutableStateOf<UiState>(UiState.Idle)
    val uiState: State<UiState> = _uiState

    private val _savedSettings = mutableStateOf(settingsStore.load())
    val savedSettings: State<SavedSettings> = _savedSettings

    private val _uploadProgress = mutableStateOf<Map<String, UploadProgress>>(emptyMap())
    val uploadProgress: State<Map<String, UploadProgress>> = _uploadProgress

    private val _syncHistory = mutableStateOf<List<String>>(emptyList())
    val syncHistory: State<List<String>> = _syncHistory

    private val _historyVisible = mutableStateOf(false)
    val historyVisible: State<Boolean> = _historyVisible

    /** Successfully uploaded local URIs awaiting optional deletion. */
    private val _deletableUris = mutableStateOf<List<Uri>>(emptyList())
    val deletableUris: State<List<Uri>> = _deletableUris

    private val _showDeletePrompt = mutableStateOf(false)
    val showDeletePrompt: State<Boolean> = _showDeletePrompt

    private val _deleteStatus = mutableStateOf<DeleteStatus?>(null)
    val deleteStatus: State<DeleteStatus?> = _deleteStatus

    /** When set, the UI should launch the system delete confirmation sheet. */
    private val _pendingSystemDelete = mutableStateOf<IntentSender?>(null)
    val pendingSystemDelete: State<IntentSender?> = _pendingSystemDelete

    private var systemDeleteUris: List<Uri> = emptyList()
    private var directDeletedCount = 0
    private var directFailedCount = 0

    private var syncClient: SyncClient? = null
    private var uploadManager: UploadManager? = null

    private var pendingConflictFile: FileInfo? = null
    private var pendingConflictCallback: (suspend (ConflictResolution) -> Unit)? = null

    /** Persist fields as the user types so they survive process death / app restart. */
    fun saveConnectionFields(host: String, port: String, username: String, deviceName: String) {
        val portNum = port.toIntOrNull() ?: SettingsStore.DEFAULT_PORT
        settingsStore.save(host, portNum, username, deviceName)
        // Do not bump _savedSettings here — that would recompose the form and reset the cursor.
    }

    fun initializeSync(serverConfig: ServerConfig) {
        // Always persist before connecting so a failed attempt still remembers the address.
        settingsStore.save(serverConfig)
        _savedSettings.value = settingsStore.load()

        viewModelScope.launch {
            _uiState.value = UiState.Connecting
            syncClient?.close()
            syncClient = SyncClient(serverConfig)

            val result = syncClient?.connect()
            if (result?.isSuccess == true) {
                uploadManager = UploadManager(syncClient!!, maxConcurrent = 1)
                uploadManager?.setProgressCallback { progress ->
                    _uploadProgress.value = progress
                }
                _uiState.value = UiState.Ready
            } else {
                val message = result?.exceptionOrNull()?.message ?: "Connection failed"
                _uiState.value = UiState.Error(message)
            }
        }
    }

    fun startSync(selectedFiles: List<Uri>) {
        if (selectedFiles.isEmpty()) return
        if (uploadManager == null || syncClient?.isConnected() != true) {
            _uiState.value = UiState.Error("Not connected to server")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Syncing
            _uploadProgress.value = emptyMap()
            clearDeleteState()

            try {
                val prepared = selectedFiles.map { uri ->
                    val fileData = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read file: ${displayName(uri)}")
                    val fileInfo = buildFileInfo(uri, fileData)
                    PreparedUpload(uri, fileInfo, fileData)
                }

                val result = uploadManager?.uploadFiles(
                    prepared.map { it.fileInfo to it.fileData }
                ) { fileInfo ->
                    handleConflict(fileInfo)
                }

                if (result?.isFailure == true) {
                    _uiState.value = UiState.Error(
                        result.exceptionOrNull()?.message ?: "Sync failed"
                    )
                } else {
                    val progress = uploadManager?.getProgress().orEmpty()
                    val completedUris = prepared.mapNotNull { item ->
                        val key = "${item.fileInfo.filename}|${item.fileInfo.fileHash}"
                        if (progress[key]?.status == UploadStatus.COMPLETED) item.uri else null
                    }
                    _deletableUris.value = completedUris
                    _showDeletePrompt.value = completedUris.isNotEmpty()
                    _uiState.value = UiState.Complete
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Sync failed")
            }
        }
    }

    private data class PreparedUpload(
        val uri: Uri,
        val fileInfo: FileInfo,
        val fileData: ByteArray
    )

    private suspend fun handleConflict(fileInfo: FileInfo): ConflictResolution? {
        pendingConflictFile = fileInfo
        _uiState.value = UiState.ConflictDialog(fileInfo)

        // Wait for user response (handled in UI)
        return suspendCancellableCoroutine { continuation ->
            pendingConflictCallback = { resolution ->
                continuation.resume(resolution)
                _uiState.value = UiState.Syncing
            }
        }
    }

    fun resolveConflict(override: Boolean, applyToAll: Boolean) {
        viewModelScope.launch {
            pendingConflictCallback?.invoke(
                ConflictResolution(
                    filename = pendingConflictFile?.filename ?: "",
                    overrideThisFile = override,
                    applyToAll = applyToAll
                )
            )
        }
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun buildFileInfo(uri: Uri, fileData: ByteArray): FileInfo {
        val filename = SyncClient.sanitizeFilename(displayName(uri))
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val fileType = when {
            mimeType.startsWith("image/") || mimeType.startsWith("video/") -> "photos"
            else -> "documents"
        }

        return FileInfo(
            filename = filename,
            fileHash = calculateHash(fileData),
            fileType = fileType,
            directory = fileType,
            filePath = uri.toString(),
            fileSize = fileData.size.toLong(),
            mimeType = mimeType
        )
    }

    private fun calculateHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).fold("") { str, it ->
            str + "%02x".format(it)
        }
    }

    fun loadSyncHistory() {
        viewModelScope.launch {
            val result = syncClient?.getSyncHistory()
            if (result?.isSuccess == true) {
                _syncHistory.value = result.getOrNull() ?: emptyList()
                _historyVisible.value = true
            } else {
                _syncHistory.value = emptyList()
                _historyVisible.value = true
            }
        }
    }

    fun hideHistory() {
        _historyVisible.value = false
    }

    fun declineDeleteLocalFiles() {
        _showDeletePrompt.value = false
        _deleteStatus.value = DeleteStatus(
            deleted = 0,
            failed = 0,
            message = "Local files kept on device"
        )
        _deletableUris.value = emptyList()
    }

    /**
     * User chose to remove successfully synced files from the device.
     * Some media URIs require a system confirmation sheet (API 30+).
     */
    fun confirmDeleteLocalFiles() {
        val uris = _deletableUris.value
        if (uris.isEmpty()) {
            _showDeletePrompt.value = false
            return
        }

        viewModelScope.launch {
            _showDeletePrompt.value = false
            _deleteStatus.value = DeleteStatus(deleted = 0, failed = 0, message = "Deleting…")

            val directOk = mutableListOf<Uri>()
            val needConsent = mutableListOf<Uri>()
            val failed = mutableListOf<Uri>()

            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    when (tryDeleteUri(uri)) {
                        DeleteAttempt.Success -> directOk.add(uri)
                        DeleteAttempt.NeedsUserConsent -> needConsent.add(uri)
                        DeleteAttempt.Failed -> failed.add(uri)
                    }
                }
            }

            directDeletedCount = directOk.size
            directFailedCount = failed.size

            // Notify server that the client chose the post-sync delete path.
            runCatching { syncClient?.confirmDelete() }

            if (needConsent.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val request = MediaStore.createDeleteRequest(context.contentResolver, needConsent)
                    systemDeleteUris = needConsent
                    _pendingSystemDelete.value = request.intentSender
                    _deleteStatus.value = DeleteStatus(
                        deleted = directOk.size,
                        failed = failed.size,
                        message = "Confirm deletion in the system dialog…"
                    )
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "createDeleteRequest failed", e)
                    directFailedCount += needConsent.size
                }
            } else if (needConsent.isNotEmpty()) {
                directFailedCount += needConsent.size
            }

            finishDeleteSummary(extraDeleted = 0, extraFailed = 0)
            _deletableUris.value = emptyList()
        }
    }

    fun onSystemDeleteResult(granted: Boolean) {
        val pending = systemDeleteUris
        systemDeleteUris = emptyList()
        _pendingSystemDelete.value = null

        val extraDeleted = if (granted) pending.size else 0
        val extraFailed = if (granted) 0 else pending.size
        finishDeleteSummary(extraDeleted, extraFailed)
        _deletableUris.value = emptyList()
    }

    fun clearPendingSystemDelete() {
        _pendingSystemDelete.value = null
    }

    private fun finishDeleteSummary(extraDeleted: Int, extraFailed: Int) {
        val deleted = directDeletedCount + extraDeleted
        val failed = directFailedCount + extraFailed
        _deleteStatus.value = when {
            deleted > 0 && failed == 0 ->
                DeleteStatus(deleted, failed, "Deleted $deleted file(s) from this device")
            deleted > 0 ->
                DeleteStatus(deleted, failed, "Deleted $deleted file(s); $failed could not be removed")
            failed > 0 ->
                DeleteStatus(deleted, failed, "Could not delete $failed file(s). Android may block removal for some gallery items.")
            else ->
                DeleteStatus(0, 0, "No files deleted")
        }
        directDeletedCount = 0
        directFailedCount = 0
    }

    private fun clearDeleteState() {
        _deletableUris.value = emptyList()
        _showDeletePrompt.value = false
        _deleteStatus.value = null
        _pendingSystemDelete.value = null
        systemDeleteUris = emptyList()
        directDeletedCount = 0
        directFailedCount = 0
    }

    private enum class DeleteAttempt { Success, NeedsUserConsent, Failed }

    private fun tryDeleteUri(uri: Uri): DeleteAttempt {
        val resolver = context.contentResolver
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                if (DocumentsContract.deleteDocument(resolver, uri)) {
                    return DeleteAttempt.Success
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to delete document $uri", e)
        } catch (e: Exception) {
            Log.w(TAG, "DocumentsContract delete failed for $uri", e)
        }

        try {
            val rows = resolver.delete(uri, null, null)
            if (rows > 0) return DeleteAttempt.Success
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to delete $uri", e)
            // MediaStore often requires a user-confirmed bulk delete on API 30+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isMediaStoreUri(uri)) {
                return DeleteAttempt.NeedsUserConsent
            }
            return DeleteAttempt.Failed
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver delete failed for $uri", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isMediaStoreUri(uri)) {
            return DeleteAttempt.NeedsUserConsent
        }
        return DeleteAttempt.Failed
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return authority == MediaStore.AUTHORITY || authority.contains("media", ignoreCase = true)
    }

    fun takePersistablePermissions(uris: List<Uri>) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // GetContent URIs often don't support persistable grants.
                }
            }
        }
    }

    /** Return to file picker after a successful sync (keep connection). */
    fun continueSyncing() {
        clearDeleteState()
        _uploadProgress.value = emptyMap()
        _historyVisible.value = false
        _uiState.value = UiState.Ready
    }

    /** Disconnect and return to connect form (errors / full restart). */
    fun resetToConnect() {
        uploadManager?.cancel()
        uploadManager = null
        syncClient?.close()
        syncClient = null
        pendingConflictFile = null
        pendingConflictCallback = null
        clearDeleteState()
        _uploadProgress.value = emptyMap()
        _syncHistory.value = emptyList()
        _historyVisible.value = false
        _uiState.value = UiState.Idle
    }

    override fun onCleared() {
        syncClient?.close()
        uploadManager?.cancel()
        super.onCleared()
    }

    companion object {
        private const val TAG = "SyncViewModel"
    }
}

data class DeleteStatus(
    val deleted: Int,
    val failed: Int,
    val message: String
)

sealed class UiState {
    object Idle : UiState()
    object Connecting : UiState()
    object Ready : UiState()
    object Syncing : UiState()
    object Complete : UiState()
    data class ConflictDialog(val fileInfo: FileInfo) : UiState()
    data class Error(val message: String) : UiState()
}

// ============================================================================
// COMPOSABLE SCREENS
// ============================================================================

@Composable
fun SyncMainScreen(viewModel: SyncViewModel) {
    val uiState by viewModel.uiState
    val uploadProgress by viewModel.uploadProgress

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        when (uiState) {
            is UiState.Idle -> ConnectScreen(viewModel)
            is UiState.Connecting -> LoadingScreen("Connecting to server...")
            is UiState.Ready -> FilePickerScreen(viewModel)
            is UiState.Syncing -> SyncProgressScreen(uploadProgress)
            is UiState.Complete -> CompleteScreen(uploadProgress, viewModel)
            is UiState.ConflictDialog -> ConflictDialogScreen((uiState as UiState.ConflictDialog).fileInfo, viewModel)
            is UiState.Error -> ErrorScreen((uiState as UiState.Error).message, viewModel)
        }
    }
}

@Composable
fun ConnectScreen(viewModel: SyncViewModel) {
    // Load once when the connect form is first shown (after cold start or Disconnect).
    val initial = remember { viewModel.savedSettings.value }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var username by remember { mutableStateOf(initial.username) }
    var deviceName by remember { mutableStateOf(initial.deviceName) }

    fun persistDraft() {
        viewModel.saveConnectionFields(host, port, username, deviceName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "File Sync",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2C3E50)
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                persistDraft()
            },
            label = { Text("Server Host") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = port,
            onValueChange = {
                port = it.filter { ch -> ch.isDigit() }.take(5)
                persistDraft()
            },
            label = { Text("Server Port") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                persistDraft()
            },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = {
                deviceName = it
                persistDraft()
            },
            label = { Text("Device Name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val config = ServerConfig(
                    host = host.trim(),
                    port = port.toIntOrNull() ?: SettingsStore.DEFAULT_PORT,
                    username = username.trim(),
                    deviceName = deviceName.trim()
                )
                viewModel.initializeSync(config)
            },
            enabled = host.isNotBlank() && username.isNotBlank() && deviceName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Connect", fontSize = 16.sp)
        }
    }
}

@Composable
fun FilePickerScreen(viewModel: SyncViewModel) {
    var selectedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModel.takePersistablePermissions(uris)
        selectedFiles = (selectedFiles + uris).distinct()
    }

    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> addUris(uris) }

    val pickVideos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> addUris(uris) }

    val pickDocuments = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addUris(uris) }

    val pickAny = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> addUris(uris) }

    // Pin Start Sync in the bottom bar so it cannot be scrolled off-screen.
    Scaffold(
        containerColor = Color(0xFFF5F5F5),
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                Button(
                    onClick = { viewModel.startSync(selectedFiles) },
                    enabled = selectedFiles.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if (selectedFiles.isEmpty()) "Select files to sync" else "Start Sync (${selectedFiles.size})",
                        fontSize = 16.sp
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "Select Files to Sync",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            SuggestedDirectoriesCard(
                onPhotos = { pickImages.launch("image/*") },
                onDocuments = { pickDocuments.launch(arrayOf("application/*", "text/*")) },
                onVideos = { pickVideos.launch("video/*") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { pickImages.launch("image/*") }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Photos")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { pickDocuments.launch(arrayOf("application/*", "text/*")) }) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Documents")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { pickAny.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Custom")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Selected Files (${selectedFiles.size})",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { selectedFiles = emptyList() }) {
                        Text("Clear")
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(selectedFiles, key = { it.toString() }) { uri ->
                        FileItemCard(
                            filename = viewModel.displayName(uri),
                            onRemove = { selectedFiles = selectedFiles.filterNot { it == uri } }
                        )
                    }
                }
            } else {
                Text(
                    "Tap Photos, Documents, or Custom to choose files.",
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun SuggestedDirectoriesCard(
    onPhotos: () -> Unit,
    onDocuments: () -> Unit,
    onVideos: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick Access", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))

            listOf(
                "📷 Photos" to onPhotos,
                "📄 Documents" to onDocuments,
                "🎬 Videos" to onVideos
            ).forEach { (label, onClick) ->
                Text(
                    label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun FileItemCard(filename: String, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green)
        Spacer(modifier = Modifier.width(12.dp))
        Text(filename, modifier = Modifier.weight(1f))
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove")
            }
        }
    }
}

@Composable
fun SyncProgressScreen(progress: Map<String, UploadProgress>) {
    val items = progress.values.toList()
    val done = items.count {
        it.status == UploadStatus.COMPLETED ||
            it.status == UploadStatus.FAILED ||
            it.status == UploadStatus.SKIPPED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Syncing Files",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Text(
            if (items.isEmpty()) "Preparing..." else "Progress: $done / ${items.size}",
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn {
            items(items, key = { "${it.fileInfo.filename}|${it.fileInfo.fileHash}" }) { fileProgress ->
                ProgressItemCard(fileProgress)
            }
        }
    }
}

@Composable
fun ProgressItemCard(progress: UploadProgress) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(progress.fileInfo.filename, modifier = Modifier.weight(1f))
                Text("${progress.percentage}%", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (progress.status) {
                    UploadStatus.UPLOADING -> "Uploading..."
                    UploadStatus.COMPLETED -> "✓ Completed"
                    UploadStatus.FAILED -> "✗ Failed: ${progress.errorMessage}"
                    UploadStatus.SKIPPED -> "⊘ Skipped"
                    else -> progress.status.name
                },
                fontSize = 12.sp,
                color = when (progress.status) {
                    UploadStatus.COMPLETED -> Color.Green
                    UploadStatus.FAILED -> Color.Red
                    else -> Color.Gray
                }
            )
        }
    }
}

@Composable
fun ConflictDialogScreen(fileInfo: FileInfo, viewModel: SyncViewModel) {
    AlertDialog(
        onDismissRequest = { viewModel.resolveConflict(false, false) },
        title = { Text("Duplicate File") },
        text = { Text("'${fileInfo.filename}' already exists. Override?") },
        confirmButton = {
            Button(onClick = { viewModel.resolveConflict(true, false) }) {
                Text("Override")
            }
        },
        dismissButton = {
            Button(onClick = { viewModel.resolveConflict(false, false) }) {
                Text("Skip")
            }
        }
    )
}

@Composable
fun CompleteScreen(progress: Map<String, UploadProgress>, viewModel: SyncViewModel) {
    val completed = progress.count { it.value.status == UploadStatus.COMPLETED }
    val failed = progress.count { it.value.status == UploadStatus.FAILED }
    val skipped = progress.count { it.value.status == UploadStatus.SKIPPED }
    val history by viewModel.syncHistory
    val historyVisible by viewModel.historyVisible
    val showDeletePrompt by viewModel.showDeletePrompt
    val deletableUris by viewModel.deletableUris
    val deleteStatus by viewModel.deleteStatus
    val pendingSystemDelete by viewModel.pendingSystemDelete

    val systemDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onSystemDeleteResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(pendingSystemDelete) {
        val sender = pendingSystemDelete ?: return@LaunchedEffect
        try {
            systemDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            Log.e("CompleteScreen", "Failed to launch system delete", e)
            viewModel.onSystemDeleteResult(false)
        } finally {
            viewModel.clearPendingSystemDelete()
        }
    }

    if (showDeletePrompt && deletableUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.declineDeleteLocalFiles() },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete from device?") },
            text = {
                Text(
                    "${deletableUris.size} file(s) were uploaded successfully. " +
                        "Delete them from this phone to free space? " +
                        "Files already on the server will not be affected."
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmDeleteLocalFiles() }) {
                    Text("Delete from device")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declineDeleteLocalFiles() }) {
                    Text("Keep files")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = Color.Green
        )

        Text("Sync Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))

        Text("Completed: $completed", modifier = Modifier.padding(vertical = 4.dp))
        Text("Failed: $failed", modifier = Modifier.padding(vertical = 4.dp))
        Text("Skipped: $skipped", modifier = Modifier.padding(vertical = 4.dp))

        if (failed > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            progress.values
                .filter { it.status == UploadStatus.FAILED }
                .take(5)
                .forEach { item ->
                    Text(
                        "• ${item.fileInfo.filename}: ${item.errorMessage ?: "failed"}",
                        color = Color.Red,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
        }

        deleteStatus?.let { status ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                status.message,
                color = when {
                    status.deleted > 0 && status.failed == 0 -> Color(0xFF2E7D32)
                    status.failed > 0 -> Color(0xFFE65100)
                    else -> Color.Gray
                },
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (deletableUris.isNotEmpty() && !showDeletePrompt && deleteStatus == null) {
            Button(
                onClick = { viewModel.confirmDeleteLocalFiles() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete ${deletableUris.size} synced file(s)")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.declineDeleteLocalFiles() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Keep files on device")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { viewModel.continueSyncing() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sync More Files")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                if (historyVisible) viewModel.hideHistory() else viewModel.loadSyncHistory()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (historyVisible) "Hide History" else "View History")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = { viewModel.resetToConnect() }) {
            Text("Disconnect")
        }

        if (historyVisible) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Sync History", fontWeight = FontWeight.SemiBold)
            if (history.isEmpty()) {
                Text("No history available", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            } else {
                history.forEach { entry ->
                    Text(entry, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun ErrorScreen(message: String, viewModel: SyncViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(80.dp))
        Text("Error", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 16.dp))
        Text(message, modifier = Modifier.padding(vertical = 16.dp))
        Button(onClick = { viewModel.resetToConnect() }) {
            Text("Try Again")
        }
    }
}
