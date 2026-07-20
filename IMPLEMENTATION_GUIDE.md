# File Sync Server - Complete Implementation Guide

**INTERNAL USE ONLY // LIMITED DISTRIBUTION**

---

## Overview

This document covers the complete implementation of a cross-platform file synchronization system:
- **Go Server**: Windows console app with TUI, SQLite database, binary protocol over TCP
- **Android App**: Kotlin/Jetpack Compose with concurrent parallel uploads (max 10 simultaneous)
- **Binary Protocol**: Custom TCP protocol for efficient file transfer

---

## Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                    Windows Machine                           │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │           Go File Sync Server (TUI)                    │  │
│  │  - TCP Binary Protocol (Port > 10100)                 │  │
│  │  - SQLite Database (users/devices/files/history)      │  │
│  │  - Storage: users/{id}/device{id}/{type}/{filename}   │  │
│  │  - Concurrent upload queue (handles multiple clients) │  │
│  └───────────────────────────────────────────────────────┘  │
│                            ▲                                  │
│                            │ Binary Protocol (TCP)            │
│                            │ Port 10101 (default)             │
│                            ▼                                  │
│                   (Network: LAN/WiFi)                         │
│                            ▲                                  │
│                            │                                  │
└────────────────────────────┼──────────────────────────────────┘
                             │
                    ┌────────▼────────┐
                    │   Android Phone  │
                    │                  │
                    │  Jetpack Compose │
                    │   Kotlin App     │
                    │                  │
                    │  - Directory     │
                    │    Picker        │
                    │  - Photo/Docs    │
                    │    Selector      │
                    │  - Parallel      │
                    │    Uploads       │
                    │    (max 10)      │
                    │  - Sync History  │
                    │                  │
                    └──────────────────┘
```

---

## GO SERVER SETUP

### Prerequisites
- Go 1.26+ installed
- Windows 10/11
- SQLite3 (included in `github.com/mattn/go-sqlite3`)

### Project Structure
```
server/
├── main.go              # Server, TUI, protocol handler
├── go.mod              # Dependencies
├── go.sum              # Checksums (generated)
└── syncserver_data/    # (Created at runtime)
    ├── sync.db         # SQLite database
    └── storage/        # File storage tree
        └── users/
            └── {user_id}/
                └── device{device_id}/
                    ├── photos/
                    ├── documents/
                    └── videos/
```

### Build & Run

**Step 1: Initialize Go module**
```bash
cd server
go mod init lansync-server
go get github.com/mattn/go-sqlite3
go mod tidy
```

**Step 2: Build**
```bash
# Debug run (creates ./syncserver_data/)
go run main.go

# Custom port
go run main.go -port 10200

# Custom data directory
go run main.go -datadir "C:\SyncServerData"

# Release build
go build -o lansync-server.exe
```

**Step 3: First Run**
```
File Sync Server listening on port 10101
Syncing Files - Press 'h' for help
```

### TUI Commands
```
h - Show recent sync history (last 10 entries)
s - Server statistics (users, devices, files)
q - Quit gracefully
```

---

## DATABASE SCHEMA

### Users Table
```sql
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### Devices Table
```sql
CREATE TABLE devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    device_name TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id),
    UNIQUE(user_id, device_name)
);
```

### Sync Files Table
```sql
CREATE TABLE sync_files (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    device_id INTEGER NOT NULL,
    filename TEXT NOT NULL,
    file_hash TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    directory TEXT,
    file_path TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status TEXT DEFAULT 'synced',
    FOREIGN KEY(user_id) REFERENCES users(id),
    FOREIGN KEY(device_id) REFERENCES devices(id),
    UNIQUE(user_id, device_id, file_hash, filename, file_size)
);
```

### Sync History Table
```sql
CREATE TABLE sync_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    device_id INTEGER NOT NULL,
    file_id INTEGER,
    action TEXT NOT NULL,           -- "upload", "skip", "conflict"
    status TEXT NOT NULL,           -- "success", "failed"
    message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(user_id) REFERENCES users(id),
    FOREIGN KEY(device_id) REFERENCES devices(id),
    FOREIGN KEY(file_id) REFERENCES sync_files(id)
);
```

