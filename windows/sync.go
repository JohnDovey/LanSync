package main

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"fyne.io/fyne/v2"
)

var imageVideoExt = map[string]bool{
	".jpg": true, ".jpeg": true, ".png": true, ".gif": true, ".webp": true, ".heic": true,
	".bmp": true, ".mp4": true, ".mov": true, ".avi": true, ".mkv": true, ".webm": true, ".3gp": true,
}

// buildFileInfo hashes the file and derives the protocol metadata for it —
// mirrors SyncViewModel.buildFileInfoStreaming (Android).
func buildFileInfo(item FileItem) (FileInfo, error) {
	f, err := os.Open(item.Path)
	if err != nil {
		return FileInfo{}, err
	}
	defer f.Close()

	h := sha256.New()
	size, err := io.Copy(h, f)
	if err != nil {
		return FileInfo{}, err
	}
	hash := hex.EncodeToString(h.Sum(nil))

	if item.RelativePath != "" {
		rel := sanitizeRelativePath(item.RelativePath)
		dir := ""
		if i := strings.LastIndex(rel, "/"); i >= 0 {
			dir = rel[:i]
		}
		return FileInfo{
			Filename:  rel,
			FileHash:  hash,
			FileType:  "folders",
			Directory: dir,
			FilePath:  item.Path,
			FileSize:  size,
		}, nil
	}

	name := sanitizeFilename(baseName(item.Path))
	fileType := "documents"
	if imageVideoExt[strings.ToLower(filepath.Ext(name))] {
		fileType = "photos"
	}
	return FileInfo{
		Filename:  name,
		FileHash:  hash,
		FileType:  fileType,
		Directory: "",
		FilePath:  item.Path,
		FileSize:  size,
	}, nil
}

// walkFolder recursively lists every file under root, producing FileItems whose
// RelativePath is rooted at the folder's own name — mirrors listFilesInTree
// (Android), which preserves the picked folder as the top path segment so the
// server recreates the same tree under the device's storage root.
func walkFolder(root string) ([]FileItem, error) {
	rootName := sanitizeFilename(baseName(root))
	var items []FileItem
	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return nil // skip unreadable entries, keep walking
		}
		if info.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		items = append(items, FileItem{
			Path:         path,
			RelativePath: rootName + "/" + rel,
		})
		return nil
	})
	return items, err
}

// syncFiles uploads items one at a time over a.client, mirroring
// UploadManager.uploadFileStreaming's per-file flow (duplicate check, conflict
// resolution, chunked upload, reconnect-and-retry-once on a dropped connection).
func (a *App) syncFiles(items []FileItem) {
	a.progressMu.Lock()
	a.progress = make([]UploadProgress, 0, len(items))
	a.progressIndex = make(map[string]int, len(items))
	for i, it := range items {
		a.progress = append(a.progress, UploadProgress{Item: it, Status: StatusPending})
		a.progressIndex[it.Key()] = i
	}
	a.globalConflict = nil
	a.progressMu.Unlock()

	a.showProgressScreen()

	var completedItems []FileItem
	for _, item := range items {
		a.syncOne(item, &completedItems)
	}

	a.reconcileFailedSyncs(items)
	fyne.Do(func() { a.showCompleteScreen(completedItems) })
}

func (a *App) updateProgress(key string, mutate func(*UploadProgress)) {
	a.progressMu.Lock()
	idx, ok := a.progressIndex[key]
	if ok {
		mutate(&a.progress[idx])
	}
	a.progressMu.Unlock()
	fyne.Do(func() { a.refreshProgressList() })
}

