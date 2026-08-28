package com.lansync.ui

import android.Manifest
import android.app.Activity
import android.content.ContentUris
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lansync.FailedSyncEntry
import com.lansync.FailedSyncStore
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


// ============================================================================
// VIEW MODEL
// ============================================================================

class SyncViewModel(private val context: Context) : ViewModel() {
    private val settingsStore = SettingsStore(context)
    private val failedSyncStore = FailedSyncStore(context)

    /** Failed uploads kept across app restarts so they can be retried or dismissed. */
    private val _failedSyncs = mutableStateOf(failedSyncStore.load())
    val failedSyncs: State<List<FailedSyncEntry>> = _failedSyncs

    /** App version shown in the header and About screen. */
    val appVersionName: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

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

    fun startSync(selectedItems: List<SelectedItem>) {
        if (selectedItems.isEmpty()) return
        if (uploadManager == null || syncClient?.isConnected() != true) {
            _uiState.value = UiState.Error("Not connected to server")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Syncing
            _uploadProgress.value = emptyMap()
            clearDeleteState()

            val manager = uploadManager ?: run {
                _uiState.value = UiState.Error("Not connected to server")
                return@launch
            }

            try {
                // One file at a time, fully streamed — DCIM / SD-card videos must
                // never be loaded entirely into a ByteArray (that OOMs the app).
                manager.beginSession()
                val completedUris = mutableListOf<Uri>()
                val total = selectedItems.size

                for ((index, item) in selectedItems.withIndex()) {
                    Log.i(TAG, "Syncing ${index + 1}/$total: ${item.displayLabel()}")
                    try {
                        val fileInfo = withContext(Dispatchers.IO) {
                            buildFileInfoStreaming(item)
                        }
                        manager.uploadFileStreaming(
                            fileInfo = fileInfo,
                            openStream = {
                                context.contentResolver.openInputStream(item.uri)
                            },
                            onConflict = { handleConflict(it) }
                        )
                        val key = "${fileInfo.filename}|${fileInfo.fileHash}"
                        if (manager.getProgress()[key]?.status == UploadStatus.COMPLETED) {
                            completedUris.add(item.uri)
                        }
                    } catch (oom: OutOfMemoryError) {
                        Log.e(TAG, "OOM on ${item.displayLabel()}", oom)
                        System.gc()
                        runCatching {
                            manager.markFailed(
                                buildFileInfoStub(item, "Out of memory"),
                                "Out of memory"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed ${item.displayLabel()}", e)
                        runCatching {
                            manager.markFailed(
                                buildFileInfoStub(item, e.message ?: "Failed"),
                                e.message
                            )
                        }
                    }
                }

                reconcileFailedSyncs(manager.getProgress())

                _deletableUris.value = completedUris.distinct()
                _showDeletePrompt.value = completedUris.isNotEmpty()
                _uiState.value = UiState.Complete
            } catch (oom: OutOfMemoryError) {
                System.gc()
                _uiState.value = UiState.Error(
                    "Out of memory while syncing. Try a smaller folder, or sync photos without large videos."
                )
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

    /**
     * Update the persisted failed-syncs list from this run's outcomes: files that
     * now succeeded (or were skipped/duplicate) are cleared, files that failed are
     * kept (or updated) so they can be retried later, including after a restart.
     */
    private fun reconcileFailedSyncs(runProgress: Map<String, UploadProgress>) {
        val resolved = _failedSyncs.value.toMutableList()
        for (p in runProgress.values) {
            val uriStr = p.fileInfo.filePath
            when (p.status) {
                UploadStatus.FAILED -> {
                    resolved.removeAll { it.uri == uriStr }
                    resolved.add(
                        FailedSyncEntry(
                            uri = uriStr,
                            relativePath = if (p.fileInfo.fileType == "folders") p.fileInfo.filename else null,
                            displayName = p.fileInfo.filename,
                            errorMessage = p.errorMessage ?: "Failed",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                UploadStatus.COMPLETED, UploadStatus.DUPLICATE, UploadStatus.SKIPPED ->
                    resolved.removeAll { it.uri == uriStr }
                else -> {}
            }
        }
        _failedSyncs.value = resolved
        failedSyncStore.save(resolved)
    }

    /** Re-queue previously failed files for another sync attempt. */
    fun retryFailedSyncs(entries: List<FailedSyncEntry>) {
        val items = entries.mapNotNull { entry ->
            runCatching { SelectedItem(uri = Uri.parse(entry.uri), relativePath = entry.relativePath) }
                .getOrNull()
        }
        if (items.isNotEmpty()) startSync(items)
    }

    /** Forget failed files without retrying (batch dismiss). */
    fun dismissFailedSyncs(entries: List<FailedSyncEntry>) {
        val keys = entries.map { it.uri }.toSet()
        _failedSyncs.value = _failedSyncs.value.filterNot { it.uri in keys }
        failedSyncStore.save(_failedSyncs.value)
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

    private fun contentLength(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        val size = cursor.getLong(idx)
                        if (size >= 0) return size
                    }
                }
            }
        return -1L
    }

    /**
     * Hash + size via streaming (never [InputStream.readBytes]).
     * Opens the content URI twice: once to hash, once later to upload.
     */
    private fun buildFileInfoStreaming(item: SelectedItem): FileInfo {
        val mimeType = context.contentResolver.getType(item.uri).orEmpty()
        val (hash, length) = context.contentResolver.openInputStream(item.uri)?.use { stream ->
            SyncClient.sha256AndLength(stream)
        } ?: throw Exception("Cannot read file: ${item.displayLabel()}")

        // Prefer streamed length; fall back to provider-reported size if stream was empty weirdly.
        val size = if (length > 0) length else contentLength(item.uri).coerceAtLeast(0L)
        val relative = item.relativePath?.let { SyncClient.sanitizeRelativePath(it) }.orEmpty()

        return if (relative.isNotEmpty()) {
            val dir = relative.substringBeforeLast('/', missingDelimiterValue = "")
            FileInfo(
                filename = relative,
                fileHash = hash,
                fileType = "folders",
                directory = dir,
                filePath = item.uri.toString(),
                fileSize = size,
                mimeType = mimeType
            )
        } else {
            val filename = SyncClient.sanitizeFilename(displayName(item.uri))
            val fileType = when {
                mimeType.startsWith("image/") || mimeType.startsWith("video/") -> "photos"
                else -> "documents"
            }
            FileInfo(
                filename = filename,
                fileHash = hash,
                fileType = fileType,
                directory = fileType,
                filePath = item.uri.toString(),
                fileSize = size,
                mimeType = mimeType
            )
        }
    }

    private fun buildFileInfoStub(item: SelectedItem, error: String): FileInfo {
        val relative = item.relativePath?.let { SyncClient.sanitizeRelativePath(it) }.orEmpty()
        val name = relative.ifEmpty { SyncClient.sanitizeFilename(displayName(item.uri)) }
        return FileInfo(
            filename = name,
            fileHash = "error",
            fileType = if (relative.isNotEmpty()) "folders" else "documents",
            directory = relative.substringBeforeLast('/', missingDelimiterValue = "documents"),
            filePath = item.uri.toString(),
            fileSize = 0,
            mimeType = error
        )
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
            total = 0,
            processed = 0,
            inProgress = false,
            message = "Local files kept on device"
        )
        _deletableUris.value = emptyList()
    }

    /**
     * User chose to remove successfully synced files from the device.
     * Some media URIs require a system confirmation sheet (API 30+).
     * Progress is published as [DeleteStatus] (x/total + fraction).
     */
    fun confirmDeleteLocalFiles() {
        val uris = _deletableUris.value
        if (uris.isEmpty()) {
            _showDeletePrompt.value = false
            return
        }

        viewModelScope.launch {
            _showDeletePrompt.value = false
            val total = uris.size
            var deleted = 0
            var failed = 0
            val needConsent = mutableListOf<Uri>()

            fun publish(
                processed: Int,
                current: String?,
                inProgress: Boolean,
                message: String
            ) {
                _deleteStatus.value = DeleteStatus(
                    deleted = deleted,
                    failed = failed,
                    total = total,
                    processed = processed,
                    inProgress = inProgress,
                    currentName = current,
                    message = message
                )
            }

            publish(
                processed = 0,
                current = null,
                inProgress = true,
                message = "Deleting 0 / $total…"
            )

            for ((index, uri) in uris.withIndex()) {
                val label = displayName(uri)
                publish(
                    processed = index,
                    current = label,
                    inProgress = true,
                    message = "Deleting ${index + 1} / $total…"
                )

                val attempt = withContext(Dispatchers.IO) { tryDeleteUri(uri) }
                when (attempt) {
                    is DeleteAttempt.Success -> deleted++
                    is DeleteAttempt.NeedsUserConsent -> needConsent.add(attempt.mediaUri)
                    is DeleteAttempt.Failed -> failed++
                }

                publish(
                    processed = index + 1,
                    current = label,
                    inProgress = true,
                    message = "Deleted $deleted / $total" +
                        if (failed > 0) " ($failed failed)" else ""
                )
            }

            directDeletedCount = deleted
            directFailedCount = failed

            // Notify server that the client chose the post-sync delete path.
            runCatching { syncClient?.confirmDelete() }

            if (needConsent.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val request = MediaStore.createDeleteRequest(context.contentResolver, needConsent)
                    systemDeleteUris = needConsent
                    _pendingSystemDelete.value = request.intentSender
                    publish(
                        processed = total - needConsent.size,
                        current = null,
                        inProgress = true,
                        message = "Deleted $deleted / $total — confirm ${needConsent.size} more in system dialog…"
                    )
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "createDeleteRequest failed", e)
                    failed += needConsent.size
                    directFailedCount = failed
                }
            } else if (needConsent.isNotEmpty()) {
                failed += needConsent.size
                directFailedCount = failed
            }

            finishDeleteSummary(extraDeleted = 0, extraFailed = 0, total = total)
            _deletableUris.value = emptyList()
        }
    }

    fun onSystemDeleteResult(granted: Boolean) {
        val pending = systemDeleteUris
        systemDeleteUris = emptyList()
        _pendingSystemDelete.value = null

        val extraDeleted = if (granted) pending.size else 0
        val extraFailed = if (granted) 0 else pending.size
        val total = (_deleteStatus.value?.total ?: 0).coerceAtLeast(
            directDeletedCount + directFailedCount + pending.size
        )
        finishDeleteSummary(extraDeleted, extraFailed, total = total)
        _deletableUris.value = emptyList()
    }

    fun clearPendingSystemDelete() {
        _pendingSystemDelete.value = null
    }

    private fun finishDeleteSummary(extraDeleted: Int, extraFailed: Int, total: Int = 0) {
        val deleted = directDeletedCount + extraDeleted
        val failed = directFailedCount + extraFailed
        val resolvedTotal = if (total > 0) total else (deleted + failed)
        val message = when {
            deleted > 0 && failed == 0 ->
                "Deleted $deleted / $resolvedTotal file(s) from this device"
            deleted > 0 ->
                "Deleted $deleted / $resolvedTotal; $failed could not be removed"
            failed > 0 ->
                "Could not delete $failed / $resolvedTotal file(s). Android may block removal for some gallery items."
            else ->
                "No files deleted"
        }
        _deleteStatus.value = DeleteStatus(
            deleted = deleted,
            failed = failed,
            total = resolvedTotal,
            processed = resolvedTotal,
            inProgress = false,
            currentName = null,
            message = message
        )
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

    private sealed class DeleteAttempt {
        object Success : DeleteAttempt()
        data class NeedsUserConsent(val mediaUri: Uri) : DeleteAttempt()
        object Failed : DeleteAttempt()
    }

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
        } catch (e: Exception) {
            Log.w(TAG, "ContentResolver delete failed for $uri", e)
        }

        // Both direct deletes were denied. On API 30+ the system offers a recoverable,
        // user-confirmed bulk delete via MediaStore — but createDeleteRequest() only
        // accepts media:// URIs. Files picked through the SAF folder picker (DCIM/SD-card
        // sync) carry externalstorage.documents URIs instead, so translate them to their
        // MediaStore equivalent first; that's what actually lets DCIM/gallery items be
        // removed instead of always failing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isMediaStoreUri(uri)) {
                return DeleteAttempt.NeedsUserConsent(uri)
            }
            val mediaUri = resolveMediaStoreUri(uri)
            if (mediaUri != null) {
                return DeleteAttempt.NeedsUserConsent(mediaUri)
            }
        }
        return DeleteAttempt.Failed
    }

