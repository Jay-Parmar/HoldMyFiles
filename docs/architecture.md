# System architecture

## Goal

Hold My Files lets an Android owner expose selected folders to guests on the same local network. Guests need only a browser. They cannot browse anything the owner did not select.

## MVP boundary

Included:

- Android owner app
- Existing Wi-Fi or phone hotspot
- Read-only sharing
- Browser guest client
- PIN login
- Folder listing and file download
- Foreground operation with a visible notification
- Immediate shutdown

Deferred:

- Starting a hotspot from the app
- Upload, rename, move, and delete
- WebDAV and SMB
- Internet relay or cloud storage
- iOS hosting

## System context

```text
Owner
  |
  | selects folders and controls sharing
  v
+----------------------- Android phone ------------------------+
|                                                               |
|  Compose UI                                                   |
|      |                                                        |
|      v                                                        |
|  Share repository <----> Storage Access Framework             |
|      |                         |                               |
|      |                         v                               |
|      |                  Selected document trees               |
|      v                                                        |
|  File server foreground service                               |
|      |                                                        |
|      +--> PIN sessions                                        |
|      +--> Opaque node handles                                 |
|      +--> Directory API                                       |
|      +--> Streaming downloads                                 |
|      +--> Guest web assets                                    |
|                                                               |
+---------------------------+-----------------------------------+
                            |
                            | local HTTP over Wi-Fi
                            v
                    Guest web browsers
```

## Main components

### Owner UI

The Compose UI displays saved shares and current server state. It launches Android's folder picker, starts and stops the foreground service, and shows the local address and session PIN.

The Activity is not the server owner. Closing or recreating the Activity must not stop an active sharing session.

### Share repository

The repository stores only configuration:

```text
ShareRoot
  id             random UUID
  displayName    owner-visible label
  treeUri        persisted Android tree URI
  enabled        whether new requests may use this share
  createdAt      creation timestamp
```

The MVP uses DataStore because the data is small and replaced as one consistent snapshot. Room becomes useful if activity history, client records, or searchable metadata are added.

### Storage gateway

The storage gateway is the only component allowed to work with Android document URIs. It provides application-level operations:

```text
listRoots()
listChildren(shareId, nodeHandle)
openFile(shareId, nodeHandle)
```

It uses `ContentResolver` and `DocumentsContract`. Guest input never reaches these APIs directly.

### File server service

An Android foreground service owns the embedded Ktor server. This matches the product lifecycle: sharing begins after an explicit owner action and remains visible through a notification.

Server states:

```text
STOPPED -> STARTING -> RUNNING -> STOPPING -> STOPPED
               |          |
               +-> ERROR <-+
```

Only one transition runs at a time. Start and stop commands are idempotent.

### Guest web client

The guest client is a small responsive site bundled in Android assets and served by Ktor. It handles login, breadcrumbs, folder listings, errors, and downloads. It has no build-time dependency on a remote service.

## Request flow

### Add a folder

```text
Owner taps Add folder
  -> Android system picker opens
  -> owner selects a directory
  -> app persists read permission for the returned tree URI
  -> app records a ShareRoot
  -> owner UI shows the share
```

### Start sharing

```text
Owner taps Start
  -> Activity sends an explicit foreground service command
  -> service creates a random session secret and PIN
  -> service opens an available TCP port
  -> service resolves reachable local addresses
  -> service publishes RUNNING state
  -> UI shows URL and PIN
```

### Guest login and browse

```text
Guest opens local URL
  -> server returns bundled web client
  -> guest submits PIN
  -> rate limiter checks the client
  -> valid PIN creates a short-lived session cookie
  -> guest requests shares
  -> server returns enabled roots
  -> guest requests children using an opaque node handle
  -> storage gateway lists only that permitted tree
```

### Download

```text
Guest requests a file handle
  -> session is validated
  -> handle signature and expiry are validated
  -> share is checked again
  -> storage gateway opens an InputStream
  -> server streams bytes to the response
  -> stream closes on completion, disconnect, or shutdown
```

### Stop sharing

```text
Owner taps Stop in app or notification
  -> state changes to STOPPING
  -> server stops accepting connections
  -> active calls are cancelled
  -> server socket closes
  -> sessions and handle keys are discarded
  -> foreground notification is removed
  -> state changes to STOPPED
```

