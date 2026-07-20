package main

import (
	"bufio"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/mattn/go-isatty"
	_ "modernc.org/sqlite"
)

// ============================================================================
// CONFIGURATION & MODELS
// ============================================================================

type Config struct {
	Port        int
	DataDir     string
	DBPath      string
	StoragePath string
}

type Server struct {
	config Config
	db     *sql.DB
	mu     sync.RWMutex
}

type SyncHistory struct {
	ID        int64
	UserID    int64
	DeviceID  int64
	FileID    int64
	Action    string // "upload", "skip", "conflict"
	Status    string // "success", "failed"
	Message   string
	CreatedAt time.Time
}

// Binary Protocol Commands
const (
	CMD_HANDSHAKE       = 0x01
	CMD_CHECK_DUPLICATE = 0x02
	CMD_UPLOAD_START    = 0x03
	CMD_UPLOAD_CHUNK    = 0x04
	CMD_UPLOAD_END      = 0x05
	CMD_DELETE_CONFIRM  = 0x06
	CMD_SYNC_HISTORY    = 0x07

	RESP_OK          = 0x00
	RESP_DUPLICATE   = 0x01
	RESP_ERROR       = 0x02
	RESP_NEED_UPDATE = 0x03
)

// ============================================================================
// DATABASE INITIALIZATION
// ============================================================================

func initDB(dbPath string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", dbPath)
	if err != nil {
		return nil, err
	}

	if err := db.Ping(); err != nil {
		return nil, err
	}

	if _, err := db.Exec("PRAGMA foreign_keys = ON"); err != nil {
		return nil, err
	}

	// Unique identity for a synced file is (user, device, filename): the
	// storage path is keyed the same way, so a re-upload under a new hash
	// is an update to the same row, not a second file.
	schema := `
	CREATE TABLE IF NOT EXISTS users (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		username TEXT UNIQUE NOT NULL,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS devices (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		device_name TEXT NOT NULL,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		FOREIGN KEY(user_id) REFERENCES users(id),
		UNIQUE(user_id, device_name)
	);

	CREATE TABLE IF NOT EXISTS sync_files (
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
		UNIQUE(user_id, device_id, filename)
	);

	CREATE TABLE IF NOT EXISTS sync_history (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		user_id INTEGER NOT NULL,
		device_id INTEGER NOT NULL,
		file_id INTEGER,
		action TEXT NOT NULL,
		status TEXT NOT NULL,
		message TEXT,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		FOREIGN KEY(user_id) REFERENCES users(id),
		FOREIGN KEY(device_id) REFERENCES devices(id),
		FOREIGN KEY(file_id) REFERENCES sync_files(id)
	);

	CREATE INDEX IF NOT EXISTS idx_files_user_device ON sync_files(user_id, device_id);
	CREATE INDEX IF NOT EXISTS idx_history_user_device ON sync_history(user_id, device_id);
	CREATE INDEX IF NOT EXISTS idx_history_created ON sync_history(created_at);
	`

	_, err = db.Exec(schema)
	return db, err
}

// ============================================================================
// DATABASE OPERATIONS
// ============================================================================

func (s *Server) getOrCreateUser(username string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var userID int64
	err := s.db.QueryRow("SELECT id FROM users WHERE username = ?", username).Scan(&userID)
	if err == sql.ErrNoRows {
		result, err := s.db.Exec("INSERT INTO users (username) VALUES (?)", username)
		if err != nil {
			return 0, err
		}
		return result.LastInsertId()
	}
	return userID, err
}

func (s *Server) getOrCreateDevice(userID int64, deviceName string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var deviceID int64
	err := s.db.QueryRow("SELECT id FROM devices WHERE user_id = ? AND device_name = ?", userID, deviceName).Scan(&deviceID)
	if err == sql.ErrNoRows {
		result, err := s.db.Exec("INSERT INTO devices (user_id, device_name) VALUES (?, ?)", userID, deviceName)
		if err != nil {
			return 0, err
		}
		return result.LastInsertId()
	}
	return deviceID, err
}

