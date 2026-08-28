package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

// Binary protocol commands/responses — must match server/main.go and the Android
// client (SyncClient.kt) exactly. See docs/PROTOCOL_REFERENCE.md.
const (
	cmdHandshake      byte = 0x01
	cmdCheckDuplicate byte = 0x02
	cmdUploadStart    byte = 0x03
	cmdUploadChunk    byte = 0x04
	cmdUploadEnd      byte = 0x05
	cmdDeleteConfirm  byte = 0x06
	cmdSyncHistory    byte = 0x07

	respOK         byte = 0x00
	respDuplicate  byte = 0x01
	respError      byte = 0x02
	respNeedUpdate byte = 0x03
)

const (
	chunkSize      = 65536 // 64KB, matches BinaryProtocol.CHUNK_SIZE
	connectTimeout = 10 * time.Second
	readTimeout    = 120 * time.Second
)

// FileInfo describes one file being checked/uploaded — mirrors the Android FileInfo.
type FileInfo struct {
	Filename  string
	FileHash  string
	FileType  string // "photos", "documents", "folders"
	Directory string
	FilePath  string // local path on disk
	FileSize  int64
}

// DuplicateCheckResult mirrors DuplicateCheckResult.kt.
type DuplicateCheckResult struct {
	IsDuplicate bool
	ShouldUpdate bool
}

// SyncClient is a single TCP connection to the LanSync server. Not safe for
// concurrent use — the app uses one client on one goroutine at a time, same
// as the Android client's single-connection design.
type SyncClient struct {
	Host       string
	Port       int
	Username   string
	DeviceName string
	AppVersion string

	conn net.Conn
	r    *bufio.Reader
	w    *bufio.Writer
}

func (c *SyncClient) Connect() error {
	c.Close()
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", c.Host, c.Port), connectTimeout)
	if err != nil {
		return fmt.Errorf("connect to %s:%d: %w", c.Host, c.Port, err)
	}
	c.conn = conn
	c.r = bufio.NewReader(conn)
	c.w = bufio.NewWriter(conn)

	if err := c.handshake(); err != nil {
		c.Close()
		return err
	}
	return nil
}

func (c *SyncClient) Close() {
	if c.conn != nil {
		c.conn.Close()
		c.conn = nil
	}
}

func (c *SyncClient) Connected() bool {
	return c.conn != nil
}

func (c *SyncClient) deadline() {
	if c.conn != nil {
		c.conn.SetDeadline(time.Now().Add(readTimeout))
	}
}

func (c *SyncClient) handshake() error {
	c.deadline()
	if err := c.w.WriteByte(cmdHandshake); err != nil {
		return err
	}
	if err := writeString(c.w, c.Username); err != nil {
		return err
	}
	if err := writeString(c.w, c.DeviceName); err != nil {
		return err
	}
	if err := writeString(c.w, c.AppVersion); err != nil {
		return err
	}
	if err := c.w.Flush(); err != nil {
		return err
	}
	resp, err := c.r.ReadByte()
	if err != nil {
		return err
	}
	if resp != respOK {
		return fmt.Errorf("handshake rejected (code=%d)", resp)
	}
	return nil
}

func (c *SyncClient) CheckDuplicate(info FileInfo) (DuplicateCheckResult, error) {
	c.deadline()
	if err := c.w.WriteByte(cmdCheckDuplicate); err != nil {
		return DuplicateCheckResult{}, err
	}
	if err := writeString(c.w, info.FileHash); err != nil {
		return DuplicateCheckResult{}, err
	}
	if err := writeString(c.w, info.Filename); err != nil {
		return DuplicateCheckResult{}, err
	}
	if err := binary.Write(c.w, binary.BigEndian, info.FileSize); err != nil {
		return DuplicateCheckResult{}, err
	}
	if err := c.w.Flush(); err != nil {
		return DuplicateCheckResult{}, err
	}
	resp, err := c.r.ReadByte()
	if err != nil {
		return DuplicateCheckResult{}, err
	}
	switch resp {
	case respOK:
		return DuplicateCheckResult{IsDuplicate: false}, nil
	case respDuplicate:
		return DuplicateCheckResult{IsDuplicate: true, ShouldUpdate: false}, nil
	case respNeedUpdate:
		return DuplicateCheckResult{IsDuplicate: true, ShouldUpdate: true}, nil
	case respError:
		return DuplicateCheckResult{}, fmt.Errorf("server error during duplicate check")
	default:
		return DuplicateCheckResult{}, fmt.Errorf("invalid duplicate-check response: %d", resp)
	}
}

