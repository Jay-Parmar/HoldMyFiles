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
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

The full local gate is:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
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
2. Install the debug APK with `adb install -r`.
3. Select a disposable test folder.
4. Start a hotspot and connect a second device.
5. Run the acceptance path in `architecture.md`.

Use non-sensitive files during development because the MVP serves plain HTTP on the local network.
