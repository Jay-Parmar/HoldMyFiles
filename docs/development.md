# Development setup

## Required tools

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0 or newer
- Android SDK Platform Tools
- Node.js 24
- A physical Android 8.0 or newer device for network acceptance tests

Android Studio is optional if the command-line tools are configured.

## Local files

The following stay outside Git:

- `local.properties`
- Android signing keys
- GitHub credentials
- generated APKs and build output
- local tool downloads

## Main checks

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

The full local gate is:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Run the DataStore instrumentation tests on a connected device or emulator with:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The guest client gate is:

```powershell
cd web
npm ci
npm run check
```

The web build refreshes the fixed assets under `app/src/main/assets/web`. Commit those generated files with their TypeScript source.

## Physical device loop

1. Enable developer options and USB debugging on the Android device.
2. Install `app\build\outputs\apk\debug\app-debug.apk` with `adb install -r`.
3. Select a disposable test folder.
4. Start a hotspot and connect a second device.
5. Run the acceptance path in `architecture.md`.

Use non-sensitive files during development because the MVP serves plain HTTP on the local network.