// checkDuplicate looks up the existing row (if any) for this filename on
// this user/device. Per the protocol: no row -> new; row with matching
// hash+size -> duplicate; row with a different hash/size -> needs update.
func (s *Server) checkDuplicate(userID, deviceID int64, fileHash, filename string, fileSize int64) (isDuplicate, needsUpdate bool, err error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var existingHash string
	var existingSize int64
	err = s.db.QueryRow(`
		SELECT file_hash, file_size FROM sync_files
		WHERE user_id = ? AND device_id = ? AND filename = ?
	`, userID, deviceID, filename).Scan(&existingHash, &existingSize)

	if err == sql.ErrNoRows {
		return false, false, nil // not seen before
	}
	if err != nil {
		return false, false, err
	}

	if existingHash == fileHash && existingSize == fileSize {
		return true, false, nil // identical
	}
	return true, true, nil // same filename, different contents
}

func (s *Server) recordSyncFile(userID, deviceID int64, filename, fileHash, directory, filePath string, fileSize int64) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	result, err := s.db.Exec(`
		INSERT INTO sync_files (user_id, device_id, filename, file_hash, file_size, directory, file_path, status, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, 'synced', CURRENT_TIMESTAMP)
		ON CONFLICT(user_id, device_id, filename) DO UPDATE SET
			file_hash = excluded.file_hash,
			file_size = excluded.file_size,
			directory = excluded.directory,
			file_path = excluded.file_path,
			status = 'synced',
			updated_at = CURRENT_TIMESTAMP
	`, userID, deviceID, filename, fileHash, fileSize, directory, filePath)

	if err != nil {
		return 0, err
	}

	if id, err := result.LastInsertId(); err == nil && id != 0 {
		return id, nil
	}

	// Row was updated rather than inserted; look its id back up.
	var id int64
	err = s.db.QueryRow(`
		SELECT id FROM sync_files WHERE user_id = ? AND device_id = ? AND filename = ?
	`, userID, deviceID, filename).Scan(&id)
	return id, err
}

func (s *Server) recordHistory(userID, deviceID, fileID int64, action, status, message string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	// file_id is optional; 0 means NULL so SQLite FK checks pass with foreign_keys=ON.
	var fileIDArg interface{}
	if fileID > 0 {
		fileIDArg = fileID
	}

	_, err := s.db.Exec(`
		INSERT INTO sync_history (user_id, device_id, file_id, action, status, message)
		VALUES (?, ?, ?, ?, ?, ?)
	`, userID, deviceID, fileIDArg, action, status, message)
	return err
}

