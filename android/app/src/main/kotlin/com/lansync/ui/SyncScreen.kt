package com.lansync.ui

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
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
import java.io.File
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

    private var syncClient: SyncClient? = null
    private var uploadManager: UploadManager? = null

    private var pendingConflictFile: FileInfo? = null
    private var pendingConflictCallback: (suspend (ConflictResolution) -> Unit)? = null

    fun initializeSync(serverConfig: ServerConfig) {
        viewModelScope.launch {
            _uiState.value = UiState.Connecting
            syncClient = SyncClient(serverConfig)

            val result = syncClient?.connect()
            if (result?.isSuccess == true) {
                uploadManager = UploadManager(syncClient!!, maxConcurrent = 10)
                uploadManager?.setProgressCallback { progress ->
                    _uploadProgress.value = progress
                }
                _uiState.value = UiState.Ready
            } else {
                _uiState.value = UiState.Error(result?.exceptionOrNull()?.message ?: "Connection failed")
            }
        }
    }

    fun startSync(selectedFiles: List<Uri>) {
        viewModelScope.launch {
            _uiState.value = UiState.Syncing
            _uploadProgress.value = emptyMap()

            try {
                val filesToUpload = selectedFiles.map { uri ->
                    val fileInfo = getFileInfoFromUri(uri)
                    val fileData = context.contentResolver.openInputStream(uri)?.readBytes()
                        ?: throw Exception("Cannot read file")
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

    private fun getFileInfoFromUri(uri: Uri): FileInfo {
        val filename = uri.lastPathSegment ?: "file"
        val fileData = context.contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0)
        val fileHash = calculateHash(fileData)
        
        val fileType = when {
            uri.toString().contains("/images/") -> "photos"
            uri.toString().contains("/video/") -> "videos"
            else -> "documents"
        }

        return FileInfo(
            filename = filename,
            fileHash = fileHash,
            fileType = fileType,
            directory = uri.toString(),
            filePath = uri.toString(),
            fileSize = fileData.size.toLong(),
            mimeType = context.contentResolver.getType(uri) ?: ""
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
            }
        }
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

        // Suggested directories
        SuggestedDirectoriesCard()

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Button(onClick = { /* Open Photos */ }) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Text("Photos")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { /* Open Documents */ }) {
                Icon(Icons.Default.Description, contentDescription = null)
                Text("Documents")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { /* Open Custom */ }) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Text("Custom")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (selectedFiles.isNotEmpty()) {
            Text(
                "Selected Files (${selectedFiles.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            LazyColumn {
                items(selectedFiles) { uri ->
                    FileItemCard(uri.lastPathSegment ?: "File")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

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
fun SuggestedDirectoriesCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick Access", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            
            listOf("📷 Photos", "📄 Documents", "🎬 Videos").forEach { path ->
                Text(path, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun FileItemCard(filename: String) {
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
        Text(filename)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
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

        Button(onClick = { viewModel.loadSyncHistory() }) {
            Text("View History")
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
        Button(onClick = { /* Reset */ }) {
            Text("Try Again")
        }
    }
}
