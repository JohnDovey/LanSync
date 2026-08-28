package main

import "time"

// AppVersion follows Major.Minor.Patch per ../.cursor/rules/versioning.mdc.
const AppVersion = "1.0.0"

// FileItem is one file/folder-entry picked for sync — mirrors SelectedItem.kt.
type FileItem struct {
	Path         string // absolute local path
	RelativePath string // set for folder-picked items, preserves subfolder structure; "" for flat picks
}

func (f FileItem) DisplayName() string {
	if f.RelativePath != "" {
		return f.RelativePath
	}
	return baseName(f.Path)
}

func (f FileItem) Key() string {
	return f.Path + "|" + f.RelativePath
}

// UploadStatus mirrors UploadStatus.kt.
type UploadStatus int

const (
	StatusPending UploadStatus = iota
	StatusUploading
	StatusCompleted
	StatusFailed
	StatusSkipped
	StatusDuplicate
)

// UploadProgress mirrors UploadProgress.kt.
type UploadProgress struct {
	Item       FileItem
	Info       FileInfo
	Sent       int64
	Total      int64
	Status     UploadStatus
	ErrMessage string
}

func (p UploadProgress) Percentage() int {
	if p.Total <= 0 {
		return 0
	}
	pct := int(p.Sent * 100 / p.Total)
	if pct > 100 {
		pct = 100
	}
	return pct
}

// ConflictResolution mirrors ConflictResolution.kt.
type ConflictResolution struct {
	Override   bool
	ApplyToAll bool
}

// FailedSyncEntry mirrors FailedSyncEntry.kt (Android) — kept across restarts so a
// dropped connection doesn't lose track of what still needs to sync.
type FailedSyncEntry struct {
	Path         string    `json:"path"`
	RelativePath string    `json:"relativePath"`
	DisplayName  string    `json:"displayName"`
	ErrorMessage string    `json:"errorMessage"`
	Timestamp    time.Time `json:"timestamp"`
}

func (f FailedSyncEntry) toItem() FileItem {
	return FileItem{Path: f.Path, RelativePath: f.RelativePath}
}

// Settings mirrors SavedSettings.kt — persisted connection fields.
type Settings struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	Username   string `json:"username"`
	DeviceName string `json:"deviceName"`
}

func defaultSettings() Settings {
	hostname, _ := hostName()
	return Settings{
		Host:       "192.168.0.195",
		Port:       10101,
		Username:   "user1",
		DeviceName: hostname,
	}
}