    /**
     * Translate a SAF document or Photo Picker URI to the real MediaStore URI for that
     * file, if it's media-indexed.
     *
     * Tries [MediaStore.getMediaUri] first (documented for SAF/FileProvider documents;
     * it rejects Photo Picker URIs with "Provider for this Uri is not supported" even
     * though picker items are ordinary on-device MediaStore rows).
     *
     * For a Photo Picker URI (content://media/picker/<user>/<authority>/media/<id>) the
     * trailing <id> IS the real MediaStore row id for local items — confirmed via
     * `adb shell content query --uri content://media/external/file --where "_id=<id>"`,
     * which returns the real row (e.g. IMG_20260828_101635.jpg) even though the picker's
     * own DISPLAY_NAME for that same URI is a synthetic "<id>.<ext>" placeholder (privacy:
     * apps with only picker-scoped access shouldn't see real camera filenames). That
     * synthetic name is why a DISPLAY_NAME-based MediaStore query can never match — the
     * id has to be read straight off the picker URI instead.
     */
    private fun resolveMediaStoreUri(uri: Uri): Uri? {
        runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()?.let { return it }

        if (uri.authority == MediaStore.AUTHORITY && uri.pathSegments.firstOrNull() == "picker") {
            uri.lastPathSegment?.toLongOrNull()?.let { id ->
                return ContentUris.withAppendedId(mediaCollectionFor(uri), id)
            }
        }

        return resolveMediaStoreUriByQuery(uri)
    }

