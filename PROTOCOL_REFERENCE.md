# Binary Protocol Reference & Code Examples

**INTERNAL USE ONLY // LIMITED DISTRIBUTION**

---

## Command & Response Constants

```go
// Commands (Client → Server)
const (
    CMD_HANDSHAKE       = 0x01
    CMD_CHECK_DUPLICATE = 0x02
    CMD_UPLOAD_START    = 0x03
    CMD_UPLOAD_CHUNK    = 0x04
    CMD_UPLOAD_END      = 0x05
    CMD_DELETE_CONFIRM  = 0x06
    CMD_SYNC_HISTORY    = 0x07
)

// Responses (Server → Client)
const (
    RESP_OK          = 0x00  // Success / file is new
    RESP_DUPLICATE   = 0x01  // File exists, identical
    RESP_ERROR       = 0x02  // Error occurred
    RESP_NEED_UPDATE = 0x03  // File exists, size differs (update)
)
```

---

## Handshake Example

### Wire Format (Hex)
```
Client → Server:
01              # CMD_HANDSHAKE
00 04           # username length = 4
6A 6F 68 6E     # "john" in ASCII
00 07           # device name length = 7
69 50 68 6F 6E 65 31  # "iPhone1" in ASCII

Server → Client:
00              # RESP_OK
```

### Go Code (Server)
```go
func (s *Server) handleHandshake(reader *bufio.Reader, writer *bufio.Writer) (int64, int64, error) {
    // Read username
    lenBuf := make([]byte, 2)
    reader.Read(lenBuf)
    usernameLen := binary.BigEndian.Uint16(lenBuf)
    usernameBuf := make([]byte, usernameLen)
    reader.Read(usernameBuf)
    username := string(usernameBuf)

    // Read device name
    reader.Read(lenBuf)
    deviceLen := binary.BigEndian.Uint16(lenBuf)
    deviceBuf := make([]byte, deviceLen)
    reader.Read(deviceBuf)
    deviceName := string(deviceBuf)

    // Get or create user/device
    userID, _ := s.getOrCreateUser(username)
    deviceID, _ := s.getOrCreateDevice(userID, deviceName)

    // Send OK
    writer.WriteByte(RESP_OK)
    writer.Flush()

    return userID, deviceID, nil
}
```

### Kotlin Code (Android Client)
```kotlin
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
        if (response == BinaryProtocol.RESP_OK.toInt()) {
            Result.success(Unit)
        } else {
            Result.failure(Exception("Handshake failed"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
```

---

## Check Duplicate Example

### Scenario
- File: `vacation.jpg`
- Hash: `a1b2c3d4e5f6...` (64-char SHA256 hex)
- Size: 2,097,152 bytes (2 MB)

### Wire Format
```
Client → Server:
02                      # CMD_CHECK_DUPLICATE
00 40                   # hash length = 64
a1b2c3d4e5f6...        # SHA256 hex (64 bytes)
00 0C                   # filename length = 12
76 61 63 61 74 69 6F 6E 2E 6A 70 67  # "vacation.jpg"
00 00 00 00 00 20 00 00 # file size = 2,097,152 (big-endian uint64)

Server → Client (File New):
00                      # RESP_OK

Server → Client (File Exists):
01                      # RESP_DUPLICATE

Server → Client (File Needs Update):
03                      # RESP_NEED_UPDATE
```

### Go Code
```go
func (s *Server) handleCheckDuplicate(reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
    // Read file hash
    lenBuf := make([]byte, 2)
    reader.Read(lenBuf)
    hashLen := binary.BigEndian.Uint16(lenBuf)
    hashBuf := make([]byte, hashLen)
    reader.Read(hashBuf)
    fileHash := string(hashBuf)

    // Read filename
    reader.Read(lenBuf)
    filenameLen := binary.BigEndian.Uint16(lenBuf)
    filenameBuf := make([]byte, filenameLen)
    reader.Read(filenameBuf)
    filename := string(filenameBuf)

    // Read file size
    sizeBuf := make([]byte, 8)
    reader.Read(sizeBuf)
    fileSize := int64(binary.BigEndian.Uint64(sizeBuf))

    // Check database
    isDuplicate, _, _ := s.checkDuplicate(userID, deviceID, fileHash, filename, fileSize)
    
    if isDuplicate {
        writer.WriteByte(RESP_DUPLICATE)
    } else {
        writer.WriteByte(RESP_OK)
    }
    writer.Flush()
}

// Database check
func (s *Server) checkDuplicate(userID, deviceID int64, fileHash, filename string, fileSize int64) (bool, int64, error) {
    var existingID int64
    var existingSize int64

    err := s.db.QueryRow(`
        SELECT id, file_size FROM sync_files 
        WHERE user_id = ? AND file_hash = ? AND filename = ?
    `, userID, fileHash, filename).Scan(&existingID, &existingSize)

    if err == sql.ErrNoRows {
        return false, 0, nil  // Not a duplicate
    }

    // Duplicate found; check if update needed
    isDuplicate := (fileSize == existingSize)
    return isDuplicate, existingID, nil
}
```