func (s *Server) getHistory(userID, deviceID int64, limit int) ([]SyncHistory, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rows, err := s.db.Query(`
		SELECT id, user_id, device_id, file_id, action, status, message, created_at
		FROM sync_history
		WHERE user_id = ? AND device_id = ?
		ORDER BY created_at DESC
		LIMIT ?
	`, userID, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var history []SyncHistory
	for rows.Next() {
		var h SyncHistory
		var fileID sql.NullInt64
		if err := rows.Scan(&h.ID, &h.UserID, &h.DeviceID, &fileID, &h.Action, &h.Status, &h.Message, &h.CreatedAt); err != nil {
			return nil, err
		}
		h.FileID = fileID.Int64
		history = append(history, h)
	}
	return history, rows.Err()
}

// ============================================================================
// FILE STORAGE
// ============================================================================

func (s *Server) getStoragePath(userID, deviceID int64, fileType, filename string) string {
	// users/{userID}/device{deviceID}/{fileType}/filename
	return filepath.Join(
		s.config.StoragePath,
		fmt.Sprintf("users/%d/device%d/%s", userID, deviceID, sanitizePathComponent(fileType)),
		sanitizeFilename(filename),
	)
}

// sanitizeFilename strips directories and characters that are illegal on
// Windows (and awkward on other OSes). Prevents path traversal and Create failures.
func sanitizeFilename(name string) string {
	name = filepath.Base(strings.ReplaceAll(name, "\\", "/"))
	if name == "." || name == ".." || name == "" {
		return "file"
	}
	var b strings.Builder
	b.Grow(len(name))
	for _, r := range name {
		switch {
		case r < 0x20:
			b.WriteByte('_')
		case strings.ContainsRune(`<>:"/\|?*`, r):
			b.WriteByte('_')
		default:
			b.WriteRune(r)
		}
	}
	out := strings.TrimRight(strings.TrimSpace(b.String()), ". ")
	if out == "" {
		return "file"
	}
	// Windows reserved device names
	stem := out
	if i := strings.LastIndex(out, "."); i > 0 {
		stem = out[:i]
	}
	switch strings.ToUpper(stem) {
	case "CON", "PRN", "AUX", "NUL",
		"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		out = "_" + out
	}
	if len(out) > 200 {
		out = out[:200]
	}
	return out
}

func sanitizePathComponent(name string) string {
	name = sanitizeFilename(name)
	if name == "file" && name != "" {
		// keep
	}
	return name
}

// isBenignDisconnect is true for normal client disconnects that should not
// be logged as server errors (avoids noisy "broken pipe" spam).
func isBenignDisconnect(err error) bool {
	if err == nil || err == io.EOF {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "broken pipe") ||
		strings.Contains(msg, "connection reset") ||
		strings.Contains(msg, "forcibly closed") ||
		strings.Contains(msg, "wsasend") ||
		strings.Contains(msg, "wsarecv") ||
		strings.Contains(msg, "use of closed network connection") ||
		strings.Contains(msg, "connection aborted")
}

const maxChunkSize = 16 * 1024 * 1024 // 16 MiB hard cap

// ============================================================================
// BINARY PROTOCOL HANDLER
// ============================================================================

// uploadSession tracks an in-progress upload for one connection. A file is
// streamed straight to its final destination as chunks arrive, rather than
// buffered in memory, so uploads aren't bounded by RAM.
type uploadSession struct {
	userID    int64
	deviceID  int64
	filename  string
	fileHash  string
	fileType  string
	directory string
	fileSize  int64
	filePath  string
	file      *os.File
	written   int64
}

type sessionStore struct {
	mu       sync.Mutex
	sessions map[net.Conn]*uploadSession
}

func newSessionStore() *sessionStore {
	return &sessionStore{sessions: make(map[net.Conn]*uploadSession)}
}

func (st *sessionStore) set(conn net.Conn, sess *uploadSession) {
	st.mu.Lock()
	defer st.mu.Unlock()
	st.sessions[conn] = sess
}

func (st *sessionStore) get(conn net.Conn) *uploadSession {
	st.mu.Lock()
	defer st.mu.Unlock()
	return st.sessions[conn]
}

func (st *sessionStore) clear(conn net.Conn) {
	st.mu.Lock()
	defer st.mu.Unlock()
	delete(st.sessions, conn)
}

var uploads = newSessionStore()

// readString reads a 2-byte big-endian length prefix followed by that many
// bytes of UTF-8 data. io.ReadFull is required here (not Reader.Read) since
// a single Read on a buffered network connection is not guaranteed to fill
// the requested slice.
func readString(reader *bufio.Reader) (string, error) {
	lenBuf := make([]byte, 2)
	if _, err := io.ReadFull(reader, lenBuf); err != nil {
		return "", err
	}
	n := binary.BigEndian.Uint16(lenBuf)
	buf := make([]byte, n)
	if n > 0 {
		if _, err := io.ReadFull(reader, buf); err != nil {
			return "", err
		}
	}
	return string(buf), nil
}

func readUint64(reader *bufio.Reader) (uint64, error) {
	buf := make([]byte, 8)
	if _, err := io.ReadFull(reader, buf); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint64(buf), nil
}

func (s *Server) handleClient(conn net.Conn) {
	defer func() {
		if sess := uploads.get(conn); sess != nil {
			sess.file.Close()
			os.Remove(sess.filePath) // incomplete upload; discard partial file
			uploads.clear(conn)
		}
		conn.Close()
	}()

	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetKeepAlive(true)
		_ = tc.SetKeepAlivePeriod(30 * time.Second)
		_ = tc.SetNoDelay(true)
	}

	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)

	cmd := make([]byte, 1)
	if _, err := io.ReadFull(reader, cmd); err != nil {
		return
	}
	if cmd[0] != CMD_HANDSHAKE {
		_ = writeResp(writer, RESP_ERROR)
		return
	}

	username, err := readString(reader)
	if err != nil {
		return
	}
	deviceName, err := readString(reader)
	if err != nil {
		return
	}

	userID, err := s.getOrCreateUser(username)
	if err != nil {
		_ = writeResp(writer, RESP_ERROR)
		return
	}

	deviceID, err := s.getOrCreateDevice(userID, deviceName)
	if err != nil {
		_ = writeResp(writer, RESP_ERROR)
		return
	}

	if err := writeResp(writer, RESP_OK); err != nil {
		return
	}

	fmt.Printf("Client connected: user=%q device=%q from %s\n", username, deviceName, conn.RemoteAddr())
	s.commandLoop(conn, reader, writer, userID, deviceID)
	fmt.Printf("Client disconnected: %s\n", conn.RemoteAddr())
}

