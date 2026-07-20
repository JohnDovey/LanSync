package com.lansync.manager

import android.util.Log
import com.lansync.network.FileInfo
import com.lansync.network.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

data class UploadProgress(
    val fileInfo: FileInfo,
    val currentBytes: Long = 0,
    val totalBytes: Long,
    val percentage: Int = 0,
    val status: UploadStatus = UploadStatus.PENDING,
    val errorMessage: String? = null
)

enum class UploadStatus {
    PENDING, UPLOADING, COMPLETED, FAILED, DUPLICATE, SKIPPED
}

data class ConflictResolution(
    val filename: String,
    val overrideThisFile: Boolean,
    val applyToAll: Boolean = false
)

/**
 * Uploads files over a single [SyncClient] connection, one at a time.
 * Prefer [uploadFileStreaming] so large DCIM / SD-card media never load fully into RAM.
 */
class UploadManager(
    private val syncClient: SyncClient,
    @Suppress("UNUSED_PARAMETER") private val maxConcurrent: Int = 1
) {
    private val TAG = "UploadManager"

    private val uploadProgress = ConcurrentHashMap<String, UploadProgress>()

    private var onProgressUpdate: ((Map<String, UploadProgress>) -> Unit)? = null
    private var globalConflictResolution: ConflictResolution? = null

    fun setProgressCallback(callback: (Map<String, UploadProgress>) -> Unit) {
        onProgressUpdate = callback
    }

    fun beginSession() {
        globalConflictResolution = null
        uploadProgress.clear()
        notifyProgress()
    }

    fun markFailed(fileInfo: FileInfo, error: String?) {
        fail(progressKey(fileInfo), fileInfo, fileInfo.fileSize.coerceAtLeast(0), error)
    }

    fun seedPending(files: List<FileInfo>) {
        for (fileInfo in files) {
            val key = progressKey(fileInfo)
            if (!uploadProgress.containsKey(key)) {
                uploadProgress[key] = UploadProgress(
                    fileInfo = fileInfo,
                    currentBytes = 0,
                    totalBytes = fileInfo.fileSize.coerceAtLeast(0),
                    percentage = 0,
                    status = UploadStatus.PENDING
                )
            }
        }
        // Cap notify cost for huge folders — UI reads getProgress() snapshots.
        if (uploadProgress.size <= 200 || uploadProgress.size % 50 == 0) {
            notifyProgress()
        }
    }

    /**
     * Upload one file from a re-openable stream. [openStream] is called once for
     * the upload body (hash/size must already be on [fileInfo]).
     */
    suspend fun uploadFileStreaming(
        fileInfo: FileInfo,
        openStream: () -> InputStream?,
        onConflict: suspend (FileInfo) -> ConflictResolution?
    ): Unit = withContext(Dispatchers.IO) {
        val key = progressKey(fileInfo)
        val total = fileInfo.fileSize.coerceAtLeast(0L)
        try {
            updateProgress(key, fileInfo, UploadStatus.UPLOADING, currentBytes = 0, totalBytes = total)

            var dupResult = syncClient.checkDuplicate(fileInfo)
            if (dupResult.isFailure && SyncClient.isConnectionError(dupResult.exceptionOrNull())) {
                Log.w(TAG, "Connection lost before duplicate check; reconnecting")
                val re = syncClient.reconnect()
                if (re.isFailure) {
                    fail(key, fileInfo, total, re.exceptionOrNull()?.message)
                    return@withContext
                }
                dupResult = syncClient.checkDuplicate(fileInfo)
            }
            if (dupResult.isFailure) {
                fail(key, fileInfo, total, dupResult.exceptionOrNull()?.message)
                return@withContext
            }

            val dup = dupResult.getOrNull() ?: return@withContext

            if (dup.isDuplicate && !dup.shouldUpdate) {
                val global = globalConflictResolution
                val resolution = when {
                    global != null -> global
                    else -> onConflict(fileInfo)
                }
                if (resolution?.applyToAll == true) {
                    globalConflictResolution = resolution
                }
                if (resolution == null || !resolution.overrideThisFile) {
                    updateProgress(
                        key,
                        fileInfo,
                        UploadStatus.SKIPPED,
                        currentBytes = total,
                        totalBytes = total
                    )
                    return@withContext
                }
            }

            suspend fun doUpload(): Result<Unit> {
                val stream = openStream()
                    ?: return Result.failure(Exception("Cannot open stream for ${fileInfo.filename}"))
                return stream.use { input ->
                    syncClient.uploadFile(fileInfo, input) { sent ->
                        updateProgress(
                            key,
                            fileInfo,
                            UploadStatus.UPLOADING,
                            currentBytes = sent,
                            totalBytes = total
                        )
                    }
                }
            }

            var uploadResult = doUpload()
            if (uploadResult.isFailure && SyncClient.isConnectionError(uploadResult.exceptionOrNull())) {
                Log.w(TAG, "Connection lost during upload of ${fileInfo.filename}; reconnecting and retrying once")
                val re = syncClient.reconnect()
                if (re.isSuccess) {
                    uploadResult = doUpload()
                } else {
                    fail(key, fileInfo, total, re.exceptionOrNull()?.message)
                    return@withContext
                }
            }

            if (uploadResult.isSuccess) {
                updateProgress(
                    key,
                    fileInfo,
                    UploadStatus.COMPLETED,
                    currentBytes = total,
                    totalBytes = total
                )
                Log.i(TAG, "Successfully uploaded ${fileInfo.filename}")
            } else {
                fail(
                    key,
                    fileInfo,
                    total,
                    uploadResult.exceptionOrNull()?.message ?: "Upload failed"
                )
            }
        } catch (e: OutOfMemoryError) {
            // Rare if streaming works; still catch to avoid process death when possible.
            Log.e(TAG, "OOM uploading ${fileInfo.filename}", e)
            fail(key, fileInfo, total, "Out of memory (file too large for device)")
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for ${fileInfo.filename}", e)
            if (SyncClient.isConnectionError(e)) {
                runCatching { syncClient.reconnect() }
            }
            fail(key, fileInfo, total, e.message)
        }
    }

    /** Legacy path: still available for tiny files / tests. */
    suspend fun uploadFiles(
        files: List<Pair<FileInfo, ByteArray>>,
        onConflict: suspend (FileInfo) -> ConflictResolution?,
        clearExisting: Boolean = true
    ): Result<UploadSummary> = withContext(Dispatchers.IO) {
        if (clearExisting) beginSession()
        try {
            for ((fileInfo, fileData) in files) {
                uploadFileStreaming(
                    fileInfo = fileInfo.copy(fileSize = fileData.size.toLong()),
                    openStream = { fileData.inputStream() },
                    onConflict = onConflict
                )
            }
            Result.success(generateSummary())
        } catch (e: Exception) {
            Log.e(TAG, "Upload batch failed", e)
            Result.failure(e)
        }
    }

    private fun fail(key: String, fileInfo: FileInfo, totalBytes: Long, error: String?) {
        updateProgress(
            key,
            fileInfo,
            UploadStatus.FAILED,
            totalBytes = totalBytes,
            error = error ?: "Unknown error"
        )
    }

    private fun progressKey(fileInfo: FileInfo): String =
        "${fileInfo.filename}|${fileInfo.fileHash}"

    private fun updateProgress(
        key: String,
        fileInfo: FileInfo,
        status: UploadStatus,
        currentBytes: Long = 0,
        totalBytes: Long = fileInfo.fileSize,
        error: String? = null
    ) {
        val total = totalBytes.coerceAtLeast(0)
        val current = currentBytes.coerceIn(0, if (total > 0) total else currentBytes)
        val percentage = if (total <= 0) {
            if (status == UploadStatus.COMPLETED || status == UploadStatus.SKIPPED) 100 else 0
        } else {
            ((current * 100) / total).toInt().coerceIn(0, 100)
        }

        uploadProgress[key] = UploadProgress(
            fileInfo = fileInfo,
            currentBytes = current,
            totalBytes = total,
            percentage = percentage,
            status = status,
            errorMessage = error
        )
        notifyProgress()
    }

    private fun notifyProgress() {
        onProgressUpdate?.invoke(uploadProgress.toMap())
    }

    fun generateSummary(): UploadSummary {
        val progress = uploadProgress.values
        return UploadSummary(
            total = progress.size,
            completed = progress.count { it.status == UploadStatus.COMPLETED },
            failed = progress.count { it.status == UploadStatus.FAILED },
            skipped = progress.count { it.status == UploadStatus.SKIPPED },
            duplicates = progress.count { it.status == UploadStatus.DUPLICATE }
        )
    }

    fun getProgress(): Map<String, UploadProgress> = uploadProgress.toMap()

    fun cancel() {
        uploadProgress.clear()
        notifyProgress()
    }
}

data class UploadSummary(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val skipped: Int,
    val duplicates: Int
)
