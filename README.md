<div align="center">

<img src="docs/assets/icon.png" width="72" alt="Shiftbell icon" />

# Shiftbell

**The time-in alarm you cannot miss and cannot dismiss unread.**

Rings before work to time in and after work to time out, on Windows and Android, and stays until you confirm you actually punched.

![Windows 10/11](https://img.shields.io/badge/Windows-10%20%7C%2011-0078D4?logo=windows&logoColor=white)
![Android 13+](https://img.shields.io/badge/Android-13%2B-3DDC84?logo=android&logoColor=white)
![.NET 10](https://img.shields.io/badge/.NET-10-512BD4?logo=dotnet&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-lightgrey)

<img src="docs/screenshots/pc/alarm-time-in.png" width="860" alt="Time-in alarm covering the Windows desktop" />

</div>

## Why

Most alarms ring through a notification, and a notification is easy to lose.
Do Not Disturb, a full-screen game, a muted PC or a phone on silent each swallow it, and a quick swipe dismisses it without a thought.
Shiftbell takes over the screen instead, and only goes away when you type **done**.

## Highlights

| | Windows | Android |
|---|---|---|
| Rings through Do Not Disturb, Focus and full-screen apps | ✓ | ✓ |
| Rings on a muted PC or a silent phone | ✓ | ✓ |
| Wakes a sleeping device for the alarm | ✓ | ✓ |
| Rings a missed alarm late after the device was off | ✓ | ✓ |
| Type **done** to stop it; snooze is limited | ✓ | ✓ |
| "Let me do it now": steps aside for 3 minutes, then returns | ✓ | ✓ |
| One-click link to your attendance page | ✓ | ✓ |
| Starts soft and builds up, never blasts your ears | ✓ | ✓ |
| Confirm on one device, every paired device stops | ✓ | ✓ |

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/phone/alarm-time-in.png" width="230" alt="Phone time-in alarm" /><br /><sub>Time in</sub></td>
    <td align="center"><img src="docs/screenshots/phone/alarm-late.png" width="230" alt="Phone late alarm" /><br /><sub>Late</sub></td>
    <td align="center"><img src="docs/screenshots/phone/alarm-time-out.png" width="230" alt="Phone time-out alarm" /><br /><sub>Time out</sub></td>
    <td align="center"><img src="docs/screenshots/phone/home.png" width="230" alt="Phone home screen" /><br /><sub>Home and setup</sub></td>
  </tr>
</table>

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/pc/alarm-time-out.png" width="560" alt="Windows time-out alarm" /><br /><sub>Time out on Windows</sub></td>
    <td align="center"><img src="docs/screenshots/pc/settings.png" width="250" alt="Windows settings" /><br /><sub>Settings</sub></td>
  </tr>
</table>

## How it works

```mermaid
flowchart LR
    C[Wall clock] --> P[AlarmPlanner]
    S[(Saved state)] --> P
    P -->|due now| R[Full-screen alarm]
    R -->|type done| A[Record punch]
    A --> S
    A -->|encrypted event| X[(Sync relay)]
    X -->|merge| O[Other devices stop]
```

- **Decided from state, not timers.** Every second (Windows) or at each system alarm (Android), a pure planner reads the saved state and the clock, so sleep, reboots and clock changes cannot lose an alarm.
- **Same rules everywhere.** The planner and every word on the alarm live in a tested core, mirrored in C# and Kotlin.
- **Sync is end-to-end encrypted.** Devices pair by QR code and derive the keys locally; the relay stores ciphertext it cannot read. No network never stops an alarm from ringing. See [docs/sync.md](docs/sync.md).

## Getting started

| Platform | Install | Details |
|---|---|---|
| Windows 10/11 | `powershell -ExecutionPolicy Bypass -File tools\install.ps1` | [docs/windows.md](docs/windows.md) |
| Android 13+ | `cd android && ./gradlew :app:assembleRelease`, then install the APK | [docs/android.md](docs/android.md) |

Default schedule: time in rings 7:45 AM (due 8:00), time out rings 5:00 PM, Monday to Friday.
Set your attendance page and pair your devices in Settings.

The apps sync through a small relay endpoint (`/api/shiftbell/{channel}`, two routes on a Nuxt site).
To run your own, point the Windows app at it with `SHIFTBELL_SYNC_URL` and change `RELAY` in `android/app/.../PhoneSync.kt`.

## Repository

| Path | Contents |
|---|---|
| `src/Shiftbell.Core` | Planner, alarm wording, settings, state, sync protocol (no UI) |
| `src/Shiftbell` | Windows app: tray, alarm windows, settings, startup task |
| `tests/` | xUnit tests for the core |
| `android/core` | The same core in Kotlin, with JUnit tests |
| `android/app` | Android app: alarm service, alarm screen, settings |
| `docs/` | Platform guides, sync protocol, decisions |
| `tools/` | Install, uninstall and icon scripts |

## Development

```bash
dotnet test Shiftbell.slnx
```

```bash
cd android && ./gradlew :core:test :app:lintDebug
```

Warnings are errors on both platforms.
Both apps check their encryption against the same independent test vectors in [docs/sync.md](docs/sync.md), byte for byte.

## License

[MIT](LICENSE)