func (a *App) syncOne(item FileItem, completedItems *[]FileItem) {
	key := item.Key()
	a.updateProgress(key, func(p *UploadProgress) { p.Status = StatusUploading })

	info, err := buildFileInfo(item)
	if err != nil {
		a.failItem(item, info, err.Error())
		return
	}
	a.updateProgress(key, func(p *UploadProgress) { p.Info = info; p.Total = info.FileSize })

	dup, err := a.client.CheckDuplicate(info)
	if err != nil && isConnectionError(err) {
		if rerr := a.client.Connect(); rerr == nil {
			dup, err = a.client.CheckDuplicate(info)
		}
	}
	if err != nil {
		a.failItem(item, info, err.Error())
		return
	}

	if dup.IsDuplicate && !dup.ShouldUpdate {
		// Byte-identical file already on the server — nothing to ask, just skip.
		a.updateProgress(key, func(p *UploadProgress) { p.Status = StatusDuplicate; p.Sent = p.Total })
		return
	}

	if dup.IsDuplicate && dup.ShouldUpdate {
		// Same filename, different content — the actual conflict: ask before overwriting.
		resolution := a.globalConflict
		if resolution == nil {
			resolution = a.askConflict(info)
		}
		if resolution != nil && resolution.ApplyToAll {
			a.globalConflict = resolution
		}
		if resolution == nil || !resolution.Override {
			a.updateProgress(key, func(p *UploadProgress) { p.Status = StatusSkipped; p.Sent = p.Total })
			return
		}
	}

	f, err := os.Open(item.Path)
	if err != nil {
		a.failItem(item, info, err.Error())
		return
	}
	uploadErr := a.client.UploadFile(info, f, func(sent int64) {
		a.updateProgress(key, func(p *UploadProgress) { p.Sent = sent })
	})
	f.Close()

	if uploadErr != nil && isConnectionError(uploadErr) {
		log.Printf("connection lost uploading %s; reconnecting and retrying once", info.Filename)
		if rerr := a.client.Connect(); rerr == nil {
			f2, oerr := os.Open(item.Path)
			if oerr == nil {
				uploadErr = a.client.UploadFile(info, f2, func(sent int64) {
					a.updateProgress(key, func(p *UploadProgress) { p.Sent = sent })
				})
				f2.Close()
			} else {
				uploadErr = oerr
			}
		}
	}

	if uploadErr != nil {
		a.failItem(item, info, uploadErr.Error())
		return
	}

	a.updateProgress(key, func(p *UploadProgress) { p.Status = StatusCompleted; p.Sent = p.Total })
	*completedItems = append(*completedItems, item)
}

func (a *App) failItem(item FileItem, info FileInfo, msg string) {
	a.updateProgress(item.Key(), func(p *UploadProgress) {
		p.Status = StatusFailed
		p.ErrMessage = msg
		if info.Filename != "" {
			p.Info = info
		}
	})
}

// reconcileFailedSyncs updates the persisted failed-syncs list from this run's
// outcomes: successes/skips clear an item, failures add/update it — mirrors
// SyncViewModel.reconcileFailedSyncs (Android).
func (a *App) reconcileFailedSyncs(items []FileItem) {
	a.progressMu.Lock()
	resolved := append([]FailedSyncEntry(nil), a.failedSyncs...)
	for _, item := range items {
		idx, ok := a.progressIndex[item.Key()]
		if !ok {
			continue
		}
		p := a.progress[idx]
		resolved = removeFailedByPath(resolved, item.Path)
		if p.Status == StatusFailed {
			name := p.Item.DisplayName()
			if p.Info.Filename != "" {
				name = p.Info.Filename
			}
			resolved = append(resolved, FailedSyncEntry{
				Path:         item.Path,
				RelativePath: item.RelativePath,
				DisplayName:  name,
				ErrorMessage: p.ErrMessage,
				Timestamp:    time.Now(),
			})
		}
	}
	a.failedSyncs = resolved
	a.progressMu.Unlock()
	saveFailedSyncs(resolved)
}

func removeFailedByPath(entries []FailedSyncEntry, path string) []FailedSyncEntry {
	out := entries[:0:0]
	for _, e := range entries {
		if e.Path != path {
			out = append(out, e)
		}
	}
	return out
}
