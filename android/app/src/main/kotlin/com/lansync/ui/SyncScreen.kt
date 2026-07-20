package com.lansync.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.lansync.manager.ConflictResolution
import com.lansync.manager.UploadManager
import com.lansync.manager.UploadProgress
import com.lansync.manager.UploadStatus
import com.lansync.network.FileInfo
import com.lansync.network.ServerConfig
import com.lansync.network.SyncClient
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest

// ============================================================================
// VIEW MODEL
// ============================================================================

class SyncViewModel(private val context: Context) : ViewModel() {
    private val _uiState = mutableStateOf<UiState>(UiState.Idle)
    val uiState: State<UiState> = _uiState

    private val _uploadProgress = mutableStateOf<Map<String, UploadProgress>>(emptyMap())
    val uploadProgress: State<Map<String, UploadProgress>> = _uploadProgress

    private val _syncHistory = mutableStateOf<List<String>>(emptyList())
    val syncHistory: State<List<String>> = _syncHistory

    private val _historyVisible = mutableStateOf(false)
    val historyVisible: State<Boolean> = _historyVisible

    private var syncClient: SyncClient? = null
    private var uploadManager: UploadManager? = null

    private var pendingConflictFile: FileInfo? = null
    private var pendingConflictCallback: (suspend (ConflictResolution) -> Unit)? = null

    fun initializeSync(serverConfig: ServerConfig) {
        viewModelScope.launch {
            _uiState.value = UiState.Connecting
            syncClient?.close()
            syncClient = SyncClient(serverConfig)

            val result = syncClient?.connect()
            if (result?.isSuccess == true) {
                uploadManager = UploadManager(syncClient!!, maxConcurrent = 10)
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
        if (uploadManager == null) {
            _uiState.value = UiState.Error("Not connected to server")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Syncing
            _uploadProgress.value = emptyMap()

            try {
                val filesToUpload = selectedFiles.map { uri ->
                    val fileData = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("Cannot read file: ${displayName(uri)}")
                    val fileInfo = buildFileInfo(uri, fileData)
                    Pair(fileInfo, fileData)
                }

                uploadManager?.uploadFiles(filesToUpload) { fileInfo ->
                    handleConflict(fileInfo)
                }

                _uiState.value = UiState.Complete
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Sync failed")
            }
        }
    }

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
        val filename = displayName(uri)
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

    /** Return to file picker after a successful sync (keep connection). */
    fun continueSyncing() {
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
}

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
    var host by remember { mutableStateOf("192.168.1.100") }
    var port by remember { mutableStateOf("10101") }
    var username by remember { mutableStateOf("user1") }
    var deviceName by remember { mutableStateOf("android-phone") }

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
            onValueChange = { host = it },
            label = { Text("Server Host") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Server Port") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device Name") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val config = ServerConfig(host, port.toIntOrNull() ?: 10101, username, deviceName)
                viewModel.initializeSync(config)
            },
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
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

        Spacer(modifier = Modifier.height(24.dp))

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

        Spacer(modifier = Modifier.height(24.dp))

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
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
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
            Spacer(modifier = Modifier.weight(1f))
        }

        if (selectedFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
        }

        Button(
            onClick = { viewModel.startSync(selectedFiles) },
            enabled = selectedFiles.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Start Sync", fontSize = 16.sp)
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

        LazyColumn {
            items(progress.values.toList()) { fileProgress ->
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

        Spacer(modifier = Modifier.height(24.dp))

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
