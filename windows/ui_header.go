package main

import (
	"fmt"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"
)

func (a *App) buildHeader() *fyne.Container {
	bg := canvas.NewRectangle(brandPurple)
	title := widget.NewLabelWithStyle(fmt.Sprintf("LanSync v%s", AppVersion), fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	title.Importance = widget.HighImportance

	info := widget.NewButtonWithIcon("", theme.InfoIcon(), func() { a.showAbout() })

	bar := container.NewBorder(nil, nil, container.NewPadded(title), info)
	return container.NewStack(bg, container.NewPadded(bar))
}

func (a *App) showAbout() {
	body := widget.NewLabel(
		"LanSync copies photos, videos, and folders from this computer to a\n" +
			"LanSync server on the same local network — there is no cloud, and\n" +
			"nothing leaves your network.\n\n" +
			"A running server is required\n" +
			"This client cannot sync on its own. The LanSync server must already be\n" +
			"running on a computer on this LAN (default port 10101). Enter its IP\n" +
			"address, a username, and a device name on the connect screen.\n\n" +
			"Once connected, add photos, videos, documents, or an entire folder to\n" +
			"upload. After a successful sync you can optionally delete the synced\n" +
			"files from this computer to free up space.",
	)
	body.Wrapping = fyne.TextWrapWord
	d := dialog.NewCustom("About LanSync", "Close", container.NewPadded(body), a.window)
	d.Resize(fyne.NewSize(440, 320))
	d.Show()
}