func writeResp(writer *bufio.Writer, code byte) error {
	if err := writer.WriteByte(code); err != nil {
		return err
	}
	return writer.Flush()
}

func (s *Server) commandLoop(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
	cmdBuf := make([]byte, 1)

	for {
		if _, err := io.ReadFull(reader, cmdBuf); err != nil {
			if !isBenignDisconnect(err) {
				fmt.Printf("Error reading command from %s: %v\n", conn.RemoteAddr(), err)
			}
			return
		}

		var err error
		switch cmdBuf[0] {
		case CMD_CHECK_DUPLICATE:
			err = s.handleCheckDuplicate(reader, writer, userID, deviceID)
		case CMD_UPLOAD_START:
			err = s.handleUploadStart(conn, reader, writer, userID, deviceID)
		case CMD_UPLOAD_CHUNK:
			err = s.handleUploadChunk(conn, reader, writer)
		case CMD_UPLOAD_END:
			err = s.handleUploadEnd(conn, reader, writer, userID, deviceID)
		case CMD_DELETE_CONFIRM:
			err = s.handleDeleteConfirm(writer)
		case CMD_SYNC_HISTORY:
			err = s.handleSyncHistory(writer, userID, deviceID)
		default:
			// Unknown command — respond and keep going (client may recover).
			err = writeResp(writer, RESP_ERROR)
		}
		if err != nil {
			if !isBenignDisconnect(err) {
				fmt.Printf("Protocol error from %s: %v\n", conn.RemoteAddr(), err)
			}
			return
		}
	}
}

func (s *Server) handleCheckDuplicate(reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) error {
	fileHash, err := readString(reader)
	if err != nil {
		return err
	}
	filename, err := readString(reader)
	if err != nil {
		return err
	}
	filename = sanitizeFilename(filename)
	fileSize, err := readUint64(reader)
	if err != nil {
		return err
	}

	isDuplicate, needsUpdate, err := s.checkDuplicate(userID, deviceID, fileHash, filename, int64(fileSize))
	if err != nil {
		_ = writeResp(writer, RESP_ERROR)
		return nil // keep connection; application error already reported
	}

	switch {
	case !isDuplicate:
		return writeResp(writer, RESP_OK)
	case needsUpdate:
		return writeResp(writer, RESP_NEED_UPDATE)
	default:
		return writeResp(writer, RESP_DUPLICATE)
	}
}

func (s *Server) handleUploadStart(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) error {
	// Close any previous incomplete session on this connection.
	if prev := uploads.get(conn); prev != nil {
		prev.file.Close()
		os.Remove(prev.filePath)
		uploads.clear(conn)
	}

	filename, err := readString(reader)
	if err != nil {
		return err
	}
	filename = sanitizeFilename(filename)
	fileHash, err := readString(reader)
	if err != nil {
		return err
	}
	fileType, err := readString(reader)
	if err != nil {
		return err
	}
	directory, err := readString(reader)
	if err != nil {
		return err
	}
	fileSize, err := readUint64(reader)
	if err != nil {
		return err
	}

	filePath := s.getStoragePath(userID, deviceID, fileType, filename)
	if err := os.MkdirAll(filepath.Dir(filePath), 0755); err != nil {
		fmt.Printf("MkdirAll failed for %s: %v\n", filePath, err)
		return writeResp(writer, RESP_ERROR)
	}

	f, err := os.Create(filePath)
	if err != nil {
		fmt.Printf("Create failed for %s: %v\n", filePath, err)
		return writeResp(writer, RESP_ERROR)
	}

	uploads.set(conn, &uploadSession{
		userID:    userID,
		deviceID:  deviceID,
		filename:  filename,
		fileHash:  fileHash,
		fileType:  fileType,
		directory: directory,
		fileSize:  int64(fileSize),
		filePath:  filePath,
		file:      f,
	})

	return writeResp(writer, RESP_OK)
}

