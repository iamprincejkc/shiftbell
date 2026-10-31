# Windows app

A tray app (.NET 10, WPF) that rings with its own full-screen window instead of a notification.

## Install

Run `Shiftbell-Setup-<version>.exe` from the [latest release](https://github.com/iamprincejkc/shiftbell/releases/latest).
It installs per user to `%LOCALAPPDATA%\Programs\Shiftbell` (no admin), adds a Start menu shortcut, and can be uninstalled from Settings, Apps.
On first start the app opens Settings and registers a Run key and a `Shiftbell` scheduled task; uninstalling removes both and keeps your settings.
Silent installs work (`/VERYSILENT /SUPPRESSMSGBOXES`), and upgrading over a running copy restarts it.

From source, either build the release files or install directly:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
```

```powershell
powershell -ExecutionPolicy Bypass -File tools\install.ps1
```

`build-release.ps1` runs both test suites and writes the installer and the APK to `dist\`; it needs [Inno Setup 6](https://jrsoftware.org/isinfo.php).
`install.ps1` publishes and installs to the same folder without an installer; `tools\uninstall.ps1` undoes it.

## Why it does not get missed

| A notification alarm fails when | Shiftbell instead |
|---|---|
| Do Not Disturb, Focus or a full-screen game hides it | Draws its own top-most window on every monitor, re-asserted every second |
| It slides into the Action Center | Stays until you confirm; Alt+F4, Win+D and closing from outside are refused |
| The PC is muted | Lifts the speakers to a floor (default 25%) only if they are lower, then puts them back |
| The PC is asleep | Holds off idle sleep 10 minutes before an alarm; the scheduled task wakes the PC 2 minutes before |
| The PC was off | Rings a missed alarm when you are back, up to 4 hours late, marked late |
| The app was closed | Starts at sign-in; the scheduled task restarts it within 15 minutes |

The tone starts soft and builds over the first minute.
Wake from sleep needs "Allow wake timers" in the power plan (on by default when plugged in).

## Command line

| Command | Does |
|---|---|
| `Shiftbell.exe` | Starts it, or opens Settings in the running copy |
| `--background` | Starts quietly, or asks the running copy to check for due alarms |
| `--test-in`, `--test-out` | Rings a test alarm now; it is never recorded |
| `--exit` | Closes the running copy |
| `--unregister` | Removes the Run key and the scheduled task |
| `--render-previews <folder>` | Draws every alarm state to PNG without showing a window |

## Data

`%APPDATA%\Shiftbell\settings.json` and `state.json` (90 days of punches), written atomically.
A damaged file is kept aside as `.corrupt` and defaults are used.

## Trial runs

Set `SHIFTBELL_HOME` to a scratch folder: settings and state go there and the startup task is not touched.
`SHIFTBELL_SYNC_URL` points sync at a local relay.
`dotnet test` does not rebuild the app project, so build `src\Shiftbell\Shiftbell.csproj` by name before running it.
