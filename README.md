# Hold My Files

Hold My Files turns an Android phone into a temporary, read-only file server for people on the same Wi-Fi or hotspot network.

The owner chooses folders through Android's system picker, starts sharing, and gives guests a local URL and PIN. Guests browse and download files in a web browser. Stopping the server closes the listener and invalidates every session.

## MVP

- Android 8.0 or newer
- Read-only folder sharing
- Explicit folder access through the Storage Access Framework
- Local HTTP server inside a foreground service
- Short-lived PIN authentication
- Browser-based directory browsing and downloads
- One-tap shutdown from the app or notification
- No cloud account and no internet dependency

## Documentation

- [System architecture](docs/architecture.md)
- [Security test plan](docs/security-testing.md)
- [Development roadmap](docs/roadmap.md)
- [Development setup](docs/development.md)
- [Guest client design](docs/guest-client.md)

## Project status

The first vertical slice is under development.
