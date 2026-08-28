package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

func baseName(path string) string {
	return filepath.Base(path)
}

func hostName() (string, error) {
	h, err := os.Hostname()
	if err != nil || h == "" {
		return "windows-pc", nil
	}
	return h, nil
}

var (
	invalidFilenameChars = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f]`)
	reservedDeviceName   = regexp.MustCompile(`(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$`)
)

// sanitizeFilename mirrors SyncClient.sanitizeFilename (Android) so both clients
// produce identical storage paths on the server for the same source filename.
func sanitizeFilename(name string) string {
	base := name
	if i := strings.LastIndexAny(base, "/\\"); i >= 0 {
		base = base[i+1:]
	}
	base = invalidFilenameChars.ReplaceAllString(base, "_")
	base = strings.TrimSpace(base)
	base = strings.TrimRight(base, ".")
	if base == "" {
		base = "file"
	}
	stem := base
	if i := strings.LastIndex(base, "."); i >= 0 {
		stem = base[:i]
	}
	if reservedDeviceName.MatchString(stem) {
		base = "_" + base
	}
	if len(base) > 200 {
		base = base[:200]
	}
	return base
}

// sanitizeRelativePath mirrors SyncClient.sanitizeRelativePath (Android).
func sanitizeRelativePath(path string) string {
	path = strings.ReplaceAll(path, "\\", "/")
	parts := strings.Split(path, "/")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" || p == "." || p == ".." {
			continue
		}
		out = append(out, sanitizeFilename(p))
	}
	return strings.Join(out, "/")
}
