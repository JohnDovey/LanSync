package com.lansync.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest

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
 * All protocol I/O is serialized with [ioMutex]. On any I/O failure the socket
 * is closed so the next call can [reconnect] cleanly (avoids "Broken pipe" on a
 * half-dead connection).
 */
class SyncClient(private val config: ServerConfig) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val ioMutex = Mutex()
    private val TAG = "SyncClient"

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        ioMutex.withLock { connectUnlocked() }
    }

    /** Re-open the TCP session + handshake (e.g. after a broken pipe). */
    suspend fun reconnect(): Result<Unit> = connect()

    private fun connectUnlocked(): Result<Unit> {
        return try {
            closeUnlocked()
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            input = DataInputStream(sock.getInputStream().buffered())
            output = DataOutputStream(sock.getOutputStream().buffered())

            handshakeUnlocked().getOrThrow()
            Log.i(TAG, "Connected to ${config.host}:${config.port}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed to ${config.host}:${config.port}", e)
            closeUnlocked()
            Result.failure(Exception(friendlyError("Connect to ${config.host}:${config.port}", e), e))
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 120_000

        fun isConnectionError(error: Throwable?): Boolean {
            var t: Throwable? = error
            while (t != null) {
                if (t is SocketException) return true
                val msg = t.message?.lowercase().orEmpty()
                if (msg.contains("broken pipe") ||
                    msg.contains("connection reset") ||
                    msg.contains("connection abort") ||
                    msg.contains("software caused connection") ||
                    msg.contains("socket closed") ||
                    msg.contains("failed to connect") ||
                    msg.contains("etimedout") ||
                    msg.contains("econnreset") ||
                    msg.contains("enotconn")
                ) {
                    return true
                }
                t = t.cause
            }
            return false
        }

        fun friendlyError(prefix: String, e: Throwable): String {
            val raw = e.message ?: e.javaClass.simpleName
            return if (isConnectionError(e)) {
                "$prefix failed: connection lost ($raw). Is the server still running?"
            } else {
                "$prefix failed: $raw"
            }
        }

        /** Strip path components and characters illegal on Windows filesystems. */
        fun sanitizeFilename(name: String): String {
            var base = name.substringAfterLast('/').substringAfterLast('\\')
            base = base.replace(Regex("""[<>:"/\\|?*\u0000-\u001f]"""), "_")
            base = base.trim().trimEnd('.')
            if (base.isBlank()) base = "file"
            // Avoid Windows reserved device names (CON, PRN, AUX, NUL, COM1, LPT1, …)
            val stem = base.substringBeforeLast('.', base)
            if (stem.matches(Regex("""(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"""))) {
                base = "_$base"
            }
            return base.take(200)
        }

        /**
         * Sanitize a folder-relative path, keeping `/` separators so the server
         * can recreate the directory tree. Drops `.` / `..` segments.
         */
        fun sanitizeRelativePath(path: String): String {
            val parts = path.replace('\\', '/')
                .split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { sanitizeFilename(it) }
            return parts.joinToString("/")
        }

        /**
         * Stream-hash a file (64KB buffer) without loading it into a single
         * [ByteArray]. Returns hex SHA-256 and byte length.
         */
        fun sha256AndLength(input: InputStream): Pair<String, Long> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BinaryProtocol.CHUNK_SIZE)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                digest.update(buf, 0, n)
                total += n
            }
            val hex = digest.digest().joinToString("") { b -> "%02x".format(b) }
            return hex to total
        }
    }

    private fun handshakeUnlocked(): Result<Unit> {
        return try {
            val out = output ?: return Result.failure(Exception("Not connected"))
            val inp = input ?: return Result.failure(Exception("Not connected"))

            out.writeByte(BinaryProtocol.CMD_HANDSHAKE.toInt() and 0xFF)
            writeString(out, config.username)
            writeString(out, config.deviceName)
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

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 0xFFFF) { "String too long for protocol: ${bytes.size}" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun requireStreams(): Pair<DataOutputStream, DataInputStream> {
        val out = output
        val inp = input
        if (out == null || inp == null || !isConnected()) {
            throw SocketException("Not connected to server")
        }
        return out to inp
    }

    /**
     * Run a protocol operation under the I/O mutex. On I/O failure the socket
     * is closed so callers can reconnect instead of writing into a dead pipe.
     */
    private suspend fun <T> protocolCall(label: String, block: (DataOutputStream, DataInputStream) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                try {
                    if (!isConnected()) {
                        connectUnlocked().getOrThrow()
                    }
                    val (out, inp) = requireStreams()
                    Result.success(block(out, inp))
                } catch (e: Exception) {
                    Log.e(TAG, "$label failed", e)
                    // Drop the socket — stream is unusable after most I/O errors.
                    closeUnlocked()
                    Result.failure(Exception(friendlyError(label, e), e))
                }
            }
        }

    suspend fun checkDuplicate(fileInfo: FileInfo): Result<DuplicateCheckResult> =
        protocolCall("Duplicate check (${fileInfo.filename})") { out, inp ->
            out.writeByte(BinaryProtocol.CMD_CHECK_DUPLICATE.toInt() and 0xFF)
            writeString(out, fileInfo.fileHash)
            writeString(out, fileInfo.filename)
            out.writeLong(fileInfo.fileSize)
            out.flush()

            when (val response = inp.readByte()) {
                BinaryProtocol.RESP_OK ->
                    DuplicateCheckResult(isDuplicate = false)
                BinaryProtocol.RESP_DUPLICATE ->
                    DuplicateCheckResult(isDuplicate = true, shouldUpdate = false)
                BinaryProtocol.RESP_NEED_UPDATE ->
                    DuplicateCheckResult(isDuplicate = true, shouldUpdate = true)
                BinaryProtocol.RESP_ERROR ->
                    throw IOException("Server error during duplicate check")
                else -> throw IOException("Invalid duplicate-check response: ${response.toUByte()}")
            }
        }

    /**
     * Stream a file to the server in 64KB protocol chunks without loading the
     * whole file into RAM (critical for DCIM / large videos).
     *
     * [fileInfo.fileSize] must match the number of bytes that will be read from
     * [input] (or the stream length if size was queried accurately).
     */
    suspend fun uploadFile(
        fileInfo: FileInfo,
        input: InputStream,
        onBytesSent: ((Long) -> Unit)? = null
    ): Result<Unit> =
        protocolCall("Upload (${fileInfo.filename})") { out, inp ->
            val total = fileInfo.fileSize.coerceAtLeast(0L)

            // UPLOAD_START
            out.writeByte(BinaryProtocol.CMD_UPLOAD_START.toInt() and 0xFF)
            writeString(out, fileInfo.filename)
            writeString(out, fileInfo.fileHash)
            writeString(out, fileInfo.fileType)
            writeString(out, fileInfo.directory)
            out.writeLong(total)
            out.flush()

            var response = inp.readByte()
            if (response != BinaryProtocol.RESP_OK) {
                throw IOException("Upload start rejected (code=${response.toUByte()})")
            }

            val buffer = ByteArray(BinaryProtocol.CHUNK_SIZE)
            var sent = 0L
            if (total == 0L) {
                // Empty file: no chunks.
            } else {
                while (sent < total) {
                    val remaining = (total - sent).coerceAtMost(BinaryProtocol.CHUNK_SIZE.toLong()).toInt()
                    val n = input.read(buffer, 0, remaining)
                    if (n < 0) {
                        throw IOException(
                            "Unexpected end of stream at $sent / $total bytes for ${fileInfo.filename}"
                        )
                    }
                    if (n == 0) continue

                    out.writeByte(BinaryProtocol.CMD_UPLOAD_CHUNK.toInt() and 0xFF)
                    out.writeInt(n)
                    out.write(buffer, 0, n)
                    out.flush()

                    response = inp.readByte()
                    if (response != BinaryProtocol.RESP_OK) {
                        throw IOException("Chunk rejected at offset $sent (code=${response.toUByte()})")
                    }

                    sent += n
                    onBytesSent?.invoke(sent)
                }
            }

            // UPLOAD_END
            out.writeByte(BinaryProtocol.CMD_UPLOAD_END.toInt() and 0xFF)
            writeString(out, fileInfo.filename)
            writeString(out, fileInfo.fileHash)
            writeString(out, fileInfo.fileType)
            writeString(out, fileInfo.directory)
            out.flush()

            response = inp.readByte()
            if (response != BinaryProtocol.RESP_OK) {
                throw IOException("Upload end rejected (code=${response.toUByte()})")
            }
            onBytesSent?.invoke(total)
        }

    /** Convenience: upload from a fully buffered array (small files / tests). */
    suspend fun uploadFile(
        fileInfo: FileInfo,
        fileData: ByteArray,
        onBytesSent: ((Long) -> Unit)? = null
    ): Result<Unit> {
        val info = if (fileInfo.fileSize == fileData.size.toLong()) {
            fileInfo
        } else {
            fileInfo.copy(fileSize = fileData.size.toLong())
        }
        return uploadFile(info, fileData.inputStream(), onBytesSent)
    }

    suspend fun confirmDelete(): Result<Unit> =
        protocolCall("Delete confirm") { out, inp ->
            out.writeByte(BinaryProtocol.CMD_DELETE_CONFIRM.toInt() and 0xFF)
            out.flush()
            val response = inp.readByte()
            if (response != BinaryProtocol.RESP_OK) {
                throw IOException("Delete confirm rejected (code=${response.toUByte()})")
            }
        }

    suspend fun getSyncHistory(): Result<List<String>> =
        protocolCall("Sync history") { out, inp ->
            out.writeByte(BinaryProtocol.CMD_SYNC_HISTORY.toInt() and 0xFF)
            out.flush()

            val response = inp.readByte()
            if (response != BinaryProtocol.RESP_OK) {
                throw IOException("History request rejected (code=${response.toUByte()})")
            }

            val count = inp.readInt()
            if (count < 0 || count > 10_000) {
                throw IOException("Invalid history count: $count")
            }

            val history = ArrayList<String>(count)
            repeat(count) {
                val len = inp.readUnsignedShort()
                if (len > 0) {
                    val bytes = ByteArray(len)
                    inp.readFully(bytes)
                    history.add(String(bytes, Charsets.UTF_8))
                }
            }
            history
        }

    fun close() {
        try {
            // Try to take the lock quickly; if busy, still force-close the socket.
            if (ioMutex.tryLock()) {
                try {
                    closeUnlocked()
                } finally {
                    ioMutex.unlock()
                }
            } else {
                closeUnlocked()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
            closeUnlocked()
        }
    }

    private fun closeUnlocked() {
        try {
            socket?.shutdownOutput()
        } catch (_: Exception) {
        }
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

    fun isConnected(): Boolean {
        val sock = socket ?: return false
        return sock.isConnected && !sock.isClosed && !sock.isInputShutdown
    }
}