    private fun resolveMediaStoreUriByQuery(uri: Uri): Uri? {
        val name = displayName(uri)
        if (name.isBlank() || name == "file") return null
        val size = contentLength(uri)

        // Search the unified Files collection (matches any media type by name/size), but
        // createDeleteRequest() rejects that generic collection's URIs ("All requested
        // items must be Media items") — the found id has to be re-wrapped in the specific
        // Images/Video collection URI to actually be usable for a delete request.
        val searchCollection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = StringBuilder("${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?")
        val args = mutableListOf(name)
        if (size > 0) {
            selection.append(" AND ${MediaStore.Files.FileColumns.SIZE} = ?")
            args.add(size.toString())
        }

        return try {
            context.contentResolver.query(
                searchCollection,
                projection,
                selection.toString(),
                args.toTypedArray(),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    ContentUris.withAppendedId(mediaCollectionFor(uri), id)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed for $name", e)
            null
        }
    }

    /** The specific MediaStore collection createDeleteRequest() will accept for this file. */
    private fun mediaCollectionFor(uri: Uri): Uri {
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        return if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    // Real MediaStore item rows (content://media/external/... or /internal/...) are usable
    // directly with createDeleteRequest(). The system Photo Picker's URIs also carry the
    // "media" authority (content://media/picker/...) but createDeleteRequest rejects them —
    // those must go through resolveMediaStoreUri()/getMediaUri() first, same as SAF documents.
    private fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return false
        val path = uri.path ?: return false
        return path.startsWith("/external/") || path.startsWith("/internal/")
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

    fun takePersistableTreePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        } catch (_: SecurityException) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not take persistable tree permission for $treeUri", e)
            }
        }
    }

    /**
     * List **all** files under a tree URI from [OpenDocumentTree] using an
     * iterative BFS (no deep recursion → no stack overflow on nested trees).
     * Relative paths start with the selected folder name
     * (e.g. `DCIM/Camera/IMG_001.jpg`).
     */
    suspend fun listFilesInTree(treeUri: Uri): List<SelectedItem> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<SelectedItem>()
            try {
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootName = treeRootDisplayName(treeUri, rootId)
                // BFS: (documentId, relativeDirPath)
                val queue = ArrayDeque<Pair<String, String>>()
                queue.add(rootId to rootName)

                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                )

                while (queue.isNotEmpty()) {
                    val (documentId, relativeDir) = queue.removeFirst()
                    val childrenUri =
                        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                    try {
                        context.contentResolver.query(
                            childrenUri,
                            projection,
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            val idIdx =
                                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val mimeIdx =
                                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            val nameIdx =
                                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            if (idIdx < 0 || mimeIdx < 0) return@use
                            while (cursor.moveToNext()) {
                                val childId = cursor.getString(idIdx) ?: continue
                                val mime = cursor.getString(mimeIdx).orEmpty()
                                val childName = if (nameIdx >= 0) {
                                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                                } else {
                                    null
                                } ?: childId.substringAfterLast(':').substringAfterLast('/')

                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                    val subDirName = SyncClient.sanitizeFilename(childName)
                                    val nextDir =
                                        if (relativeDir.isBlank()) subDirName
                                        else "$relativeDir/$subDirName"
                                    queue.add(childId to nextDir)
                                } else {
                                    val fileName = SyncClient.sanitizeFilename(childName)
                                    val relativePath =
                                        if (relativeDir.isBlank()) fileName
                                        else "$relativeDir/$fileName"
                                    result.add(
                                        SelectedItem(
                                            uri = DocumentsContract.buildDocumentUriUsingTree(
                                                treeUri,
                                                childId
                                            ),
                                            relativePath = relativePath
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "No access under $documentId", e)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error listing $documentId", e)
                    }
                }
                Log.i(TAG, "Listed ${result.size} file(s) under folder tree")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list folder $treeUri", e)
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM listing folder (partial ${result.size} files)", oom)
                System.gc()
            }
            result
        }

    private fun treeRootDisplayName(treeUri: Uri, rootDocumentId: String): String {
        // Prefer the document display name; fall back to the last path segment of the doc id.
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val fromQuery = displayName(docUri)
        if (fromQuery.isNotBlank() && fromQuery != "file") {
            return SyncClient.sanitizeFilename(fromQuery)
        }
        val fromId = rootDocumentId
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { "folder" }
        return SyncClient.sanitizeFilename(fromId)
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
        /** Max rows shown in the selected-file list (full count still syncs). */
        const val UI_LIST_CAP = 100
    }
}