Persisted folder grants remain until the owner removes that share.

## HTTP API

```text
GET  /                         guest web client
GET  /health                   server readiness
POST /api/v1/session           exchange PIN for session
DELETE /api/v1/session         clear session
GET  /api/v1/shares            enabled share roots
GET  /api/v1/nodes/{handle}    list a directory
HEAD /api/v1/files/{handle}    inspect a file
GET  /api/v1/files/{handle}    stream a file
```

Error responses use one shape:

```json
{
  "code": "invalid_handle",
  "message": "This link is no longer valid."
}
```

## Trust boundaries

```text
Trusted                       Untrusted
---------------------------   -----------------------------
Owner actions                 Guest HTTP requests
Persisted tree grants         Headers, cookies, and handles
In-process session secret     File names from providers
Application configuration     Local network peers
```

The phone hotspot is transport, not authorization. Every guest request still requires a valid application session.

## Security invariants

1. A guest cannot submit a filesystem path, document URI, or document ID.
2. A node handle is signed, short-lived, and bound to one server run.
3. A valid handle is useless without a valid session.
4. Removing or disabling a share blocks its existing handles immediately.
5. PIN attempts are rate limited by client address and globally.
6. Authentication failures do not reveal whether a share or file exists.
7. Downloads are read-only and streamed with bounded concurrency.
8. Stop invalidates all in-memory authorization material.
9. Logs exclude PINs, cookies, content URIs, and handle payloads.

## Opaque handles

A handle represents a server-issued reference to a node. It contains the share ID, provider URI, node kind, and expiry in an authenticated payload. The client cannot change the payload without invalidating its HMAC.

The signing key exists only for the current server run. Restarting the server invalidates old links.

The server issues handles only for roots it loaded from the share repository and children returned by the storage gateway. It never signs guest-provided URIs.

## Network model

The server listens on an available high port. It reports addresses from active private or link-local interfaces and rejects loopback-only results. The owner chooses the address that belongs to the hotspot or Wi-Fi network.

The first release does not assume a fixed gateway such as `192.168.43.1`. Android vendors use different subnets.

Cleartext HTTP is acceptable for the first local-network MVP, but it is not confidential against a hostile local peer. The app must warn owners not to share sensitive files. A later secure transport can use a companion client with certificate pinning.

## Technology choices

| Concern | Choice | Reason |
| --- | --- | --- |
| Owner UI | Kotlin and Compose | Direct Android lifecycle and picker integration |
| HTTP server | Ktor and Netty | Structured routing, streaming, and coroutine support |
| Configuration | DataStore | Small atomic configuration set |
| File access | Storage Access Framework | Owner-selected access without broad storage permission |
| Guest UI | Static HTML, CSS, and TypeScript | Browser access with no guest install |
| Authentication | Random PIN and in-memory session cookies | Temporary access scoped to one server run |
| Tests | JUnit, coroutine test, Ktor test host | Fast tests for domain and HTTP behavior |

PostgreSQL, Django, and FastAPI are intentionally absent. This server runs inside Android and stores little structured data. Adding those systems would increase size and lifecycle complexity without helping the MVP.

## Planned source layout

```text
app/src/main/kotlin/dev/jay/holdmyfiles/
  app/             Activity and application setup
  sharing/         Share model and repository
  storage/         Storage Access Framework gateway
  security/        PIN, sessions, handles, rate limits
  server/          Ktor routes and response models
  service/         Foreground service and state controller
  network/         Address discovery
  ui/              Compose screens and components

app/src/main/assets/web/
  index.html
  app.css
  app.js
```

## End-to-end acceptance path

1. Install the app on an Android phone.
2. Select one test folder with one nested folder and two files.
3. Enable the phone hotspot and connect a second device.
4. Start sharing and open the displayed URL on the second device.
5. Confirm a wrong PIN is rejected and the current PIN succeeds.
6. Browse both folder levels and download both files.
7. Confirm an unselected sibling folder is never listed.
8. Disable the selected share and confirm its old link stops working.
9. Re-enable it, restart sharing, and confirm the old session is invalid.
10. Stop from the notification and confirm the URL no longer connects.
