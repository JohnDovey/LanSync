package com.lansync.manager

import android.util.Log
import com.lansync.network.FileInfo
import com.lansync.network.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Uploads files over a single [SyncClient] connection.
 *
 * Uploads run **sequentially** — the protocol supports one session per TCP
 * connection, and the client mutex already serializes I/O.
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

    suspend fun uploadFiles(
        files: List<Pair<FileInfo, ByteArray>>,
        onConflict: suspend (FileInfo) -> ConflictResolution?
    ): Result<UploadSummary> = withContext(Dispatchers.IO) {
        globalConflictResolution = null
        uploadProgress.clear()

        // Seed progress so the UI shows every file immediately.
        files.forEach { (fileInfo, fileData) ->
            val key = progressKey(fileInfo)
            uploadProgress[key] = UploadProgress(
                fileInfo = fileInfo,
                currentBytes = 0,
                totalBytes = fileData.size.toLong(),
                percentage = 0,
                status = UploadStatus.PENDING
            )
        }
        notifyProgress()

        try {
            for ((fileInfo, fileData) in files) {
                uploadSingleFile(fileInfo, fileData, onConflict)
            }
            Result.success(generateSummary())
        } catch (e: Exception) {
            Log.e(TAG, "Upload batch failed", e)
            Result.failure(e)
        }
    }

    private suspend fun uploadSingleFile(
        fileInfo: FileInfo,
        fileData: ByteArray,
        onConflict: suspend (FileInfo) -> ConflictResolution?
    ) {
        val key = progressKey(fileInfo)
        try {
            updateProgress(key, fileInfo, UploadStatus.UPLOADING, currentBytes = 0, totalBytes = fileData.size.toLong())

            val dupResult = syncClient.checkDuplicate(fileInfo)
            if (dupResult.isFailure) {
                updateProgress(
                    key,
                    fileInfo,
                    UploadStatus.FAILED,
                    totalBytes = fileData.size.toLong(),
                    error = dupResult.exceptionOrNull()?.message ?: "Duplicate check failed"
                )
                return
            }

            val dup = dupResult.getOrNull() ?: return

            if (dup.isDuplicate && !dup.shouldUpdate) {
                // Identical file already on server — skip (or override if user chose apply-to-all).
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
                        currentBytes = fileData.size.toLong(),
                        totalBytes = fileData.size.toLong()
                    )
                    return
                }
            }

            val uploadResult = syncClient.uploadFile(fileInfo, fileData) { sent ->
                updateProgress(
                    key,
                    fileInfo,
                    UploadStatus.UPLOADING,
                    currentBytes = sent,
                    totalBytes = fileData.size.toLong()
                )
            }

            if (uploadResult.isSuccess) {
                updateProgress(
                    key,
                    fileInfo,
                    UploadStatus.COMPLETED,
                    currentBytes = fileData.size.toLong(),
                    totalBytes = fileData.size.toLong()
                )
                Log.i(TAG, "Successfully uploaded ${fileInfo.filename}")
            } else {
                updateProgress(
                    key,
                    fileInfo,
                    UploadStatus.FAILED,
                    totalBytes = fileData.size.toLong(),
                    error = uploadResult.exceptionOrNull()?.message ?: "Upload failed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for ${fileInfo.filename}", e)
            updateProgress(
                key,
                fileInfo,
                UploadStatus.FAILED,
                totalBytes = fileData.size.toLong(),
                error = e.message ?: "Unknown error"
            )
        }
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

    private fun generateSummary(): UploadSummary {
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
        // Connection close is owned by the ViewModel / SyncClient.
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