---

## BINARY PROTOCOL SPECIFICATION

### Overview
- **Transport**: TCP/IP over LAN
- **Encoding**: Big-endian binary
- **Security**: None (trusted LAN assumption)
- **Concurrency**: Multiple clients supported

### Message Format

#### String Length-Prefixed
```
[2 bytes: big-endian uint16 length][N bytes: UTF-8 data]
```

#### File Size
```
[8 bytes: big-endian uint64]
```

---

### Protocol Flow

#### 1. Handshake (Client → Server)
```
[1 byte: CMD_HANDSHAKE 0x01]
[2 bytes: username length]
[N bytes: username UTF-8]
[2 bytes: device name length]
[N bytes: device name UTF-8]

← Response: [1 byte: RESP_OK 0x00] or RESP_ERROR 0x02
```

**Example**: User "john" on device "iPhone"
```
01 00 04 6A 6F 68 6E 00 06 69 50 68 6F 6E 65
^  ^     ^                 ^
|  |     +- "john"         +- "iPhone"
|  +- length 4
+- CMD_HANDSHAKE
```

#### 2. Check Duplicate
```
[1 byte: CMD_CHECK_DUPLICATE 0x02]
[2 bytes: file hash length]
[N bytes: SHA256 hex string (64 bytes typical)]
[2 bytes: filename length]
[N bytes: filename UTF-8]
[8 bytes: file size]

← Response:
  RESP_OK 0x00          - File is new, proceed with upload
  RESP_DUPLICATE 0x01   - File exists, identical
  RESP_NEED_UPDATE 0x03 - File exists, size/date differs (smart merge)
```

#### 3. Upload Start
```
[1 byte: CMD_UPLOAD_START 0x03]
[2 bytes: filename length]
[N bytes: filename]
[2 bytes: file hash length]
[N bytes: file hash (SHA256 hex)]
[2 bytes: file type length]
[N bytes: file type ("photos", "documents", "videos")]
[2 bytes: directory length]
[N bytes: directory path]
[8 bytes: total file size]

← Response: RESP_OK 0x00 or RESP_ERROR 0x02
```

#### 4. Upload Chunk (streaming, repeating)
```
[1 byte: CMD_UPLOAD_CHUNK 0x04]
[4 bytes: chunk size]
[N bytes: file data (max 65536 bytes)]

← Response: RESP_OK 0x00 or RESP_ERROR 0x02
(Server sends OK after each successful chunk)
```

#### 5. Upload End
```
[1 byte: CMD_UPLOAD_END 0x05]
[2 bytes: filename length]
[N bytes: filename]
[2 bytes: file hash length]
[N bytes: file hash]
[2 bytes: file type length]
[N bytes: file type]
[2 bytes: directory length]
[N bytes: directory]

← Response: RESP_OK 0x00 (file recorded) or RESP_ERROR 0x02
```

#### 6. Delete Confirmation
```
[1 byte: CMD_DELETE_CONFIRM 0x06]

← Response: RESP_OK 0x00
```

#### 7. Sync History
```
[1 byte: CMD_SYNC_HISTORY 0x07]

← Response: RESP_OK 0x00
← [4 bytes: count]
← For each entry:
   [2 bytes: message length]
   [N bytes: "TIMESTAMP|ACTION|STATUS|MESSAGE"]
```

---

## ANDROID APP SETUP

### Prerequisites
- Android SDK 26+ (API level 26 = Android 8.0)
- Android Studio (2023.1+)
- Kotlin 1.9+
- Jetpack Compose