// UploadFile streams a file's bytes (read from r, info.FileSize long) to the
// server in chunkSize pieces, calling onProgress after each chunk is ack'd.
func (c *SyncClient) UploadFile(info FileInfo, r io.Reader, onProgress func(sent int64)) error {
	c.deadline()
	if err := c.w.WriteByte(cmdUploadStart); err != nil {
		return err
	}
	if err := writeString(c.w, info.Filename); err != nil {
		return err
	}
	if err := writeString(c.w, info.FileHash); err != nil {
		return err
	}
	if err := writeString(c.w, info.FileType); err != nil {
		return err
	}
	if err := writeString(c.w, info.Directory); err != nil {
		return err
	}
	if err := binary.Write(c.w, binary.BigEndian, info.FileSize); err != nil {
		return err
	}
	if err := c.w.Flush(); err != nil {
		return err
	}
	if resp, err := c.r.ReadByte(); err != nil {
		return err
	} else if resp != respOK {
		return fmt.Errorf("upload start rejected (code=%d)", resp)
	}

	buf := make([]byte, chunkSize)
	var sent int64
	for sent < info.FileSize {
		remaining := info.FileSize - sent
		want := int64(chunkSize)
		if remaining < want {
			want = remaining
		}
		n, err := io.ReadFull(r, buf[:want])
		if err != nil && err != io.ErrUnexpectedEOF {
			return fmt.Errorf("read %s at %d/%d: %w", info.Filename, sent, info.FileSize, err)
		}
		if n == 0 {
			break
		}

		c.deadline()
		if err := c.w.WriteByte(cmdUploadChunk); err != nil {
			return err
		}
		if err := binary.Write(c.w, binary.BigEndian, int32(n)); err != nil {
			return err
		}
		if _, err := c.w.Write(buf[:n]); err != nil {
			return err
		}
		if err := c.w.Flush(); err != nil {
			return err
		}
		resp, err := c.r.ReadByte()
		if err != nil {
			return err
		}
		if resp != respOK {
			return fmt.Errorf("chunk rejected at offset %d (code=%d)", sent, resp)
		}
		sent += int64(n)
		if onProgress != nil {
			onProgress(sent)
		}
	}

	c.deadline()
	if err := c.w.WriteByte(cmdUploadEnd); err != nil {
		return err
	}
	if err := writeString(c.w, info.Filename); err != nil {
		return err
	}
	if err := writeString(c.w, info.FileHash); err != nil {
		return err
	}
	if err := writeString(c.w, info.FileType); err != nil {
		return err
	}
	if err := writeString(c.w, info.Directory); err != nil {
		return err
	}
	if err := c.w.Flush(); err != nil {
		return err
	}
	resp, err := c.r.ReadByte()
	if err != nil {
		return err
	}
	if resp != respOK {
		return fmt.Errorf("upload end rejected (code=%d)", resp)
	}
	if onProgress != nil {
		onProgress(info.FileSize)
	}
	return nil
}

func (c *SyncClient) ConfirmDelete() error {
	c.deadline()
	if err := c.w.WriteByte(cmdDeleteConfirm); err != nil {
		return err
	}
	if err := c.w.Flush(); err != nil {
		return err
	}
	resp, err := c.r.ReadByte()
	if err != nil {
		return err
	}
	if resp != respOK {
		return fmt.Errorf("delete confirm rejected (code=%d)", resp)
	}
	return nil
}

func (c *SyncClient) SyncHistory() ([]string, error) {
	c.deadline()
	if err := c.w.WriteByte(cmdSyncHistory); err != nil {
		return nil, err
	}
	if err := c.w.Flush(); err != nil {
		return nil, err
	}
	resp, err := c.r.ReadByte()
	if err != nil {
		return nil, err
	}
	if resp != respOK {
		return nil, fmt.Errorf("history request rejected (code=%d)", resp)
	}
	var count int32
	if err := binary.Read(c.r, binary.BigEndian, &count); err != nil {
		return nil, err
	}
	if count < 0 || count > 10000 {
		return nil, fmt.Errorf("invalid history count: %d", count)
	}
	history := make([]string, 0, count)
	for i := int32(0); i < count; i++ {
		s, err := readString(c.r)
		if err != nil {
			return nil, err
		}
		history = append(history, s)
	}
	return history, nil
}

func writeString(w *bufio.Writer, s string) error {
	b := []byte(s)
	if len(b) > 0xFFFF {
		return fmt.Errorf("string too long for protocol: %d bytes", len(b))
	}
	if err := binary.Write(w, binary.BigEndian, uint16(len(b))); err != nil {
		return err
	}
	_, err := w.Write(b)
	return err
}

func readString(r *bufio.Reader) (string, error) {
	var n uint16
	if err := binary.Read(r, binary.BigEndian, &n); err != nil {
		return "", err
	}
	if n == 0 {
		return "", nil
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

func isConnectionError(err error) bool {
	if err == nil {
		return false
	}
	if _, ok := err.(net.Error); ok {
		return true
	}
	msg := strings.ToLower(err.Error())
	for _, needle := range []string{"broken pipe", "connection reset", "use of closed network connection", "eof", "forcibly closed"} {
		if strings.Contains(msg, needle) {
			return true
		}
	}
	return false
}
