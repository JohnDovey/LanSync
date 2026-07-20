package com.lansync.manager

import android.util.Log
import com.lansync.network.FileInfo
import com.lansync.network.SyncClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

class UploadManager(
    private val syncClient: SyncClient,
    private val maxConcurrent: Int = 10
) {
    private val TAG = "UploadManager"
    
    private val activeUploads = AtomicInteger(0)
    private val uploadProgress = ConcurrentHashMap<String, UploadProgress>()
    private val uploadJob = ConcurrentHashMap<String, Job>()
    
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val semaphore = Semaphore(maxConcurrent)
    
    private var onProgressUpdate: ((Map<String, UploadProgress>) -> Unit)? = null
    private var onConflict: ((FileInfo, suspend (ConflictResolution) -> Unit) -> Unit)? = null
    private var globalConflictResolution: ConflictResolution? = null

    fun setProgressCallback(callback: (Map<String, UploadProgress>) -> Unit) {
        onProgressUpdate = callback
    }

    fun setConflictCallback(callback: (FileInfo, suspend (ConflictResolution) -> Unit) -> Unit) {
        onConflict = callback
    }

    suspend fun uploadFiles(
        files: List<Pair<FileInfo, ByteArray>>,
        onConflict: suspend (FileInfo) -> ConflictResolution?
    ): Result<UploadSummary> = withContext(Dispatchers.Main.immediate) {
        return@withContext try {
            val uploadTasks = files.map { (fileInfo, fileData) ->
                scope.launch {
                    semaphore.acquire()
                    try {
                        activeUploads.incrementAndGet()
                        uploadSingleFile(fileInfo, fileData, onConflict)
                    } finally {
                        semaphore.release()
                        activeUploads.decrementAndGet()
                    }
                }
            }

            // Wait for all uploads to complete
            uploadTasks.joinAll()

            val summary = generateSummary()
            Result.success(summary)
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
        try {
            updateProgress(fileInfo.filename, UploadStatus.UPLOADING)

            // Check for duplicate
            val dupResult = syncClient.checkDuplicate(fileInfo)
            if (dupResult.isFailure) {
                updateProgress(
                    fileInfo.filename,
                    UploadStatus.FAILED,
                    error = dupResult.exceptionOrNull()?.message ?: "Duplicate check failed"
                )
                return
            }

            val (isDuplicate, shouldUpdate) = dupResult.getOrNull() ?: return
            
            if (isDuplicate && !shouldUpdate) {
                // File exists and is identical - ask user
                val resolution = onConflict(fileInfo)
                if (resolution == null || !resolution.overrideThisFile) {
                    updateProgress(fileInfo.filename, UploadStatus.SKIPPED)
                    return
                }
            }

            // Proceed with upload
            val uploadResult = syncClient.uploadFile(fileInfo, fileData)
            if (uploadResult.isSuccess) {
                updateProgress(
                    fileInfo.filename,
                    UploadStatus.COMPLETED,
                    currentBytes = fileData.size.toLong()
                )
                Log.i(TAG, "Successfully uploaded ${fileInfo.filename}")
            } else {
                updateProgress(
                    fileInfo.filename,
                    UploadStatus.FAILED,
                    error = uploadResult.exceptionOrNull()?.message ?: "Upload failed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for ${fileInfo.filename}", e)
            updateProgress(
                fileInfo.filename,
                UploadStatus.FAILED,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun updateProgress(
        filename: String,
        status: UploadStatus,
        currentBytes: Long = 0,
        error: String? = null
    ) {
        val currentProgress = uploadProgress[filename]
        uploadProgress[filename] = UploadProgress(
            fileInfo = currentProgress?.fileInfo ?: FileInfo(filename, "", "", "", "", 0),
            currentBytes = currentBytes,
            totalBytes = currentProgress?.totalBytes ?: currentBytes,
            percentage = if (currentProgress?.totalBytes == 0L) 0 
                        else (currentBytes * 100 / (currentProgress?.totalBytes ?: 1)).toInt(),
            status = status,
            errorMessage = error
        )
        onProgressUpdate?.invoke(uploadProgress.toMap())
    }

    private fun generateSummary(): UploadSummary {
        val progress = uploadProgress.values
        return UploadSummary(
            total = progress.size,
            completed = progress.count { it.status == UploadStatus.COMPLETED }.toInt(),
            failed = progress.count { it.status == UploadStatus.FAILED }.toInt(),
            skipped = progress.count { it.status == UploadStatus.SKIPPED }.toInt(),
            duplicates = progress.count { it.status == UploadStatus.DUPLICATE }.toInt()
        )
    }

    fun getProgress(): Map<String, UploadProgress> = uploadProgress.toMap()

    suspend fun getActiveCount(): Int = activeUploads.get()

    fun cancel() {
        scope.cancel()
    }
}

data class UploadSummary(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val skipped: Int,
    val duplicates: Int
)