### Project Structure
```
android/
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   └── main/
│   │       ├── AndroidManifest.xml
│   │       └── kotlin/
│   │           └── com/lansync/
│   │               ├── MainActivity.kt
│   │               ├── network/
│   │               │   └── SyncClient.kt
│   │               ├── manager/
│   │               │   └── UploadManager.kt
│   │               └── ui/
│   │                   └── SyncScreen.kt
│   └── proguard-rules.pro
├── build.gradle.kts (project-level)
├── gradle.properties
└── settings.gradle.kts
```

### Build & Run

**Step 1: Open in Android Studio**
```bash
# File → Open → select android/
```

**Step 2: Sync Gradle**
- Wait for indexing to complete
- Resolve any SDK version mismatches

**Step 3: Update AndroidManifest.xml**
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:theme="@style/Theme.FileSync">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.FileSync">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 4: Create MainActivity.kt**
```kotlin
package com.lansync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lansync.ui.SyncMainScreen
import com.lansync.ui.SyncViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel = viewModel<SyncViewModel>(
                factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return SyncViewModel(this@MainActivity) as T
                    }
                }
            )
            SyncMainScreen(viewModel)
        }
    }
}
```

**Step 5: Run**
- Select device/emulator
- Click "Run" (Shift+F10)

---

## SYNC WORKFLOW

### User Interaction Flow

```
1. Android App Starts
   ↓
2. User enters server address (remembered in preferences)
   ↓
3. App connects to server via binary protocol (handshake)
   ↓
4. User selects files/directories to sync
   - Photos (suggested)
   - Documents (suggested)
   - Custom folders
   ↓
5. User taps "Start Sync"
   ↓
6. App calculates SHA256 hash for each file
   ↓
7. For each file (up to 10 parallel):
   a. Check duplicate on server
   b. If duplicate & identical → Ask user
      - Skip file
      - Override this file
      - Apply to all duplicates
   c. If new or approved override → Upload in chunks
   d. Track progress (filename + progress bar)
   e. Record in history
   ↓
8. All uploads complete/failed
   ↓
9. Show summary (X completed, Y failed, Z skipped)
   ↓
10. Ask "Delete files from phone?"
    - Yes, delete all synced files
    - No, keep all files
    ↓
11. Show sync history (last 100 entries)
```

### Smart Merge Detection

When a file exists:
1. Compare file hash (SHA256) + filename + file size
2. If all match → **Duplicate (skip or override)**
3. If hash differs but filename matches → **Update (smart merge)**
   - Compare dates/sizes
   - Server becomes latest version

---

## RESUMABLE SYNC

If a sync is interrupted:
1. **State tracking**: App records which files completed
2. **Next sync**: Check server; only upload files that changed/don't exist
3. **Progress storage**: Local SQLite (or SharedPreferences)

---

## CONCURRENT UPLOAD IMPLEMENTATION

### Max 10 Simultaneous Uploads

**Algorithm**:
```kotlin
// Pseudo-code
val semaphore = Semaphore(10)
val uploadJobs = files.map { file ->
    launch {
        semaphore.acquire()
        try {
            uploadFile(file)
        } finally {
            semaphore.release()
        }
    }
}
joinAll(uploadJobs)
```

**Benefits**:
- Network throughput optimization
- Faster overall sync time
- No server overload (controlled concurrency)

**Display**:
- Individual progress bar for each file
- % complete per file
- Status badge (uploading/complete/failed)

---

## FILE STORAGE ON SERVER

### Structure
```
C:\SyncServerData\storage\
└── users\
    └── 1\ (user_id)
        └── device1\ (device_id)
            ├── photos\
            │   ├── IMG_001.jpg
            │   ├── IMG_002.jpg
            │   └── photo_20240101.png
            ├── documents\
            │   ├── resume.pdf
            │   ├── invoice_2024.xlsx
            │   └── notes.txt
            └── videos\
                └── vacation.mp4
```

### Metadata Stored in Database
```sql
-- Example query
SELECT 
    sf.filename,
    sf.file_hash,
    sf.file_size,
    sf.file_path,
    sf.directory,
    sh.action,
    sh.status,
    sh.created_at
FROM sync_files sf
LEFT JOIN sync_history sh ON sf.id = sh.file_id
WHERE sf.user_id = 1 AND sf.device_id = 1
ORDER BY sf.created_at DESC;
```

