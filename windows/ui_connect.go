package main

import (
	"fmt"
	"strconv"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/widget"
)

func (a *App) showConnectScreen() {
	title := widget.NewLabelWithStyle("LanSync", fyne.TextAlignCenter, fyne.TextStyle{Bold: true})
	title.TextStyle.Bold = true

	hostEntry := widget.NewEntry()
	hostEntry.SetText(a.settings.Host)
	portEntry := widget.NewEntry()
	portEntry.SetText(strconv.Itoa(a.settings.Port))
	userEntry := widget.NewEntry()
	userEntry.SetText(a.settings.Username)
	deviceEntry := widget.NewEntry()
	deviceEntry.SetText(a.settings.DeviceName)

	form := widget.NewForm(
		widget.NewFormItem("Server Host", hostEntry),
		widget.NewFormItem("Server Port", portEntry),
		widget.NewFormItem("Username", userEntry),
		widget.NewFormItem("Device Name", deviceEntry),
	)

	status := widget.NewLabel("")
	var connectBtn *widget.Button
	connectBtn = widget.NewButton("Connect", func() {
		port, err := strconv.Atoi(portEntry.Text)
		if err != nil || port <= 0 {
			port = 10101
		}
		s := Settings{Host: hostEntry.Text, Port: port, Username: userEntry.Text, DeviceName: deviceEntry.Text}
		a.settings = s
		saveSettings(s)

		connectBtn.Disable()
		status.SetText("Connecting…")
		a.client.Host = s.Host
		a.client.Port = s.Port
		a.client.Username = s.Username
		a.client.DeviceName = s.DeviceName
		a.client.AppVersion = AppVersion

		go func() {
			err := a.client.Connect()
			fyne.Do(func() {
				connectBtn.Enable()
				if err != nil {
					status.SetText("")
					dialog.ShowError(fmt.Errorf("connection failed: %w", err), a.window)
					return
				}
				a.showPickerScreen()
			})
		}()
	})
	connectBtn.Importance = widget.HighImportance

	content := container.NewVBox(
		title,
		widget.NewSeparator(),
		form,
		connectBtn,
		status,
	)
	padded := container.NewPadded(content)
	a.setBody(container.NewCenter(container.NewGridWrap(fyne.NewSize(380, 280), padded)))
}
