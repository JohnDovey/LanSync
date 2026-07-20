package main

import (
	"bufio"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
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
	db, err := sql.Open("sqlite3", dbPath)
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

	_, err := s.db.Exec(`
		INSERT INTO sync_history (user_id, device_id, file_id, action, status, message)
		VALUES (?, ?, ?, ?, ?, ?)
	`, userID, deviceID, fileID, action, status, message)
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
		fmt.Sprintf("users/%d/device%d/%s", userID, deviceID, fileType),
		filename,
	)
}

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

	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)

	cmd := make([]byte, 1)
	if _, err := io.ReadFull(reader, cmd); err != nil {
		return
	}
	if cmd[0] != CMD_HANDSHAKE {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
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
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}

	deviceID, err := s.getOrCreateDevice(userID, deviceName)
	if err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}

	writer.WriteByte(RESP_OK)
	writer.Flush()

	s.commandLoop(conn, reader, writer, userID, deviceID)
}

func (s *Server) commandLoop(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
	cmdBuf := make([]byte, 1)

	for {
		if _, err := io.ReadFull(reader, cmdBuf); err != nil {
			if err != io.EOF {
				fmt.Printf("Error reading command: %v\n", err)
			}
			return
		}

		switch cmdBuf[0] {
		case CMD_CHECK_DUPLICATE:
			s.handleCheckDuplicate(reader, writer, userID, deviceID)
		case CMD_UPLOAD_START:
			s.handleUploadStart(conn, reader, writer, userID, deviceID)
		case CMD_UPLOAD_CHUNK:
			s.handleUploadChunk(conn, reader, writer)
		case CMD_UPLOAD_END:
			s.handleUploadEnd(conn, reader, writer, userID, deviceID)
		case CMD_DELETE_CONFIRM:
			s.handleDeleteConfirm(reader, writer)
		case CMD_SYNC_HISTORY:
			s.handleSyncHistory(reader, writer, userID, deviceID)
		default:
			writer.WriteByte(RESP_ERROR)
			writer.Flush()
		}
	}
}

func (s *Server) handleCheckDuplicate(reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
	fileHash, err := readString(reader)
	if err != nil {
		return
	}
	filename, err := readString(reader)
	if err != nil {
		return
	}
	fileSize, err := readUint64(reader)
	if err != nil {
		return
	}

	isDuplicate, needsUpdate, err := s.checkDuplicate(userID, deviceID, fileHash, filename, int64(fileSize))
	if err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}

	switch {
	case !isDuplicate:
		writer.WriteByte(RESP_OK)
	case needsUpdate:
		writer.WriteByte(RESP_NEED_UPDATE)
	default:
		writer.WriteByte(RESP_DUPLICATE)
	}
	writer.Flush()
}

func (s *Server) handleUploadStart(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
	filename, err := readString(reader)
	if err != nil {
		return
	}
	fileHash, err := readString(reader)
	if err != nil {
		return
	}
	fileType, err := readString(reader)
	if err != nil {
		return
	}
	directory, err := readString(reader)
	if err != nil {
		return
	}
	fileSize, err := readUint64(reader)
	if err != nil {
		return
	}

	filePath := s.getStoragePath(userID, deviceID, fileType, filename)
	if err := os.MkdirAll(filepath.Dir(filePath), 0755); err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}

	f, err := os.Create(filePath)
	if err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
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

	writer.WriteByte(RESP_OK)
	writer.Flush()
}

func (s *Server) handleUploadChunk(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer) {
	sizeBuf := make([]byte, 4)
	if _, err := io.ReadFull(reader, sizeBuf); err != nil {
		return
	}
	chunkSize := binary.BigEndian.Uint32(sizeBuf)

	chunk := make([]byte, chunkSize)
	if _, err := io.ReadFull(reader, chunk); err != nil {
		return
	}

	sess := uploads.get(conn)
	if sess == nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}

	if _, err := sess.file.Write(chunk); err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}
	sess.written += int64(len(chunk))

	writer.WriteByte(RESP_OK)
	writer.Flush()
}

func (s *Server) handleUploadEnd(conn net.Conn, reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
	// Metadata is re-sent for verification, per protocol.
	filename, err := readString(reader)
	if err != nil {
		return
	}
	fileHash, err := readString(reader)
	if err != nil {
		return
	}
	_, err = readString(reader) // file type (already known from UPLOAD_START)
	if err != nil {
		return
	}
	directory, err := readString(reader)
	if err != nil {
		return
	}

	sess := uploads.get(conn)
	if sess == nil || sess.filename != filename {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}
	defer uploads.clear(conn)

	if err := sess.file.Close(); err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		s.recordHistory(userID, deviceID, 0, "upload", "failed", err.Error())
		return
	}

	fileID, err := s.recordSyncFile(userID, deviceID, filename, fileHash, directory, sess.filePath, sess.written)
	if err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		s.recordHistory(userID, deviceID, 0, "upload", "failed", err.Error())
		return
	}

	s.recordHistory(userID, deviceID, fileID, "upload", "success", "File uploaded successfully")

	writer.WriteByte(RESP_OK)
	writer.Flush()
}

func (s *Server) handleDeleteConfirm(reader *bufio.Reader, writer *bufio.Writer) {
	writer.WriteByte(RESP_OK)
	writer.Flush()
}

func (s *Server) handleSyncHistory(reader *bufio.Reader, writer *bufio.Writer, userID, deviceID int64) {
	history, err := s.getHistory(userID, deviceID, 100)
	if err != nil {
		writer.WriteByte(RESP_ERROR)
		writer.Flush()
		return
	}

	writer.WriteByte(RESP_OK)

	countBuf := make([]byte, 4)
	binary.BigEndian.PutUint32(countBuf, uint32(len(history)))
	writer.Write(countBuf)

	for _, h := range history {
		msg := fmt.Sprintf("%s|%s|%s|%s", h.CreatedAt.Format(time.RFC3339), h.Action, h.Status, h.Message)
		lenBuf := make([]byte, 2)
		binary.BigEndian.PutUint16(lenBuf, uint16(len(msg)))
		writer.Write(lenBuf)
		writer.WriteString(msg)
	}
	writer.Flush()
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

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				fmt.Printf("Connection error: %v\n", err)
				continue
			}
			go server.handleClient(conn)
		}
	}()

	scanner := bufio.NewScanner(os.Stdin)
	for {
		server.displayStatus()
		fmt.Print("sync> ")

		if !scanner.Scan() {
			break
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
			return

		case "":
			// ignore blank input

		default:
			fmt.Println("Unknown command")
		}
	}
}
