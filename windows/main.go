package main

import (
	"image/color"
	"sync"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"
)

// brandPurple matches the Android app's header bar (SyncScreen.kt AppHeaderBar,
// 0xFF673AB7).
var brandPurple = color.NRGBA{R: 0x67, G: 0x3A, B: 0xB7, A: 0xFF}

// App holds all mutable client state and the single window's screen content.
// Fyne UI mutations from background goroutines must go through fyne.Do/DoAndWait.
type App struct {
	fyneApp fyne.App
	window  fyne.Window

	client   *SyncClient
	settings Settings

	body       *fyne.Container // swappable screen content (below the header)
	header     *fyne.Container
	titleLabel *widget.Label

	selectedItems []FileItem
	checkedItems  map[string]bool // FileItem.Key() -> checked, for the picker's select-all/remove

	progress       []UploadProgress
	progressIndex  map[string]int
	progressList   *widget.List
	progressMu     sync.Mutex
	globalConflict *ConflictResolution

	failedSyncs []FailedSyncEntry

	deletableItems []FileItem // successfully-synced items eligible for post-sync local delete

	showingAbout bool
	historyOpen  bool
}

func main() {
	a := &App{
		fyneApp:      app.NewWithID("com.lansync.windows"),
		client:       &SyncClient{},
		settings:     loadSettings(),
		checkedItems: map[string]bool{},
		failedSyncs:  loadFailedSyncs(),
	}

	a.window = a.fyneApp.NewWindow("LanSync")
	a.window.Resize(fyne.NewSize(720, 640))

	a.header = a.buildHeader()
	a.body = container.NewStack()
	root := container.NewBorder(a.header, nil, nil, nil, a.body)
	a.window.SetContent(root)

	a.showConnectScreen()

	a.window.ShowAndRun()
}

// setBody swaps the main content area for a new screen.
func (a *App) setBody(content fyne.CanvasObject) {
	a.body.RemoveAll()
	a.body.Add(content)
	a.body.Refresh()
}