### Kotlin Code
```kotlin
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

            // Parse response
            val response = input?.readByte()?.toInt()
            val result = when (response) {
                RESP_OK -> DuplicateCheckResult(isDuplicate = false)
                RESP_DUPLICATE -> DuplicateCheckResult(isDuplicate = true)
                RESP_NEED_UPDATE -> DuplicateCheckResult(isDuplicate = true, shouldUpdate = true)
                else -> throw Exception("Invalid response")
            }
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

---

## File Upload (3-Step) Example

### Scenario
- File: `IMG_001.jpg`
- Size: 1,048,576 bytes (1 MB)
- Hash: `f1f2f3f4f5f6...`
- Chunks: 2 (65536 + remainder)

### Step 1: Upload Start

**Wire Format**
```
Client → Server:
03                      # CMD_UPLOAD_START
00 0D                   # filename length = 13
49 4D 47 5F 30 30 31 2E 6A 70 67  # "IMG_001.jpg"
00 40                   # hash length = 64
f1f2f3f4f5f6...        # SHA256 hex
00 06                   # file type length = 6
70 68 6F 74 6F 73      # "photos"
00 0C                   # directory length = 12
2F 44 43 49 4D 2F 32 30 32 34 31 32  # "/DCIM/202412"
00 00 00 00 00 10 00 00 # file size = 1,048,576