---

## SETTINGS & PERSISTENCE

### Android (SharedPreferences)
```kotlin
val prefs = getSharedPreferences("sync_settings", MODE_PRIVATE)

// Save server config
prefs.edit().apply {
    putString("server_host", "192.168.1.100")
    putInt("server_port", 10101)
    putString("username", "user1")
    putString("device_name", "android-phone")
}.apply()

// Load
val host = prefs.getString("server_host", "")
```

### Windows Server
```go
// Config file (optional, for future expansion)
// Currently: command-line flags
// lansync-server.exe -port 10200 -datadir C:\CustomData
```

---

## TROUBLESHOOTING

### "Connection refused"
- Verify server is running: `go run main.go`
- Check port: `netstat -an | find "10101"`
- Firewall: Allow port on Windows Defender Firewall

### "Duplicate check failed"
- Verify SHA256 calculation matches
- Check file encoding (UTF-8)
- Test with small files first

### "Upload chunk timeout"
- Network congestion; increase CHUNK_SIZE if robust
- Or retry logic: exponential backoff
- LAN typically: 1-10 Mbps sustained

### Database locked
- One writer at a time (Go handles with mutex)
- If corruption: delete `sync.db`, recreate on server restart

---

## SECURITY CONSIDERATIONS

### Current (Trusted LAN)
- No encryption
- No authentication (username is identifier, not password)
- Binary protocol is simple, not obfuscated

### Future Hardening
1. **TLS**: Wrap TCP in TLS for internet access
2. **Auth**: Add password hash verification
3. **Rate limiting**: Per-IP/user upload limits
4. **Audit log**: Log all uploads to syslog

---

## PERFORMANCE NOTES

### Go Server
- SQLite: ~1000 ops/sec typical (one thread)
- Concurrent clients: Handled by goroutines
- File I/O: Bottlenecked by disk speed (5400 RPM HDD ≈ 50-100 Mbps, SSD ≈ 500+ Mbps)

### Android App
- 10 concurrent uploads: 64KB chunks × 10 ≈ 640KB/sec network usage
- Memory: ~50-100MB per concurrent upload buffer

### Network (WiFi 5GHz LAN)
- Typical: 50-200 Mbps sustained
- 1GB file: 5-20 seconds

---

## BUILD & DEPLOYMENT

### Go Server Executable
```bash
# Static binary (no runtime dependencies)
go build -o lansync-server.exe

# Create shortcut on Windows Desktop for easy launch
```

### Android APK
```bash
# In Android Studio
Build → Build Bundle / APK → Create APK

# Output: app/release/app-release.apk
# Install: adb install app/release/app-release.apk
```

---

## QUICK START CHECKLIST

- [ ] Go server: `go run main.go` → listening on port 10101
- [ ] Android app: Set server host to PC's local IP (e.g., 192.168.1.100)
- [ ] Test handshake: Connect → should show "Ready"
- [ ] Select test file (small JPG)
- [ ] Sync → monitor progress bar
- [ ] Check server storage: `C:\SyncServerData\storage\users\1\device1\photos\`
- [ ] Verify database: Open `sync.db` in SQLite viewer
- [ ] Test duplicate detection: Re-sync same file
- [ ] Test delete confirmation: Choose delete after sync

---

## NEXT PHASES (Future)

1. **Resumable sync**: Save progress, restart where left off
2. **Directory monitoring**: Auto-detect new files in watched dirs
3. **Compression**: DEFLATE before sending
4. **P2P sync**: Device-to-device without server (WebRTC)
5. **Cloud backup**: S3/Azure blob integration
6. **Multi-user dashboard**: Web interface on server

---

**Document Version**: 1.0  
**Last Updated**: 2026-07-18  
**Status**: READY FOR IMPLEMENTATION