func (s *Server) handleUploadChunk(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer) error {
	sizeBuf := make([]byte, 4)
	if _, err := io.ReadFull(reader, sizeBuf); err != nil {
		return err
	}
	chunkSize := binary.BigEndian.Uint32(sizeBuf)
	if chunkSize == 0 || chunkSize > maxChunkSize {
		// Drain is impossible without knowing true size if corrupt; drop connection.
		return fmt.Errorf("invalid chunk size %d", chunkSize)
	}

	chunk := make([]byte, chunkSize)
	if _, err := io.ReadFull(reader, chunk); err != nil {
		return err
	}

	sess := uploads.get(conn)
	if sess == nil {
		return writeResp(writer, RESP_ERROR)
	}

	if _, err := sess.file.Write(chunk); err != nil {
		fmt.Printf("Chunk write failed for %s: %v\n", sess.filename, err)
		return writeResp(writer, RESP_ERROR)
	}
	sess.written += int64(len(chunk))

	return writeResp(writer, RESP_OK)
}

func (s *Server) handleUploadEnd(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) error {
	// Metadata is re-sent for verification, per protocol.
	filename, err := readString(reader)
	if err != nil {
		return err
	}
	filename = sanitizeFilename(filename)
	fileHash, err := readString(reader)
	if err != nil {
		return err
	}
	_, err = readString(reader) // file type (already known from UPLOAD_START)
	if err != nil {
		return err
	}
	directory, err := readString(reader)
	if err != nil {
		return err
	}

	sess := uploads.get(conn)
	if sess == nil || sess.filename != filename {
		return writeResp(writer, RESP_ERROR)
	}
	defer uploads.clear(conn)

	if err := sess.file.Close(); err != nil {
		_ = s.recordHistory(userID, deviceID, 0, "upload", "failed", err.Error())
		return writeResp(writer, RESP_ERROR)
	}

	fileID, err := s.recordSyncFile(userID, deviceID, filename, fileHash, directory, sess.filePath, sess.written)
	if err != nil {
		_ = s.recordHistory(userID, deviceID, 0, "upload", "failed", err.Error())
		return writeResp(writer, RESP_ERROR)
	}

	_ = s.recordHistory(userID, deviceID, fileID, "upload", "success", "File uploaded successfully")

	return writeResp(writer, RESP_OK)
}

func (s *Server) handleDeleteConfirm(writer *bufio.Writer) error {
	return writeResp(writer, RESP_OK)
}

func (s *Server) handleSyncHistory(writer *bufio.Writer, userID, deviceID int64) error {
	history, err := s.getHistory(userID, deviceID, 100)
	if err != nil {
		return writeResp(writer, RESP_ERROR)
	}

	if err := writer.WriteByte(RESP_OK); err != nil {
		return err
	}

	countBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(countBuf, uint32(len(history)))
	if _, err := writer.Write(countBuf); err != nil {
		return err
	}

	for _, h := range history {
		msg := fmt.Sprintf("%s|%s|%s|%s", h.CreatedAt.Format(time.RFC3339), h.Action, h.Status, h.Message)
		lenBuf := make([]byte, 2)
		binary.BigEndian.PutUint16(lenBuf, uint16(len(msg)))
		if _, err := writer.Write(lenBuf); err != nil {
			return err
		}
		if _, err := writer.WriteString(msg); err != nil {
			return err
		}
	}
	return writer.Flush()
}

// ============================================================================
// HASH CALCULATION
// ============================================================================

func calculateFileHash(data []byte) string {
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:])
}

// ============================================================================
// TUI & MAIN
// ============================================================================

const clearScreen = "\033[H\033[2J"

func (s *Server) displayStatus() {
	fmt.Print(clearScreen)
	fmt.Println("╔════════════════════════════════════════════════════════════╗")
	fmt.Println("║        LanSync Server                                       ║")
	fmt.Println("╚════════════════════════════════════════════════════════════╝")
	fmt.Printf("Port:        %d\n", s.config.Port)
	fmt.Printf("Data Dir:    %s\n", s.config.DataDir)
	fmt.Printf("Storage:     %s\n", s.config.StoragePath)
	fmt.Println("\nCommands:")
	fmt.Println("  h - Show sync history")
	fmt.Println("  s - Server stats")
	fmt.Println("  q - Quit")
	fmt.Println("")
}

