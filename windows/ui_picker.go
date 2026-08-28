package main

import (
	"fmt"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"github.com/ncruces/zenity"
)

func (a *App) showPickerScreen() {
	a.selectedItems = nil
	a.checkedItems = map[string]bool{}
	a.deletableItems = nil

	title := widget.NewLabelWithStyle("Select Files to Sync", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})

	var list *widget.List
	var selectAllChk *widget.Check
	var removeBtn *widget.Button
	var startBtn *widget.Button
	statusLabel := widget.NewLabel("")
	statusLabel.Wrapping = fyne.TextWrapWord

	itemKey := func(it FileItem) string { return it.Key() }

	refreshCount := func() {
		n := len(a.selectedItems)
		if n == 0 {
			startBtn.SetText("Select files below, then sync")
			startBtn.Disable()
		} else {
			startBtn.SetText(fmt.Sprintf("Start Sync (%d)", n))
			startBtn.Enable()
		}
		checked := 0
		for _, v := range a.checkedItems {
			if v {
				checked++
			}
		}
		if checked > 0 {
			removeBtn.SetText(fmt.Sprintf("Remove (%d)", checked))
			removeBtn.Show()
		} else {
			removeBtn.Hide()
		}
		selectAllChk.SetChecked(n > 0 && checked == n)
	}

	addItems := func(items []FileItem) int {
		existing := map[string]bool{}
		for _, it := range a.selectedItems {
			existing[itemKey(it)] = true
		}
		added := 0
		for _, it := range items {
			k := itemKey(it)
			if !existing[k] {
				a.selectedItems = append(a.selectedItems, it)
				existing[k] = true
				added++
			}
		}
		list.Refresh()
		refreshCount()
		return added
	}

	// --- row template: [label(center), checkbox(left), remove(right)] per
	// container.NewBorder's Objects ordering (center objects first, then
	// left/right) — see NewBorder source in fyne/container/layouts.go.
	list = widget.NewList(
		func() int { return len(a.selectedItems) },
		func() fyne.CanvasObject {
			lbl := widget.NewLabel("")
			lbl.Truncation = fyne.TextTruncateEllipsis
			chk := widget.NewCheck("", nil)
			rm := widget.NewButtonWithIcon("", theme.DeleteIcon(), nil)
			return container.NewBorder(nil, nil, chk, rm, lbl)
		},
		func(id widget.ListItemID, obj fyne.CanvasObject) {
			row := obj.(*fyne.Container)
			lbl := row.Objects[0].(*widget.Label)
			chk := row.Objects[1].(*widget.Check)
			rm := row.Objects[2].(*widget.Button)

			item := a.selectedItems[id]
			key := itemKey(item)
			lbl.SetText(item.DisplayName())
			chk.OnChanged = nil
			chk.SetChecked(a.checkedItems[key])
			chk.OnChanged = func(v bool) {
				if v {
					a.checkedItems[key] = true
				} else {
					delete(a.checkedItems, key)
				}
				refreshCount()
			}
			rm.OnTapped = func() {
				a.selectedItems = append(a.selectedItems[:id], a.selectedItems[id+1:]...)
				delete(a.checkedItems, key)
				list.Refresh()
				refreshCount()
			}
		},
	)

	selectAllChk = widget.NewCheck("Select all", func(v bool) {
		a.checkedItems = map[string]bool{}
		if v {
			for _, it := range a.selectedItems {
				a.checkedItems[itemKey(it)] = true
			}
		}
		list.Refresh()
		refreshCount()
	})

	removeBtn = widget.NewButton("Remove", func() {
		var kept []FileItem
		for _, it := range a.selectedItems {
			if !a.checkedItems[itemKey(it)] {
				kept = append(kept, it)
			}
		}
		a.selectedItems = kept
		a.checkedItems = map[string]bool{}
		list.Refresh()
		refreshCount()
	})
	removeBtn.Hide()

	clearBtn := widget.NewButton("Clear", func() {
		a.selectedItems = nil
		a.checkedItems = map[string]bool{}
		list.Refresh()
		refreshCount()
		statusLabel.SetText("")
	})

	pickFiles := func(label string, filter zenity.FileFilter, fileType string) {
		paths, err := zenity.SelectFileMultiple(zenity.Title("Add "+label), filter)
		if err != nil {
			if err != zenity.ErrCanceled {
				statusLabel.SetText("Pick failed: " + err.Error())
			}
			return
		}
		items := make([]FileItem, 0, len(paths))
		for _, p := range paths {
			items = append(items, FileItem{Path: p})
		}
		n := addItems(items)
		if n > 0 {
			statusLabel.SetText(fmt.Sprintf("Added %d %s", n, label))
		}
		_ = fileType
	}

	photosBtn := widget.NewButtonWithIcon("Photos", theme.MediaPhotoIcon(), func() {
		pickFiles("photo(s)", zenity.FileFilter{Name: "Images", Patterns: []string{"*.jpg", "*.jpeg", "*.png", "*.gif", "*.webp", "*.bmp", "*.heic"}}, "photos")
	})
	videosBtn := widget.NewButtonWithIcon("Videos", theme.MediaVideoIcon(), func() {
		pickFiles("video(s)", zenity.FileFilter{Name: "Videos", Patterns: []string{"*.mp4", "*.mov", "*.avi", "*.mkv", "*.webm"}}, "photos")
	})
	docsBtn := widget.NewButtonWithIcon("Documents", theme.FileTextIcon(), func() {
		pickFiles("document(s)", zenity.FileFilter{Name: "Documents", Patterns: []string{"*.pdf", "*.doc", "*.docx", "*.xls", "*.xlsx", "*.txt", "*.csv"}}, "documents")
	})
	anyBtn := widget.NewButtonWithIcon("Files", theme.FileIcon(), func() {
		pickFiles("file(s)", zenity.FileFilter{Name: "All files", Patterns: []string{"*"}}, "documents")
	})
	folderBtn := widget.NewButtonWithIcon("Folder", theme.FolderIcon(), func() {
		dir, err := zenity.SelectFile(zenity.Title("Add Folder"), zenity.Directory())
		if err != nil {
			if err != zenity.ErrCanceled {
				statusLabel.SetText("Folder pick failed: " + err.Error())
			}
			return
		}
		statusLabel.SetText("Scanning folder…")
		go func() {
			items, err := walkFolder(dir)
			fyne.Do(func() {
				if err != nil {
					statusLabel.SetText("Folder scan failed: " + err.Error())
					return
				}
				if len(items) == 0 {
					statusLabel.SetText("No files found in that folder")
					return
				}
				n := addItems(items)
				root := ""
				if len(items) > 0 {
					root = strings.SplitN(items[0].RelativePath, "/", 2)[0]
				}
				statusLabel.SetText(fmt.Sprintf("Added %d file(s) from \"%s\"", n, root))
			})
		}()
	})

	addRow := container.NewHBox(photosBtn, videosBtn, docsBtn, folderBtn, anyBtn)

	startBtn = widget.NewButton("Select files below, then sync", func() {
		items := append([]FileItem(nil), a.selectedItems...)
		go a.syncFiles(items)
	})
	startBtn.Importance = widget.HighImportance
	startBtn.Disable()

	header := container.NewVBox(
		title,
		startBtn,
		widget.NewLabel("Add files"),
		addRow,
		statusLabel,
		container.NewBorder(nil, nil, nil, container.NewHBox(removeBtn, clearBtn), selectAllChk),
	)

	a.setBody(container.NewBorder(header, nil, nil, nil, list))
	refreshCount()
}
