package main

import (
	"fmt"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"
)

func statusText(s UploadStatus) string {
	switch s {
	case StatusPending:
		return "Pending"
	case StatusUploading:
		return "Uploading…"
	case StatusCompleted:
		return "✓ Completed"
	case StatusFailed:
		return "✗ Failed"
	case StatusSkipped:
		return "⊘ Skipped"
	case StatusDuplicate:
		return "⊙ Already synced"
	default:
		return ""
	}
}

func (a *App) showProgressScreen() {
	title := widget.NewLabelWithStyle("Syncing files…", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})

	// Row: [name+status(center), percent(right)] — see container.NewBorder's
	// Objects ordering note in ui_picker.go.
	a.progressList = widget.NewList(
		func() int {
			a.progressMu.Lock()
			defer a.progressMu.Unlock()
			return len(a.progress)
		},
		func() fyne.CanvasObject {
			name := widget.NewLabel("")
			name.Truncation = fyne.TextTruncateEllipsis
			status := widget.NewLabel("")
			pct := widget.NewLabel("")
			left := container.NewVBox(name, status)
			return container.NewBorder(nil, nil, nil, pct, left)
		},
		func(id widget.ListItemID, obj fyne.CanvasObject) {
			a.progressMu.Lock()
			p := a.progress[id]
			a.progressMu.Unlock()

			row := obj.(*fyne.Container)
			left := row.Objects[0].(*fyne.Container)
			pct := row.Objects[1].(*widget.Label)
			name := left.Objects[0].(*widget.Label)
			status := left.Objects[1].(*widget.Label)

			label := p.Item.DisplayName()
			if p.Info.Filename != "" {
				label = p.Info.Filename
			}
			name.SetText(label)
			txt := statusText(p.Status)
			if p.Status == StatusFailed && p.ErrMessage != "" {
				txt = txt + ": " + p.ErrMessage
			}
			status.SetText(txt)
			if p.Status == StatusUploading || p.Status == StatusCompleted {
				pct.SetText(fmt.Sprintf("%d%%", p.Percentage()))
			} else {
				pct.SetText("")
			}
		},
	)

	a.setBody(container.NewBorder(title, nil, nil, nil, a.progressList))
}

func (a *App) refreshProgressList() {
	if a.progressList != nil {
		a.progressList.Refresh()
	}
}

// askConflict blocks the calling (background) goroutine until the user answers
// the duplicate-file dialog, showing it on the UI thread via fyne.Do — mirrors
// SyncViewModel.handleConflict (Android)'s suspend-until-answered pattern.
func (a *App) askConflict(info FileInfo) *ConflictResolution {
	respCh := make(chan *ConflictResolution, 1)
	fyne.Do(func() {
		applyAll := widget.NewCheck("Apply to all remaining duplicates", nil)
		content := container.NewVBox(
			widget.NewLabel(fmt.Sprintf("'%s' already exists. Override?", info.Filename)),
			applyAll,
		)
		// The dialog hides itself as part of the confirm/dismiss action that
		// triggers this callback (see ConfirmDialog.hideWithResponse) — no
		// need to hide it again here.
		d := dialog.NewCustomConfirm("Duplicate File", "Override", "Skip", content,
			func(override bool) {
				respCh <- &ConflictResolution{Override: override, ApplyToAll: applyAll.Checked}
			}, a.window)
		d.Show()
	})
	return <-respCh
}
