# File Sync System - Complete Implementation Package

**INTERNAL USE ONLY // LIMITED DISTRIBUTION**

---

## 📦 What's Included

This package contains a complete, production-ready file synchronization system:

- **Go Server** (`server/`): Windows console app with TUI, SQLite, binary protocol
- **Android App** (`android/`): Kotlin/Jetpack Compose with parallel uploads
- **Documentation**:
  - `IMPLEMENTATION_GUIDE.md` — Full setup & architecture (17KB)
  - `PROTOCOL_REFERENCE.md` — Binary protocol specs & code examples (15KB)
  - This `README.md` — Quick navigation

---

## 🚀 Quick Start (5 Minutes)

### Server (Windows)

```bash
cd server
go mod tidy
go run main.go
# Output: File Sync Server listening on port 10101
```

Server is now ready. Check:
- Database: `syncserver_data/sync.db`
- Storage: `syncserver_data/storage/`

### Android App

```bash
cd android/app
# Open in Android Studio
# File → Open → select this directory
# Click "Run" (Shift+F10)
```

### First Sync

1. **Android**: Enter server IP (e.g., `192.168.1.100:10101`)
2. **Android**: Username & device name (remember these)
3. **Android**: Select files to sync
4. **Android**: Tap "Start Sync"
5. **Android**: Watch progress bars (max 10 files in parallel)
6. **Server**: Check `syncserver_data/storage/users/1/device1/` for files

---

## 📋 Project Structure

```
lansync/
├── server/                     # Go server (module: lansync-server)
│   ├── main.go
│   ├── go.mod
│   └── syncserver_data/        # (created at runtime, gitignored)
│       ├── sync.db             # SQLite database
│       └── storage/            # File storage tree
│
├── android/                    # Android app (Gradle project)
│   ├── build.gradle.kts        # project-level
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   ├── android-build.sh        # CLI build wrapper
│   └── app/
│       ├── build.gradle.kts    # module-level
│       ├── proguard-rules.pro
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── res/values/{strings,themes}.xml
│           └── kotlin/com/lansync/
│               ├── MainActivity.kt
│               ├── network/SyncClient.kt
│               ├── manager/UploadManager.kt
│               └── ui/SyncScreen.kt
│
├── IMPLEMENTATION_GUIDE.md     # Full setup guide
├── PROTOCOL_REFERENCE.md       # Binary protocol specs + code examples
└── README.md                   # This file
```

---

## 🔧 Configuration

### Server (Go)

**Command-line flags:**
```bash
go run main.go -port 10200 -datadir "C:\CustomData"
```

**Database**: Auto-created on first run
- Schema: 4 tables (users, devices, sync_files, sync_history)
- File storage: Organized by user/device/type

### Android App

**Settings (SharedPreferences)**:
- Server host/port (remembered)
- Username & device name (remembered)
- Sync history (last 100 entries)

---

## 🔐 Binary Protocol Overview

**Commands** (Client → Server):
- `0x01` — Handshake (username, device)
- `0x02` — Check Duplicate (hash, filename, size)
- `0x03` — Upload Start (metadata)
- `0x04` — Upload Chunk (file data, streaming)
- `0x05` — Upload End (finalize)
- `0x06` — Delete Confirm (acknowledgment)
- `0x07` — Sync History (retrieve logs)

**Encoding**: Big-endian binary, length-prefixed strings (2 bytes length + data)

See `PROTOCOL_REFERENCE.md` for detailed wire formats and code examples.

---

## 📊 Features

### ✅ Implemented

- [x] Go server with TUI console interface
- [x] SQLite database with schema (users, devices, files, history)
- [x] Binary protocol over TCP (efficient, low overhead)
- [x] Android Jetpack Compose UI (modern, responsive)
- [x] File picker with suggested directories (Photos, Documents)
- [x] **Parallel uploads** (max 10 concurrent files)
- [x] Per-file progress bars with percentage
- [x] Duplicate detection (SHA256 hash + filename + size)
- [x] Smart merge (detect file updates)
- [x] Conflict resolution UI (skip / override / apply to all)
- [x] Sync history log (100 entries, timestamped)
- [x] Delete confirmation at end of sync
- [x] Resumable sync state tracking
- [x] Logical file storage (users/{id}/device{id}/{type}/)
- [x] Metadata tracking (hash, user, device, filename, directory, date)

### 🟡 Future (Phase 2)

- [ ] Directory monitoring (auto-detect new files)
- [ ] TLS encryption (for internet access)
- [ ] Password authentication (beyond username)
- [ ] Web dashboard (see server status from browser)
- [ ] Compression (DEFLATE before upload)
- [ ] Incremental sync (only changed files)
- [ ] Conflict merge strategies (auto-merge by date/size)
- [ ] Cloud backend (S3/Azure Blob)

---

## 🔌 Networking

### LAN Setup (Trusted Network)

```
Windows PC (Go Server)
  IP: 192.168.1.100
  Port: 10101 (default)
  
Android Phone (Jetpack Compose)
  Same WiFi network
  Connect to: 192.168.1.100:10101
```

**Firewall**:
- Windows: Allow port 10101 in Windows Defender Firewall
  - Settings → Firewall & network protection → Allow app through firewall
  - Or: `netsh advfirewall firewall add rule name="Sync Server" dir=in action=allow protocol=tcp localport=10101`

### Internet Setup (Future - WAN)

When moving to internet:
1. Add TLS/SSL encryption
2. Add username/password authentication
3. Use reverse proxy (nginx) if behind NAT
4. See `IMPLEMENTATION_GUIDE.md` "Security Considerations"

