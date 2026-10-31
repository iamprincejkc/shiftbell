# Android app

Native Kotlin and Jetpack Compose, Android 13 or newer.
The alarm rules and wording are the same as on Windows, ported to `android/core` (plain JVM, unit tested).

## Build and install

```bash
cd android && ./gradlew :core:test :app:lintDebug :app:assembleRelease
```

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

Lint warnings fail the build.
The release APK is signed with the local debug key, so it installs directly but is not meant for a store.

## Why it rings on a silent phone

| Risk | What the app does |
|---|---|
| Silent or vibrate mode | The tone plays as an alarm sound on the alarm volume, which the ringer mode never mutes; vibration uses alarm usage too |
| Do Not Disturb | Alarm sounds pass unless "Alarms" was turned off in Do Not Disturb |
| Doze or a sleeping phone | `AlarmManager.setAlarmClock`, the same call the Clock app uses: exact, and wakes the phone |
| Phone off at alarm time | On boot, even before the first unlock, a missed alarm inside the catch-up window rings |
| Swiped away | A foreground service rings until you type the phrase; its notification cannot be cleared |
| Phone in use | A full-screen notification, or the whole screen with "Show over other apps" allowed |

The tone starts at 15% of the alarm volume and builds to 100% over a minute.
The first screen lists every permission the alarm needs and opens the right settings page for each.

## Screenshots

The README images are rendered on the JVM with Robolectric and Roborazzi, no device needed:

```bash
cd android && ./gradlew :app:recordRoborazziDebug
```