data class DeleteStatus(
    val deleted: Int,
    val failed: Int,
    val total: Int = 0,
    /** How many files have been attempted so far (deleted + failed + skipped). */
    val processed: Int = deleted + failed,
    val inProgress: Boolean = false,
    val currentName: String? = null,
    val message: String
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

/** A user-selected file, optionally with a folder-relative path for tree sync. */
data class SelectedItem(
    val uri: Uri,
    /** e.g. `Camera/Vacation/IMG_001.jpg` when picked via Folder. */
    val relativePath: String? = null
) {
    fun displayLabel(): String = relativePath?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "file"
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
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
            // Extra safety if the host still draws edge-to-edge.
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AppHeaderBar(
            versionName = viewModel.appVersionName,
            showBack = showAbout,
            onInfoClick = { showAbout = true },
            onBackClick = { showAbout = false }
        )

        Box(modifier = Modifier.fillMaxSize()) {
            if (showAbout) {
                AboutScreen()
            } else {
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
    }
}

/** Persistent header shown at the top of every screen: app name, version, and About access. */
@Composable
fun AppHeaderBar(
    versionName: String,
    showBack: Boolean,
    onInfoClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF673AB7))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }
        Text(
            text = "LanSync v$versionName",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (showBack) 0.dp else 12.dp)
        )
        if (!showBack) {
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "About LanSync",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "About LanSync",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "LanSync copies photos, videos, and folders from this device to a " +
                "computer on the same local network. It connects directly over Wi-Fi to a " +
                "LanSync server — there is no cloud, and nothing leaves your network.",
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "A running server is required",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This app cannot sync on its own. Before connecting, the LanSync server " +
                "must be running on a computer on the same local network as this device " +
                "(the server listens on port 10101 by default). Start the server, note the " +
                "computer's IP address, and enter it on the connect screen along with a " +
                "username and device name.",
            fontSize = 15.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Once connected, pick photos, videos, documents, or an entire folder " +
                "(such as DCIM or an SD card) to upload. After a successful sync you can " +
                "optionally remove the synced files from this device to free up space.",
            fontSize = 15.sp
        )
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
                    deviceName = deviceName.trim(),
                    appVersion = viewModel.appVersionName
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
    var selectedItems by remember { mutableStateOf<List<SelectedItem>>(emptyList()) }
    var isScanningFolder by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // Checked-for-removal set, keyed the same way as the LazyColumn item key below.
    var checkedForRemoval by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    fun itemKey(item: SelectedItem) = "${item.uri}|${item.relativePath.orEmpty()}"

    fun addItems(items: List<SelectedItem>) {
        if (items.isEmpty()) return
        viewModel.takePersistablePermissions(items.map { it.uri })
        selectedItems = (selectedItems + items).distinctBy { it.uri.toString() to it.relativePath }
    }

    fun addUris(uris: List<Uri>) {
        addItems(uris.map { SelectedItem(uri = it, relativePath = null) })
    }

    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        addUris(uris)
        if (uris.isNotEmpty()) statusMessage = "Added ${uris.size} photo(s)"
    }

    val pickVideos = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        addUris(uris)
        if (uris.isNotEmpty()) statusMessage = "Added ${uris.size} video(s)"
    }

    val pickDocuments = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        addUris(uris)
        if (uris.isNotEmpty()) statusMessage = "Added ${uris.size} document(s)"
    }

    // Multi-file pick (any type) — not a folder.
    val pickAnyFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        addUris(uris)
        if (uris.isNotEmpty()) statusMessage = "Added ${uris.size} file(s)"
    }

    // True folder picker via Storage Access Framework.
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) {
            statusMessage = "Folder selection cancelled"
            return@rememberLauncherForActivityResult
        }
        viewModel.takePersistableTreePermission(treeUri)
        scope.launch {
            isScanningFolder = true
            statusMessage = "Scanning folder…"
            try {
                val files = viewModel.listFilesInTree(treeUri)
                if (files.isEmpty()) {
                    statusMessage = "No files found in that folder"
                } else {
                    addItems(files)
                    val rootHint = files.first().relativePath?.substringBefore('/') ?: "folder"
                    statusMessage =
                        "Added ${files.size} file(s) from “$rootHint”. " +
                            "Large media is streamed (safe for DCIM / SD card)."
                }
            } catch (e: Exception) {
                statusMessage = "Folder scan failed: ${e.message}"
            } catch (oom: OutOfMemoryError) {
                System.gc()
                statusMessage = "Not enough memory to list that folder. Try a smaller subfolder."
            }
            isScanningFolder = false
        }
    }

    // Layout: fixed header + Start Sync always visible near the top; only the
    // file list scrolls. Avoids the primary action sitting under gesture nav.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Select Files to Sync",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
        )

        // Primary action — always on screen, never at the bottom edge.
        Button(
            onClick = { viewModel.startSync(selectedItems) },
            enabled = selectedItems.isNotEmpty() && !isScanningFolder,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (selectedItems.isEmpty()) {
                    "Select files below, then sync"
                } else {
                    "Start Sync (${selectedItems.size})"
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Add files", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { pickImages.launch("image/*") },
                enabled = !isScanningFolder
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Photos")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { pickVideos.launch("video/*") },
                enabled = !isScanningFolder
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Videos")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { pickDocuments.launch(arrayOf("application/*", "text/*")) },
                enabled = !isScanningFolder
            ) {
                Icon(Icons.Default.Description, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Documents")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { pickFolder.launch(null) },
                enabled = !isScanningFolder
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Folder")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { pickAnyFiles.launch(arrayOf("*/*")) },
                enabled = !isScanningFolder
            ) {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Files")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SuggestedDirectoriesCard(
            onPhotos = { pickImages.launch("image/*") },
            onDocuments = { pickDocuments.launch(arrayOf("application/*", "text/*")) },
            onVideos = { pickVideos.launch("video/*") },
            onFolder = { pickFolder.launch(null) }
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (isScanningFolder) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Scanning folder…", color = Color.Gray)
            }
        }

        statusMessage?.let { msg ->
            Text(
                msg,
                color = Color(0xFF1565C0),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (selectedItems.isEmpty()) "No files selected" else "Selected (${selectedItems.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (selectedItems.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (checkedForRemoval.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                selectedItems = selectedItems.filterNot { itemKey(it) in checkedForRemoval }
                                checkedForRemoval = emptySet()
                            },
                            enabled = !isScanningFolder
                        ) {
                            Text("Remove (${checkedForRemoval.size})")
                        }
                    }
                    TextButton(
                        onClick = {
                            selectedItems = emptyList()
                            checkedForRemoval = emptySet()
                            statusMessage = null
                        },
                        enabled = !isScanningFolder
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        if (selectedItems.isNotEmpty()) {
            val allKeys = selectedItems.map(::itemKey).toSet()
            val allChecked = checkedForRemoval.isNotEmpty() && checkedForRemoval.containsAll(allKeys)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        checkedForRemoval = if (allChecked) emptySet() else allKeys
                    }
            ) {
                Checkbox(
                    checked = allChecked,
                    onCheckedChange = { checked ->
                        checkedForRemoval = if (checked) allKeys else emptySet()
                    }
                )
                Text("Select all", fontSize = 14.sp, color = Color.Gray)
            }
        }

        if (selectedItems.isEmpty() && !isScanningFolder) {
            Text(
                "Folder pick recreates the folder name and all subfolders on the server.",
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else if (selectedItems.isNotEmpty()) {
            val visible = if (selectedItems.size > SyncViewModel.UI_LIST_CAP) {
                selectedItems.take(SyncViewModel.UI_LIST_CAP)
            } else {
                selectedItems
            }
            if (selectedItems.size > SyncViewModel.UI_LIST_CAP) {
                Text(
                    "Showing first ${SyncViewModel.UI_LIST_CAP} of ${selectedItems.size} files " +
                        "(all will still sync)",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    visible,
                    key = { itemKey(it) }
                ) { item ->
                    val key = itemKey(item)
                    FileItemCard(
                        filename = item.relativePath
                            ?: viewModel.displayName(item.uri),
                        checked = key in checkedForRemoval,
                        onCheckedChange = { checked ->
                            checkedForRemoval = if (checked) {
                                checkedForRemoval + key
                            } else {
                                checkedForRemoval - key
                            }
                        },
                        onRemove = {
                            selectedItems = selectedItems.filterNot {
                                it.uri == item.uri && it.relativePath == item.relativePath
                            }
                            checkedForRemoval = checkedForRemoval - key
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SuggestedDirectoriesCard(
    onPhotos: () -> Unit,
    onDocuments: () -> Unit,
    onVideos: () -> Unit,
    onFolder: () -> Unit = {}
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
                "🎬 Videos" to onVideos,
                "📁 Folder" to onFolder
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
fun FileItemCard(
    filename: String,
    checked: Boolean = false,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onRemove: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onCheckedChange != null) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        } else {
            Spacer(modifier = Modifier.width(12.dp))
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green)
            Spacer(modifier = Modifier.width(12.dp))
        }
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
    val completed = items.count { it.status == UploadStatus.COMPLETED }
    val failed = items.count { it.status == UploadStatus.FAILED }
    val skipped = items.count { it.status == UploadStatus.SKIPPED }
    // Only render a window of cards — huge DCIM runs would freeze Compose.
    val active = items.filter { it.status == UploadStatus.UPLOADING || it.status == UploadStatus.PENDING }
        .take(20)
    val recentDone = items.filter {
        it.status == UploadStatus.COMPLETED ||
            it.status == UploadStatus.FAILED ||
            it.status == UploadStatus.SKIPPED
    }.takeLast(15)
    val visible = (active + recentDone).distinctBy { "${it.fileInfo.filename}|${it.fileInfo.fileHash}" }

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
            if (items.isEmpty()) {
                "Preparing..."
            } else {
                "Progress: $done / ${items.size}  ·  ✓ $completed  ✗ $failed  ⊘ $skipped"
            },
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        LazyColumn {
            items(visible, key = { "${it.fileInfo.filename}|${it.fileInfo.fileHash}" }) { fileProgress ->
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
    var applyToAll by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { viewModel.resolveConflict(false, applyToAll) },
        title = { Text("Duplicate File") },
        text = {
            Column {
                Text("'${fileInfo.filename}' already exists. Override?")
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToAll = !applyToAll }
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Apply to all remaining duplicates")
                }
            }
        },
        confirmButton = {
            Button(onClick = { viewModel.resolveConflict(true, applyToAll) }) {
                Text("Override")
            }
        },
        dismissButton = {
            Button(onClick = { viewModel.resolveConflict(false, applyToAll) }) {
                Text("Skip")
            }
        }
    )
}

@Composable
fun DeleteProgressCard(status: DeleteStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (status.inProgress) "Deleting from device…" else "Delete result",
                    fontWeight = FontWeight.SemiBold
                )
                if (status.total > 0) {
                    Text(
                        "${status.processed} / ${status.total}",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (status.total > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { status.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Deleted ${status.deleted} · Failed ${status.failed}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            status.currentName?.takeIf { status.inProgress && it.isNotBlank() }?.let { name ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    name,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                status.message,
                color = when {
                    status.inProgress -> Color(0xFF1565C0)
                    status.deleted > 0 && status.failed == 0 -> Color(0xFF2E7D32)
                    status.failed > 0 -> Color(0xFFE65100)
                    else -> Color.Gray
                }
            )
        }
    }
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

    // READ_MEDIA_IMAGES/VIDEO let the app look up a gallery-picked file's real MediaStore
    // row by name when it only holds a Photo Picker or SAF URI, which is required to
    // request its deletion. Proceed either way — deletion just can't resolve extra items
    // if the user declines, same as before this permission existed.
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.confirmDeleteLocalFiles()
    }
    fun startDelete() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermissionLauncher.launch(permissions)
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
                Button(onClick = { startDelete() }) {
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
            Spacer(modifier = Modifier.height(16.dp))
            DeleteProgressCard(status)
        }

        Spacer(modifier = Modifier.height(24.dp))

        val deleting = deleteStatus?.inProgress == true

        if (deletableUris.isNotEmpty() && !showDeletePrompt && deleteStatus == null) {
            Button(
                onClick = { startDelete() },
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
            enabled = !deleting,
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
            val failedSyncs by viewModel.failedSyncs
            if (failedSyncs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                FailedSyncsSection(failedSyncs = failedSyncs, viewModel = viewModel)
            }

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

/** Failed uploads kept across restarts, with select-all + retry/dismiss in bulk. */
@Composable
fun FailedSyncsSection(failedSyncs: List<FailedSyncEntry>, viewModel: SyncViewModel) {
    var checked by remember(failedSyncs) { mutableStateOf<Set<String>>(emptySet()) }
    val allChecked = checked.isNotEmpty() && checked.size == failedSyncs.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            "Failed Syncs (${failedSyncs.size})",
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE65100)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    checked = if (allChecked) emptySet() else failedSyncs.map { it.uri }.toSet()
                }
        ) {
            Checkbox(
                checked = allChecked,
                onCheckedChange = { isChecked ->
                    checked = if (isChecked) failedSyncs.map { it.uri }.toSet() else emptySet()
                }
            )
            Text("Select all", fontSize = 14.sp, color = Color.Gray)
        }

        failedSyncs.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        checked = if (entry.uri in checked) checked - entry.uri else checked + entry.uri
                    }
            ) {
                Checkbox(
                    checked = entry.uri in checked,
                    onCheckedChange = { isChecked ->
                        checked = if (isChecked) checked + entry.uri else checked - entry.uri
                    }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.displayName, fontSize = 13.sp)
                    Text(entry.errorMessage, fontSize = 11.sp, color = Color.Gray, maxLines = 2)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val selected = failedSyncs.filter { it.uri in checked }
                    checked = emptySet()
                    viewModel.retryFailedSyncs(selected)
                },
                enabled = checked.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Retry (${checked.size})")
            }
            OutlinedButton(
                onClick = {
                    val selected = failedSyncs.filter { it.uri in checked }
                    checked = emptySet()
                    viewModel.dismissFailedSyncs(selected)
                },
                enabled = checked.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text("Remove (${checked.size})")
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