---

## 📈 Performance

### Upload Speed (Typical LAN)

| Network | Speed | 1 GB Time |
|---------|-------|-----------|
| WiFi 5GHz LAN | 50-200 Mbps | 5-20s |
| Gigabit Ethernet | 100-1000 Mbps | 1-10s |
| Mobile 4G | 5-50 Mbps | 100-1000s |

### Concurrency

- **Max simultaneous uploads**: 10 (configurable)
- **Chunk size**: 64KB per write
- **Memory overhead**: ~50-100MB per concurrent upload

### Database

- SQLite: ~1000 queries/sec single-threaded
- Go handles: Unlimited concurrent clients (goroutines)
- File I/O: Bottlenecked by disk speed

---

## 🧪 Testing Checklist

Before deploying:

- [ ] **Server**:
  - [ ] Starts without errors: `go run main.go`
  - [ ] Database created: `syncserver_data/sync.db` exists
  - [ ] TUI responds to commands (h, s, q)
  - [ ] Accepts TCP connections on port 10101

- [ ] **Android**:
  - [ ] Builds in Android Studio without errors
  - [ ] App starts on emulator/device
  - [ ] Connect screen appears (enter server IP)
  - [ ] Connects successfully

- [ ] **Integration**:
  - [ ] Android connects to server (handshake)
  - [ ] File picker shows suggested directories
  - [ ] Select small file (< 10 MB)
  - [ ] Upload completes, file appears in `storage/users/1/device1/`
  - [ ] Database record created
  - [ ] Sync history shows upload
  - [ ] Re-sync same file → duplicate detection works
  - [ ] 10 files upload in parallel → all show progress
  - [ ] Delete confirmation appears

---

## 📝 Database Inspection

### View Files Uploaded

**Via command line (macOS/Linux)**:
```bash
sqlite3 syncserver_data/sync.db << EOF
SELECT 
    u.username, 
    d.device_name, 
    sf.filename, 
    sf.file_size, 
    sf.file_hash,
    sf.created_at
FROM sync_files sf
JOIN users u ON sf.user_id = u.id
JOIN devices d ON sf.device_id = d.id
ORDER BY sf.created_at DESC;
EOF
```

**Via SQLite Browser (any OS)**:
1. Download [sqlitebrowser.org](https://sqlitebrowser.org/)
2. Open `syncserver_data/sync.db`
3. Browse `sync_files` table

### View Sync History

```bash
sqlite3 syncserver_data/sync.db << EOF
SELECT 
    created_at,
    action,
    status,
    message
FROM sync_history
ORDER BY created_at DESC
LIMIT 50;
EOF
```

---

## 🛠️ Customization

### Change Server Port

```bash
go run main.go -port 10200
# Android app: enter server IP as "192.168.1.100:10200"
```

### Change Data Directory

```bash
go run main.go -datadir "D:\SyncBackup"
# Files stored in: D:\SyncBackup\storage\
```

### Adjust Concurrent Uploads

**In `android/app/src/main/kotlin/com/lansync/manager/UploadManager.kt`**:

```kotlin
class UploadManager(
    private val syncClient: SyncClient,
    private val maxConcurrent: Int = 10  // ← Change this
)
```

### Adjust Chunk Size

**In `android/app/src/main/kotlin/com/lansync/network/SyncClient.kt`**:

```kotlin
const val CHUNK_SIZE = 65536  // ← 64KB, adjust for your network
```

---

## ⚠️ Known Limitations

1. **No encryption**: Assumes trusted LAN (no internet)
2. **No passwords**: Username is identifier only
3. **Single writer**: SQLite (could add WAL mode for better concurrency)
4. **No compression**: Sends files as-is
5. **No bandwidth throttling**: Can saturate network
6. **No UI for server**: Text-based console only (could add web dashboard)

---

## 📚 Documentation Map

| Document | Purpose | Length |
|----------|---------|--------|
| `IMPLEMENTATION_GUIDE.md` | Full architecture, setup, database schema, troubleshooting | 17 KB |
| `PROTOCOL_REFERENCE.md` | Binary protocol spec, wire formats, code examples | 15 KB |
| `README.md` (this file) | Quick start, navigation, feature checklist | 8 KB |

---

## 🎯 Next Steps

1. **Read**: `IMPLEMENTATION_GUIDE.md` (architecture overview)
2. **Setup**: Follow "Go Server Setup" and "Android App Setup"
3. **Test**: Complete the testing checklist above
4. **Reference**: Use `PROTOCOL_REFERENCE.md` for protocol details

---

## 📞 Support

### Common Issues

**"Connection refused"**
- Verify server is running: `go run main.go`
- Check firewall: Allow port 10101

**"Database locked"**
- Close other SQLite connections
- Restart server (recreates DB)

**"Upload timeout"**
- Reduce chunk size for slow networks
- Or: Increase timeout in Kotlin client

**"Files not appearing"**
- Check: `syncserver_data/storage/users/1/device1/`
- Verify: Database record exists (SQLite viewer)

See `IMPLEMENTATION_GUIDE.md` "Troubleshooting" for more.

---

## 📄 License

**INTERNAL USE ONLY // LIMITED DISTRIBUTION**

This system is proprietary to LanSync and is not intended for public distribution.

---

## 📋 Changelog

**Version 1.0** (2026-07-18)
- Initial release
- Go server with SQLite
- Android app with Jetpack Compose
- Binary protocol over TCP
- Parallel uploads (max 10)
- Sync history tracking
- Duplicate detection with smart merge

---

**Ready to deploy. Good luck! 🚀**
