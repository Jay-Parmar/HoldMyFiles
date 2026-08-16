# Development roadmap

Each milestone ends in a working vertical slice. Each checkbox should normally become its own micro commit.

## 0. Repository foundation

- [x] Record repository working rules
- [x] Record the MVP architecture and security invariants
- [x] Record the test and delivery plan
- [x] Record the security test plan
- [x] Add pull request and issue templates

Exit check: a new contributor can explain the scope, request flow, and security boundary from the repository docs.

## 1. Buildable Android shell

- [x] Add Gradle wrapper and version catalog
- [x] Add the Android application module
- [ ] Add a minimal Compose screen
- [ ] Add CI for unit tests and debug APK builds

Test loop:

1. Add a smoke test for the application state holder.
2. Build a debug APK.
3. Run unit tests and Android lint.

Exit check: `gradlew testDebugUnitTest assembleDebug lintDebug` passes from a clean checkout.

## 2. Share configuration

- [ ] Define the `ShareRoot` domain model
- [ ] Add serialization tests
- [ ] Implement the DataStore repository
- [ ] Launch the system tree picker
- [ ] Persist read permission and save a selected root
- [ ] List, enable, disable, and remove saved roots

Test loop:

1. Define repository contract tests with a fake store.
2. Test duplicate URI handling and stable IDs.
3. Exercise add and remove through an instrumentation test.

Exit check: a selected folder survives Activity recreation and app restart, while unselected folders remain inaccessible.

## 3. Security core

- [x] Generate a per-run PIN
- [x] Compare PINs without logging secrets
- [x] Issue, validate, expire, and revoke sessions
- [x] Issue, expire, and revoke random in-memory node handles
- [x] Add per-client and global PIN rate limits

Test loop:

1. Write tests for invalid, modified, expired, and previous-run tokens.
2. Implement the minimum passing security primitives.
3. Run mutation-oriented cases against every token field.
4. Verify root-set changes revoke existing sessions and handles.

Exit check: no guest-controlled path or URI can enter the storage layer.

## 4. Storage gateway

- [x] Define platform-independent node models
- [x] Query a tree root and its children through the core gateway
- [x] Distinguish directories and regular files
- [x] Open file metadata and content streams through the core gateway
- [ ] Handle missing grants and removed storage
- [x] Bound concurrent open streams

Test loop:

1. Contract-test server behavior with a fake gateway.
2. Instrument the real gateway against a test document provider.
3. Test unusual names, empty files, and disappearing documents.

Exit check: only descendants discovered through an enabled selected root can receive a handle.

## 5. Embedded HTTP server

- [x] Add Ktor application wiring
- [x] Add readiness and static asset routes
- [x] Add PIN session routes
- [x] Add share and directory routes
- [x] Add `GET` and `HEAD` download routes
- [x] Add request limits and safe response headers
- [x] Enforce same-origin login and reject unexpected hosts
- [x] Force untrusted files to download as attachments
- [x] Add cancellation-aware streaming

Test loop:

1. Write a failing Ktor route test for one response at a time.
2. Implement the route with fake repositories.
3. Run the complete server test suite after each route.

Exit check: Ktor tests cover login, browse, download, disabled shares, malformed handles, and shutdown cancellation.

## 6. Foreground service

- [ ] Add the connected-device foreground service declaration
- [ ] Add start, stop, and status commands
- [ ] Publish immutable server state
- [ ] Add the persistent notification
- [ ] Add the notification Stop action
- [ ] Discover reachable local addresses
- [ ] Request local network access on Android versions that require it
- [ ] Bind only the selected local interface
- [ ] Make repeated start and stop commands safe

Test loop:

1. Unit-test the state machine.
2. Instrument service start and stop.
3. Confirm the listening socket closes after every stop path.

Exit check: sharing survives Activity recreation and stops from both the app and notification.

## 7. Owner experience

- [ ] Build the empty share state
- [ ] Build the share list and controls
- [ ] Build start and stop controls
- [ ] Show URL, port, and PIN while running
- [ ] Show actionable error states
- [ ] Add accessibility labels and large touch targets

Test loop:

1. Add Compose tests for each state.
2. Drive state changes through a fake controller.
3. Run screenshot checks for compact and large screens when available.

Exit check: the complete owner flow works without opening settings except to enable a normal hotspot.

## 8. Guest experience

- [x] Add login view
- [x] Add share and folder browser
- [x] Add breadcrumbs and back navigation
- [x] Add download actions and native browser progress
- [x] Add expired-session recovery
- [x] Bundle deterministic web assets into the APK

Test loop:

1. Test browser behavior against the Ktor test server.
2. Test narrow and wide layouts.
3. Run the guest flow from a second physical device.

Exit check: a current Chrome, Firefox, or Safari browser can log in, browse, and download without installing an app.

## 9. Release hardening

- [ ] Run traversal and token tampering tests
- [ ] Test large files and client disconnects
- [ ] Test service death, storage removal, and network changes
- [ ] Add privacy copy and cleartext transport warning
- [ ] Add release signing instructions
- [ ] Build a release candidate APK

Exit check: the full acceptance path in `architecture.md` passes on at least two Android vendors and two guest platforms.

## Later releases

- App-created local-only hotspot and Wi-Fi QR code
- mDNS service discovery
- TLS through a companion guest app
- HTTP range requests for seekable providers
- Streaming folder archives
- Optional uploads with separate write grants
- Read-only WebDAV
