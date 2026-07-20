package com.lansync.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import kotlin.math.min

// ============================================================================
// BINARY PROTOCOL COMMANDS & RESPONSES
// ============================================================================

object BinaryProtocol {
    // Commands
    const val CMD_HANDSHAKE = 0x01.toByte()
    const val CMD_CHECK_DUPLICATE = 0x02.toByte()
    const val CMD_UPLOAD_START = 0x03.toByte()
    const val CMD_UPLOAD_CHUNK = 0x04.toByte()
    const val CMD_UPLOAD_END = 0x05.toByte()
    const val CMD_DELETE_CONFIRM = 0x06.toByte()
    const val CMD_SYNC_HISTORY = 0x07.toByte()

    // Responses
    const val RESP_OK = 0x00.toByte()
    const val RESP_DUPLICATE = 0x01.toByte()
    const val RESP_ERROR = 0x02.toByte()
    const val RESP_NEED_UPDATE = 0x03.toByte()

    const val CHUNK_SIZE = 65536 // 64KB chunks
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val username: String,
    val deviceName: String
)

data class FileInfo(
    val filename: String,
    val fileHash: String,
    val fileType: String,      // "photos", "documents", "folders"
    val directory: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String = ""
)

data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val shouldUpdate: Boolean = false
)

sealed class SyncResult {
    data class Success(val message: String) : SyncResult()
    data class Duplicate(val allowOverride: Boolean = false) : SyncResult()
    data class Error(val message: String, val exception: Exception? = null) : SyncResult()
}

class SyncClient(private val config: ServerConfig) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val TAG = "SyncClient"

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            socket = Socket(config.host, config.port)
            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            // Handshake
            handshake()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            close()
            Result.failure(e)
        }
    }

    private suspend fun handshake(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            output?.writeByte(BinaryProtocol.CMD_HANDSHAKE.toInt())

            // Send username
            val usernameBytes = config.username.toByteArray()
            output?.writeShort(usernameBytes.size)
            output?.write(usernameBytes)

            // Send device name
            val deviceBytes = config.deviceName.toByteArray()
            output?.writeShort(deviceBytes.size)
            output?.write(deviceBytes)

            output?.flush()

            // Read response
            val response = input?.readByte()
            if (response == BinaryProtocol.RESP_OK) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Handshake failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error", e)
            Result.failure(e)
        }
    }

    suspend fun checkDuplicate(fileInfo: FileInfo): Result<DuplicateCheckResult> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                output?.writeByte(BinaryProtocol.CMD_CHECK_DUPLICATE.toInt())

                // Send file hash
                val hashBytes = fileInfo.fileHash.toByteArray()
                output?.writeShort(hashBytes.size)
                output?.write(hashBytes)

                // Send filename
                val filenameBytes = fileInfo.filename.toByteArray()
                output?.writeShort(filenameBytes.size)
                output?.write(filenameBytes)

                // Send file size
                output?.writeLong(fileInfo.fileSize)

                output?.flush()

                // Read response
                val response = input?.readByte()
                val result = when (response) {
                    BinaryProtocol.RESP_OK ->
                        DuplicateCheckResult(isDuplicate = false)
                    BinaryProtocol.RESP_DUPLICATE ->
                        DuplicateCheckResult(isDuplicate = true, shouldUpdate = false)
                    BinaryProtocol.RESP_NEED_UPDATE ->
                        DuplicateCheckResult(isDuplicate = true, shouldUpdate = true)
                    else -> throw Exception("Invalid response: $response")
                }
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Duplicate check failed", e)
                Result.failure(e)
            }
        }

    suspend fun uploadFile(fileInfo: FileInfo, fileData: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                // UPLOAD_START
                output?.writeByte(BinaryProtocol.CMD_UPLOAD_START.toInt())

                val filenameBytes = fileInfo.filename.toByteArray()
                output?.writeShort(filenameBytes.size)
                output?.write(filenameBytes)

                val hashBytes = fileInfo.fileHash.toByteArray()
                output?.writeShort(hashBytes.size)
                output?.write(hashBytes)

                val typeBytes = fileInfo.fileType.toByteArray()
                output?.writeShort(typeBytes.size)
                output?.write(typeBytes)

                val dirBytes = fileInfo.directory.toByteArray()
                output?.writeShort(dirBytes.size)
                output?.write(dirBytes)

                output?.writeLong(fileData.size.toLong())
                output?.flush()

                // Read OK
                var response = input?.readByte()
                if (response != BinaryProtocol.RESP_OK) {
                    throw Exception("Upload start failed")
                }

                // Send chunks
                var offset = 0
                while (offset < fileData.size) {
                    val chunkSize = min(BinaryProtocol.CHUNK_SIZE, fileData.size - offset)
                    output?.writeByte(BinaryProtocol.CMD_UPLOAD_CHUNK.toInt())
                    output?.writeInt(chunkSize)
                    output?.write(fileData, offset, chunkSize)
                    output?.flush()

                    response = input?.readByte()
                    if (response != BinaryProtocol.RESP_OK) {
                        throw Exception("Chunk upload failed at offset $offset")
                    }

                    offset += chunkSize
                }

                // UPLOAD_END
                output?.writeByte(BinaryProtocol.CMD_UPLOAD_END.toInt())

                output?.writeShort(filenameBytes.size)
                output?.write(filenameBytes)

                output?.writeShort(hashBytes.size)
                output?.write(hashBytes)

                output?.writeShort(typeBytes.size)
                output?.write(typeBytes)

                output?.writeShort(dirBytes.size)
                output?.write(dirBytes)

                output?.flush()

                response = input?.readByte()
                if (response != BinaryProtocol.RESP_OK) {
                    Result.failure(Exception("Upload end failed"))
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                Result.failure(e)
            }
        }

    suspend fun confirmDelete(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            output?.writeByte(BinaryProtocol.CMD_DELETE_CONFIRM.toInt())
            output?.flush()

            val response = input?.readByte()
            if (response == BinaryProtocol.RESP_OK) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Delete confirm failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete confirm error", e)
            Result.failure(e)
        }
    }

    suspend fun getSyncHistory(): Result<List<String>> = withContext(Dispatchers.IO) {
        return@withContext try {
            output?.writeByte(BinaryProtocol.CMD_SYNC_HISTORY.toInt())
            output?.flush()

            val response = input?.readByte()
            if (response != BinaryProtocol.RESP_OK) {
                return@withContext Result.failure(Exception("History request failed"))
            }

            val count = input?.readInt() ?: 0
            val history = mutableListOf<String>()

            repeat(count) {
                val len = input?.readShort()?.toInt() ?: 0
                if (len > 0) {
                    val bytes = ByteArray(len)
                    input?.readFully(bytes)
                    history.add(String(bytes))
                }
            }

            Result.success(history)
        } catch (e: Exception) {
            Log.e(TAG, "Get history failed", e)
            Result.failure(e)
        }
    }

    fun close() {
        try {
            socket?.close()
            input?.close()
            output?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
        socket = null
        input = null
        output = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false
}