func (s *Server) showStats() {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var userCount, deviceCount, fileCount int
	s.db.QueryRow("SELECT COUNT(*) FROM users").Scan(&userCount)
	s.db.QueryRow("SELECT COUNT(*) FROM devices").Scan(&deviceCount)
	s.db.QueryRow("SELECT COUNT(*) FROM sync_files").Scan(&fileCount)

	fmt.Printf("\nServer Stats:\n")
	fmt.Printf("  Users:   %d\n", userCount)
	fmt.Printf("  Devices: %d\n", deviceCount)
	fmt.Printf("  Files:   %d\n", fileCount)
}

func main() {
	port := flag.Int("port", 10101, "Server port")
	dataDir := flag.String("datadir", "./syncserver_data", "Data directory")
	flag.Parse()

	config := Config{
		Port:        *port,
		DataDir:     *dataDir,
		DBPath:      filepath.Join(*dataDir, "sync.db"),
		StoragePath: filepath.Join(*dataDir, "storage"),
	}

	os.MkdirAll(config.DataDir, 0755)
	os.MkdirAll(config.StoragePath, 0755)

	db, err := initDB(config.DBPath)
	if err != nil {
		fmt.Printf("Failed to initialize database: %v\n", err)
		os.Exit(1)
	}
	defer db.Close()

	server := &Server{
		config: config,
		db:     db,
	}

	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", config.Port))
	if err != nil {
		fmt.Printf("Failed to listen on port %d: %v\n", config.Port, err)
		os.Exit(1)
	}
	defer listener.Close()

	fmt.Printf("LanSync server listening on port %d\n", config.Port)

	// Accept loop exits cleanly when the listener is closed on shutdown.
	// Without this check, Close() surfaces as a noisy "use of closed network
	// connection" and the goroutine would spin forever.
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				if errors.Is(err, net.ErrClosed) || isClosedNetworkError(err) {
					return
				}
				fmt.Printf("Connection error: %v\n", err)
				continue
			}
			go server.handleClient(conn)
		}
	}()

	// ServiceMonitor (and other supervisors) start us with no console / null
	// stdin. The old TUI treated stdin EOF as "quit", which closed the
	// listener immediately and produced: accept tcp [::]:10101: use of
	// closed network connection. Headless mode blocks on OS signals instead.
	if !isatty.IsTerminal(os.Stdin.Fd()) && !isatty.IsCygwinTerminal(os.Stdin.Fd()) {
		runHeadless(listener)
		return
	}

	runInteractive(server, listener)
}

// isClosedNetworkError matches the pre-Go-1.16 string and Windows variants
// of a closed listener Accept error.
func isClosedNetworkError(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "use of closed network connection") ||
		strings.Contains(msg, "closed network connection")
}

// runHeadless keeps the server alive until SIGINT/SIGTERM (or Windows
// process termination from ServiceMonitor).
func runHeadless(listener net.Listener) {
	fmt.Println("Running headless (no interactive console). Waiting for signal to stop...")
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	sig := <-sigCh
	fmt.Printf("Shutting down (%v)...\n", sig)
	_ = listener.Close()
}

func runInteractive(server *Server, listener net.Listener) {
	scanner := bufio.NewScanner(os.Stdin)
	for {
		server.displayStatus()
		fmt.Print("sync> ")

		if !scanner.Scan() {
			// Real console closed (e.g. window closed); shut down cleanly.
			fmt.Println("\nStdin closed; shutting down...")
			_ = listener.Close()
			return
		}

		switch strings.TrimSpace(scanner.Text()) {
		case "h":
			history, err := server.getHistory(1, 1, 10)
			if err == nil && len(history) > 0 {
				fmt.Println("\nRecent Sync History:")
				for _, h := range history {
					fmt.Printf("  %s | %s | %s | %s\n", h.CreatedAt.Format("15:04:05"), h.Action, h.Status, h.Message)
				}
			} else {
				fmt.Println("No sync history yet.")
			}
			fmt.Print("Press Enter...")
			scanner.Scan()

		case "s":
			server.showStats()
			fmt.Print("Press Enter...")
			scanner.Scan()

		case "q":
			fmt.Println("Shutting down...")
			_ = listener.Close()
			return

		case "":
			// ignore blank input

		default:
			fmt.Println("Unknown command")
		}
	}
}
