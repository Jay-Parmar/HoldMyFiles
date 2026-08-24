# Android MVP session handoff

This document preserves the current implementation state and gives the next session a safe starting point.

## Branch and scope

- Working branch: `codex/android-mvp`
- Original base: `origin/epic/share-configuration`
- Product scope: Android-first, local-only, read-only file sharing over an existing Wi-Fi or hotspot network
- Debug package: `dev.jay.holdmyfiles`, version `0.1.0`
- Minimum Android version: Android 8.0, API 26

The MVP is implemented as a runnable vertical slice. The owner selects folders with Android's system picker, starts a foreground sharing service, and gives a local URL and short-lived PIN to guests. Guests use a browser to authenticate, browse enabled shares, and download files. Stopping sharing closes the server and invalidates the run's sessions and handles.

## Implemented components

### Android application

- `HoldMyFilesApplication` composes the repositories, storage gateway, server factory, service controller, and owner UI dependencies.
- `MainActivity` requests required runtime permissions, launches the Storage Access Framework tree picker, and renders the Compose owner screen.
- The manifest declares local-network access, foreground-service permissions, a non-exported connected-device service, and disabled backup behavior.

### Share configuration and storage

- `DataStoreShareRepository` persists selected tree URIs, labels, enabled state, and stable share IDs.
- `SelectedFolderRegistrar` takes persisted read grants from the system picker and sanitizes display labels.
- `SafReadOnlyStorageGateway` discovers descendants from enabled roots, rejects virtual documents, bounds listings and concurrent streams, and exposes cancellable read leases.
- Guest requests never provide absolute paths, raw content URIs, or document IDs to the storage layer.

### Server and foreground service

- `SharingService` owns the complete Ktor server lifetime and its notification.
- `SharingController` sends idempotent start and stop commands while `SharingStateStore` publishes immutable state to the UI.
- `LocalIpv4Resolver` selects a reachable private IPv4 address from an active Wi-Fi or hotspot interface.
- Every run receives a fresh PIN, session registry, and opaque handle registry.
- Share configuration changes invalidate existing guest authorization.
- Stop closes the listener, cancels active work, and invalidates sessions and handles.

### Owner and guest experiences

- The Compose owner screen supports adding, enabling, disabling, and removing folders.
- It shows start and stop controls, actionable errors, and the current URL, port, and PIN only while sharing is active.
- The existing TypeScript guest client is bundled into the APK and serves login, browsing, breadcrumbs, downloads, and expired-session recovery.

## Security invariants to preserve

- Treat every network client as untrusted.
- Access content only through persisted Storage Access Framework tree grants.
- Keep sharing read-only.
- Use authenticated opaque handles at every guest-facing storage boundary.
- Check that a share remains enabled on every listing and download request.
- Stream file content instead of buffering complete files.
- Keep server ownership in the foreground service, not the Activity.
- On stop, close sockets, cancel streams, and invalidate all run-scoped authorization.
- Never log or persist PINs, session tokens, signing material, or guest-facing handles.

## Verification evidence

The final pre-commit gate ran on 2026-08-24:

- 121 core JVM tests passed with no failures or errors
- 23 Android app JVM tests passed with no failures or errors
- Android lint passed with warnings treated as errors
- debug APK assembly passed
- TypeScript type checking passed
- 18 guest web tests passed
- the production guest web build passed and refreshed the bundled APK assets

The full emulator acceptance path was completed on 2026-08-21 using an API 35 emulator:

- two Android instrumentation tests passed
- folder selection produced a persisted read grant
- an incorrect PIN was rejected and the current PIN authenticated successfully
- directory listing and an exact 32-byte test-file download succeeded
- stopping sharing closed the listener
- restarting used a fresh port and PIN
- the previous run's session was rejected

No physical Android device was connected during that acceptance run. A real two-device Wi-Fi or hotspot test is still required.

## Build and test commands

Install JDK 17, Android SDK Platform 37, Android SDK Build Tools 36.0.0 or newer, Android Platform Tools, and Node.js 24. Configure the normal Android SDK environment or use Android Studio.

Run the Android gate from the repository root:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Run device tests when an emulator or device is connected:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Run the guest client gate:

```powershell
cd web
npm ci
npm run check
```

The generated debug APK is `app\build\outputs\apk\debug\app-debug.apk`. Its current SHA-256 is `58CC237FC9F47765A6B8E2CDC209E9D55FB2637B56B15DF634E01AE6A4C8CDB7`. APKs and build output are intentionally ignored by Git, so rebuild the APK after cloning on another computer.

## Remaining work

1. Run the complete guest flow on a physical Android host and a second device over both Wi-Fi and hotspot networks.
2. Add CI gates for Android JVM tests, lint, and debug APK assembly.
3. Harden large-file streaming, client disconnects, service death, storage removal, and network-change behavior.
4. Add release signing and versioning instructions, then produce a release candidate.
5. Test the acceptance matrix on at least two Android vendors and two guest platforms.

Plain HTTP is intentional for this local-network MVP. Use non-sensitive files during testing and keep the warning visible. App-created hotspots, uploads, and WebDAV remain outside the MVP.

## Continue on another computer

After the branch has been pushed, use:

```powershell
git fetch origin
git switch codex/android-mvp
git pull --ff-only
```

Copy and paste this prompt into the next coding session:

```text
Continue work on Hold My Files from branch codex/android-mvp. First read AGENTS.md and docs/session-handoff.md, then verify git status and inspect the latest commits without rewriting published history. Run the documented Android and web gates before changing behavior. The Android MVP is implemented and emulator-verified. Prioritize physical two-device acceptance, Android CI, and release hardening. Preserve SAF-only read-only access, opaque guest handles, foreground-service ownership, per-run PIN and session invalidation, and the rule that secrets and local machine paths never enter Git. Keep commits small and use imperative Conventional Commit subjects. Do not push unless I ask.
```
