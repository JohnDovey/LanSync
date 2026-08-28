package main

import (
	"fmt"
	"os"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

func (a *App) showCompleteScreen(completedItems []FileItem) {
	a.progressMu.Lock()
	var completed, failed, skipped, duplicate int
	for _, p := range a.progress {
		switch p.Status {
		case StatusCompleted:
			completed++
		case StatusFailed:
			failed++
		case StatusSkipped:
			skipped++
		case StatusDuplicate:
			duplicate++
		}
	}
	a.progressMu.Unlock()

	a.deletableItems = completedItems

	icon := widget.NewIcon(theme.ConfirmIcon())
	title := widget.NewLabelWithStyle("Sync Complete", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	summaryText := fmt.Sprintf("Completed: %d\nFailed: %d\nSkipped: %d", completed, failed, skipped)
	if duplicate > 0 {
		summaryText += fmt.Sprintf("\nAlready synced: %d", duplicate)
	}
	summary := widget.NewLabel(summaryText)

	content := container.NewVBox(icon, title, summary)

	if len(completedItems) > 0 {
		deleteResult := widget.NewLabel("")
		deleteBtn := widget.NewButton(fmt.Sprintf("Delete %d synced file(s)", len(completedItems)), nil)
		keepBtn := widget.NewButton("Keep files on this computer", func() {
			deleteBtn.Hide()
		})
		deleteBtn.OnTapped = func() {
			dialog.ShowConfirm("Delete from this computer?",
				fmt.Sprintf("%d file(s) were uploaded successfully. Delete them from this computer to free space? Files already on the server will not be affected.", len(completedItems)),
				func(ok bool) {
					if !ok {
						return
					}
					deleteBtn.Hide()
					keepBtn.Hide()
					deleted, failedDel := 0, 0
					for _, it := range completedItems {
						if err := os.Remove(it.Path); err != nil {
							failedDel++
						} else {
							deleted++
						}
					}
					go func() { _ = a.client.ConfirmDelete() }()
					if failedDel == 0 {
						deleteResult.SetText(fmt.Sprintf("Deleted %d file(s) from this computer", deleted))
					} else {
						deleteResult.SetText(fmt.Sprintf("Deleted %d; %d could not be removed", deleted, failedDel))
					}
				}, a.window)
		}
		content.Add(deleteBtn)
		content.Add(keepBtn)
		content.Add(deleteResult)
	}

	syncMoreBtn := widget.NewButton("Sync More Files", func() { a.showPickerScreen() })
	syncMoreBtn.Importance = widget.HighImportance
	historyBtn := widget.NewButton("View History", func() { a.showHistoryPanel(content) })
	disconnectBtn := widget.NewButton("Disconnect", func() {
		a.client.Close()
		a.showConnectScreen()
	})

	content.Add(syncMoreBtn)
	content.Add(historyBtn)
	content.Add(disconnectBtn)

	a.setBody(container.NewVScroll(container.NewPadded(content)))
}

// showHistoryPanel fetches server history and appends it (plus the persisted
// Failed Syncs section) to the complete screen — mirrors the Android
// View History toggle.
func (a *App) showHistoryPanel(content *fyne.Container) {
	if a.historyOpen {
		return
	}
	a.historyOpen = true

	if len(a.failedSyncs) > 0 {
		content.Add(a.buildFailedSyncsSection())
	}

	historyLabel := widget.NewLabel("Loading history…")
	content.Add(widget.NewSeparator())
	content.Add(widget.NewLabelWithStyle("Sync History", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}))
	content.Add(historyLabel)

	go func() {
		history, err := a.client.SyncHistory()
		fyne.Do(func() {
			if err != nil {
				historyLabel.SetText("Could not load history: " + err.Error())
				return
			}
			if len(history) == 0 {
				historyLabel.SetText("No history available")
				return
			}
			text := ""
			for _, h := range history {
				text += h + "\n"
			}
			historyLabel.SetText(text)
		})
	}()
}

// buildFailedSyncsSection renders the persisted failed-uploads list with
// select-all + batch Retry/Remove — mirrors FailedSyncsSection.kt (Android).
func (a *App) buildFailedSyncsSection() fyne.CanvasObject {
	checked := map[string]bool{}

	var list *widget.List
	var selectAllChk *widget.Check
	var retryBtn, removeBtn *widget.Button

	refresh := func() {
		n := 0
		for _, v := range checked {
			if v {
				n++
			}
		}
		retryBtn.SetText(fmt.Sprintf("Retry (%d)", n))
		removeBtn.SetText(fmt.Sprintf("Remove (%d)", n))
		enable := n > 0
		if enable {
			retryBtn.Enable()
			removeBtn.Enable()
		} else {
			retryBtn.Disable()
			removeBtn.Disable()
		}
		selectAllChk.SetChecked(len(a.failedSyncs) > 0 && n == len(a.failedSyncs))
	}

	list = widget.NewList(
		func() int { return len(a.failedSyncs) },
		func() fyne.CanvasObject {
			lbl := widget.NewLabel("")
			lbl.Truncation = fyne.TextTruncateEllipsis
			sub := widget.NewLabel("")
			chk := widget.NewCheck("", nil)
			center := container.NewVBox(lbl, sub)
			return container.NewBorder(nil, nil, chk, nil, center)
		},
		func(id widget.ListItemID, obj fyne.CanvasObject) {
			e := a.failedSyncs[id]
			row := obj.(*fyne.Container)
			center := row.Objects[0].(*fyne.Container)
			chk := row.Objects[1].(*widget.Check)
			lbl := center.Objects[0].(*widget.Label)
			sub := center.Objects[1].(*widget.Label)

			lbl.SetText(e.DisplayName)
			sub.SetText(e.ErrorMessage)
			chk.OnChanged = nil
			chk.SetChecked(checked[e.Path])
			chk.OnChanged = func(v bool) {
				checked[e.Path] = v
				refresh()
			}
		},
	)
	list.Resize(fyne.NewSize(400, 160))

	selectAllChk = widget.NewCheck("Select all", func(v bool) {
		checked = map[string]bool{}
		if v {
			for _, e := range a.failedSyncs {
				checked[e.Path] = true
			}
		}
		list.Refresh()
		refresh()
	})

	retryBtn = widget.NewButton("Retry", func() {
		var items []FileItem
		var keep []FailedSyncEntry
		for _, e := range a.failedSyncs {
			if checked[e.Path] {
				items = append(items, e.toItem())
			} else {
				keep = append(keep, e)
			}
		}
		a.failedSyncs = keep
		saveFailedSyncs(keep)
		if len(items) > 0 {
			go a.syncFiles(items)
		}
	})
	removeBtn = widget.NewButton("Remove", func() {
		var keep []FailedSyncEntry
		for _, e := range a.failedSyncs {
			if !checked[e.Path] {
				keep = append(keep, e)
			}
		}
		a.failedSyncs = keep
		saveFailedSyncs(keep)
		checked = map[string]bool{}
		list.Refresh()
		refresh()
	})
	retryBtn.Disable()
	removeBtn.Disable()

	header := widget.NewLabelWithStyle(fmt.Sprintf("Failed Syncs (%d)", len(a.failedSyncs)), fyne.TextAlignLeading, fyne.TextStyle{Bold: true})

	return container.NewVBox(
		header,
		selectAllChk,
		container.New(&fixedHeight{h: 160}, list),
		container.NewHBox(retryBtn, removeBtn),
	)
}

// fixedHeight is a tiny layout that forces its single child to a fixed height
// while filling available width, so the failed-syncs list doesn't try to take
// unbounded space inside the scrolling complete screen.
type fixedHeight struct{ h float32 }

func (f *fixedHeight) MinSize(objects []fyne.CanvasObject) fyne.Size {
	return fyne.NewSize(0, f.h)
}

func (f *fixedHeight) Layout(objects []fyne.CanvasObject, size fyne.Size) {
	for _, o := range objects {
		o.Resize(fyne.NewSize(size.Width, f.h))
	}
}