Server → Client:
00                      # RESP_OK
```

**Go Code**
```go
func (s *Server) handleUploadStart(reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
    lenBuf := make([]byte, 2)
    
    // Read filename
    reader.Read(lenBuf)
    filenameLen := binary.BigEndian.Uint16(lenBuf)
    filenameBuf := make([]byte, filenameLen)
    reader.Read(filenameBuf)
    filename := string(filenameBuf)

    // Read hash, type, directory, size (similar pattern)
    ...

    // Send OK
    writer.WriteByte(RESP_OK)
    writer.Flush()
}
```

**Kotlin Code**
```kotlin
suspend fun uploadFile(fileInfo: FileInfo, fileData: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
        try {
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

            var response = input?.readByte()
            if (response?.toInt() != RESP_OK) {
                throw Exception("Upload start failed")
            }
            
            // Proceed to chunks...
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

### Step 2: Upload Chunks (Repeating)

**Wire Format (First 65536-byte chunk)**
```
Client → Server:
04                      # CMD_UPLOAD_CHUNK
00 01 00 00             # chunk size = 65536 (big-endian uint32)
[65536 bytes of file data]

Server → Client:
00                      # RESP_OK

Client → Server (Second/remainder chunk):
04                      # CMD_UPLOAD_CHUNK
00 00 F0 00             # chunk size = 61440 (1,048,576 - 65,536)
[61440 bytes of file data]

Server → Client:
00                      # RESP_OK
```

**Kotlin Code**
```kotlin
// Send chunks
var offset = 0
while (offset < fileData.size) {
    val chunkSize = min(BinaryProtocol.CHUNK_SIZE, fileData.size - offset)
    output?.writeByte(BinaryProtocol.CMD_UPLOAD_CHUNK.toInt())
    output?.writeInt(chunkSize)
    output?.write(fileData, offset, chunkSize)
    output?.flush()

    val response = input?.readByte()
    if (response?.toInt() != RESP_OK) {
        throw Exception("Chunk upload failed at offset $offset")
    }

    offset += chunkSize
}
```

### Step 3: Upload End

**Wire Format**
```
Client → Server:
05                      # CMD_UPLOAD_END
00 0D                   # filename length
49 4D 47 5F 30 30 31 2E 6A 70 67  # "IMG_001.jpg" (repeat)
00 40                   # hash length
f1f2f3f4f5f6...        # hash (repeat)
00 06                   # type length
70 68 6F 74 6F 73      # "photos" (repeat)
00 0C                   # directory length
2F 44 43 49 4D 2F 32 30 32 34 31 32  # directory (repeat)

Server → Client:
00                      # RESP_OK
```

**Go Code (Handler)**
```go
func (s *Server) handleUploadEnd(reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
    // Re-read metadata (for verification)
    lenBuf := make([]byte, 2)
    reader.Read(lenBuf)
    filenameLen := binary.BigEndian.Uint16(lenBuf)
    filenameBuf := make([]byte, filenameLen)
    reader.Read(filenameBuf)
    filename := string(filenameBuf)
    // ... read hash, type, directory

    // Record in database (UPSERT)
    fileID, err := s.recordSyncFile(userID, deviceID, filename, fileHash, directory, "", fileSize)
    if err != nil {
        writer.WriteByte(RESP_ERROR)
        writer.Flush()
        s.recordHistory(userID, deviceID, 0, "upload", "failed", err.Error())
        return
    }

    // Record success in history
    s.recordHistory(userID, deviceID, fileID, "upload", "success", "File uploaded successfully")

    writer.WriteByte(RESP_OK)
    writer.Flush()
}

// Database record
func (s *Server) recordSyncFile(userID, deviceID int64, filename, fileHash, directory, filePath string, fileSize int64) (int64, error) {
    result, err := s.db.Exec(`
        INSERT OR REPLACE INTO sync_files (user_id, device_id, filename, file_hash, file_size, directory, file_path, status, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'synced', CURRENT_TIMESTAMP)
    `, userID, deviceID, filename, fileHash, fileSize, directory, filePath)

    if err != nil {
        return 0, err
    }
    return result.LastInsertId()
}
```

---

## Sync History Retrieval Example

**Wire Format**
```
Client → Server:
07                      # CMD_SYNC_HISTORY

Server → Client:
00                      # RESP_OK
00 00 00 03             # count = 3 (big-endian uint32)

# Entry 1
00 5E                   # length = 94 bytes
32 30 32 34 2D 30 31 2D 31 35 54 31 30 3A 30 30  # "2024-01-15T10:00"
3A 30 30 5A 7C 75 70 6C 6F 61 64 7C 73 75 63 63  # ":00Z|upload|succ"
65 73 73 7C 46 69 6C 65 20 75 70 6C 6F 61 64 65  # "ess|File uploade"
64 20 73 75 63 63 65 73 73 66 75 6C 6C 79         # "d successfully"

# Entry 2
00 48                   # length = 72 bytes
32 30 32 34 2D 30 31 2D 31 35 54 31 30 3A 31 35  # "2024-01-15T10:15"
...                    # |upload|success|IMG_002.jpg

# Entry 3
00 52                   # length = 82 bytes
32 30 32 34 2D 30 31 2D 31 35 54 31 30 3A 33 30  # "2024-01-15T10:30"
...                    # |upload|duplicate|File exists
```

---

## SHA256 Hashing (Matching on Both Sides)

### Go
```go
import (
    "crypto/sha256"
    "encoding/hex"
)

func calculateFileHash(data []byte) string {
    hash := sha256.Sum256(data)
    return hex.EncodeToString(hash[:])
}

// Usage
fileHash := calculateFileHash(fileData)
// Result: "a1b2c3d4e5f6..." (64 hex chars)
```

### Kotlin
```kotlin
import java.security.MessageDigest

fun calculateFileHash(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data).fold("") { str, it ->
        str + "%02x".format(it)
    }
}

// Usage
val fileHash = calculateFileHash(fileData)
// Result: "a1b2c3d4e5f6..." (64 hex chars)
```

### Verification
```bash
# macOS/Linux
sha256sum filename

# Windows PowerShell
(Get-FileHash filename -Algorithm SHA256).Hash

# Result: A1B2C3D4E5F6... (matches both implementations)
```

---

## Error Handling Pattern

### Go Server (All handlers)
```go
func handler(reader *bufio.Reader, writer *bufio.Writer) {
    defer func() {
        if r := recover(); r != nil {
            log.Printf("Handler panic: %v", r)
            writer.WriteByte(RESP_ERROR)
            writer.Flush()
        }
    }()

    // ... command logic ...

    if err != nil {
        log.Printf("Error: %v", err)
        writer.WriteByte(RESP_ERROR)
        writer.Flush()
        return
    }

    writer.WriteByte(RESP_OK)
    writer.Flush()
}
```

### Kotlin Client (Retry Logic)
```kotlin
suspend fun <T> withRetry(maxRetries: Int = 3, block: suspend () -> Result<T>): Result<T> {
    var lastException: Exception? = null
    repeat(maxRetries) {
        val result = block()
        if (result.isSuccess) return result
        lastException = result.exceptionOrNull()
        delay(1000 * (it + 1))  // Exponential backoff
    }
    return Result.failure(lastException ?: Exception("Max retries exceeded"))
}

// Usage
val result = withRetry {
    syncClient.uploadFile(fileInfo, fileData)
}
```

---

## Performance Optimization Tips

### Server (Go)
1. **Increase concurrency**: goroutine pool for file I/O
2. **Batch inserts**: Record multiple files in one DB transaction
3. **Compression**: Add DEFLATE before sending (optional protocol extension)
4. **Caching**: Keep recent file hashes in memory

### Client (Kotlin)
1. **Max 10 concurrent**: Semaphore prevents OOM
2. **Chunk size**: 65KB is optimal for WiFi; adjust for cellular
3. **Progress updates**: Throttle UI updates to 500ms intervals
4. **Memory mapping**: Use `ByteArray` not `String` for file data

---

## Testing Checklist

- [ ] Connect to server successfully
- [ ] Check duplicate with new file (RESP_OK)
- [ ] Upload small file (< 1 MB)
- [ ] Check file exists in storage directory
- [ ] Verify database record
- [ ] Re-sync same file (RESP_DUPLICATE)
- [ ] Upload large file (> 100 MB)
- [ ] Monitor chunk progress
- [ ] Test network disconnection (midway through upload)
- [ ] Test 10 concurrent uploads
- [ ] Verify sync history shows all uploads

---

**Document Version**: 1.0  
**Last Updated**: 2026-07-18  
**Status**: READY FOR REFERENCE
