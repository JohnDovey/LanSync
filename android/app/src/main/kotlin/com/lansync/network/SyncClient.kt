package com.lansync.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
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

/**
 * Single TCP connection to the LanSync server.
 *
 * All protocol I/O is serialized with [ioMutex] — the server keeps one
 * upload session per connection, so concurrent writes corrupt the stream.
 */
class SyncClient(private val config: ServerConfig) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val ioMutex = Mutex()
    private val TAG = "SyncClient"

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                closeUnlocked()
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                socket = sock
                input = DataInputStream(sock.getInputStream())
                output = DataOutputStream(sock.getOutputStream())

                handshakeUnlocked().getOrThrow()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to ${config.host}:${config.port}", e)
                closeUnlocked()
                Result.failure(Exception("Connect to ${config.host}:${config.port} failed: ${e.message}", e))
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    private fun handshakeUnlocked(): Result<Unit> {
        return try {
            val out = output ?: return Result.failure(Exception("Not connected"))
            val inp = input ?: return Result.failure(Exception("Not connected"))

            out.writeByte(BinaryProtocol.CMD_HANDSHAKE.toInt() and 0xFF)

            val usernameBytes = config.username.toByteArray(Charsets.UTF_8)
            out.writeShort(usernameBytes.size)
            out.write(usernameBytes)

            val deviceBytes = config.deviceName.toByteArray(Charsets.UTF_8)
            out.writeShort(deviceBytes.size)
            out.write(deviceBytes)

            out.flush()

            val response = inp.readByte()
            if (response == BinaryProtocol.RESP_OK) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Handshake rejected (code=${response.toUByte()})"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake error", e)
            Result.failure(e)
        }
    }

    suspend fun checkDuplicate(fileInfo: FileInfo): Result<DuplicateCheckResult> =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                try {
                    val out = output ?: return@withLock Result.failure(Exception("Not connected"))
                    val inp = input ?: return@withLock Result.failure(Exception("Not connected"))

                    out.writeByte(BinaryProtocol.CMD_CHECK_DUPLICATE.toInt() and 0xFF)

                    val hashBytes = fileInfo.fileHash.toByteArray(Charsets.UTF_8)
                    out.writeShort(hashBytes.size)
                    out.write(hashBytes)

                    val filenameBytes = fileInfo.filename.toByteArray(Charsets.UTF_8)
                    out.writeShort(filenameBytes.size)
                    out.write(filenameBytes)

                    out.writeLong(fileInfo.fileSize)
                    out.flush()

                    val response = inp.readByte()
                    val result = when (response) {
                        BinaryProtocol.RESP_OK ->
                            DuplicateCheckResult(isDuplicate = false)
                        BinaryProtocol.RESP_DUPLICATE ->
                            DuplicateCheckResult(isDuplicate = true, shouldUpdate = false)
                        BinaryProtocol.RESP_NEED_UPDATE ->
                            DuplicateCheckResult(isDuplicate = true, shouldUpdate = true)
                        BinaryProtocol.RESP_ERROR ->
                            throw Exception("Server error during duplicate check")
                        else -> throw Exception("Invalid duplicate-check response: ${response.toUByte()}")
                    }
                    Result.success(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Duplicate check failed for ${fileInfo.filename}", e)
                    Result.failure(e)
                }
            }
        }

    suspend fun uploadFile(
        fileInfo: FileInfo,
        fileData: ByteArray,
        onBytesSent: ((Long) -> Unit)? = null
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                try {
                    val out = output ?: return@withLock Result.failure(Exception("Not connected"))
                    val inp = input ?: return@withLock Result.failure(Exception("Not connected"))

                    // UPLOAD_START
                    out.writeByte(BinaryProtocol.CMD_UPLOAD_START.toInt() and 0xFF)

                    val filenameBytes = fileInfo.filename.toByteArray(Charsets.UTF_8)
                    out.writeShort(filenameBytes.size)
                    out.write(filenameBytes)

                    val hashBytes = fileInfo.fileHash.toByteArray(Charsets.UTF_8)
                    out.writeShort(hashBytes.size)
                    out.write(hashBytes)

                    val typeBytes = fileInfo.fileType.toByteArray(Charsets.UTF_8)
                    out.writeShort(typeBytes.size)
                    out.write(typeBytes)

                    val dirBytes = fileInfo.directory.toByteArray(Charsets.UTF_8)
                    out.writeShort(dirBytes.size)
                    out.write(dirBytes)

                    out.writeLong(fileData.size.toLong())
                    out.flush()

                    var response = inp.readByte()
                    if (response != BinaryProtocol.RESP_OK) {
                        throw Exception("Upload start failed (code=${response.toUByte()})")
                    }

                    // Chunks (empty files skip this loop)
                    var offset = 0
                    while (offset < fileData.size) {
                        val chunkSize = min(BinaryProtocol.CHUNK_SIZE, fileData.size - offset)
                        out.writeByte(BinaryProtocol.CMD_UPLOAD_CHUNK.toInt() and 0xFF)
                        out.writeInt(chunkSize)
                        out.write(fileData, offset, chunkSize)
                        out.flush()

                        response = inp.readByte()
                        if (response != BinaryProtocol.RESP_OK) {
                            throw Exception("Chunk upload failed at offset $offset (code=${response.toUByte()})")
                        }

                        offset += chunkSize
                        onBytesSent?.invoke(offset.toLong())
                    }

                    // UPLOAD_END
                    out.writeByte(BinaryProtocol.CMD_UPLOAD_END.toInt() and 0xFF)

                    out.writeShort(filenameBytes.size)
                    out.write(filenameBytes)

                    out.writeShort(hashBytes.size)
                    out.write(hashBytes)

                    out.writeShort(typeBytes.size)
                    out.write(typeBytes)

                    out.writeShort(dirBytes.size)
                    out.write(dirBytes)

                    out.flush()

                    response = inp.readByte()
                    if (response != BinaryProtocol.RESP_OK) {
                        Result.failure(Exception("Upload end failed (code=${response.toUByte()})"))
                    } else {
                        onBytesSent?.invoke(fileData.size.toLong())
                        Result.success(Unit)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Upload failed for ${fileInfo.filename}", e)
                    Result.failure(e)
                }
            }
        }

    suspend fun confirmDelete(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val out = output ?: return@withLock Result.failure(Exception("Not connected"))
                val inp = input ?: return@withLock Result.failure(Exception("Not connected"))

                out.writeByte(BinaryProtocol.CMD_DELETE_CONFIRM.toInt() and 0xFF)
                out.flush()

                val response = inp.readByte()
                if (response == BinaryProtocol.RESP_OK) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Delete confirm failed (code=${response.toUByte()})"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete confirm error", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getSyncHistory(): Result<List<String>> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val out = output ?: return@withLock Result.failure(Exception("Not connected"))
                val inp = input ?: return@withLock Result.failure(Exception("Not connected"))

                out.writeByte(BinaryProtocol.CMD_SYNC_HISTORY.toInt() and 0xFF)
                out.flush()

                val response = inp.readByte()
                if (response != BinaryProtocol.RESP_OK) {
                    return@withLock Result.failure(Exception("History request failed (code=${response.toUByte()})"))
                }

                val count = inp.readInt()
                if (count < 0 || count > 10_000) {
                    return@withLock Result.failure(Exception("Invalid history count: $count"))
                }

                val history = mutableListOf<String>()
                repeat(count) {
                    val len = inp.readUnsignedShort()
                    if (len > 0) {
                        val bytes = ByteArray(len)
                        inp.readFully(bytes)
                        history.add(String(bytes, Charsets.UTF_8))
                    }
                }

                Result.success(history)
            } catch (e: Exception) {
                Log.e(TAG, "Get history failed", e)
                Result.failure(e)
            }
        }
    }

    fun close() {
        // Best-effort close; callers typically already hold no lock or are done.
        try {
            closeUnlocked()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        }
    }

    private fun closeUnlocked() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false
}
