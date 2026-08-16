# Security test plan

## Accepted MVP risk

The MVP uses plain HTTP on a trusted temporary local network. It does not protect the PIN, cookie, filenames, or file bytes from an on-path attacker. The owner must see this limitation before starting a server.

## Release invariants

- Anonymous requests never query a document provider.
- The app never requests a write grant or calls a document mutation API.
- Guest input never supplies a path, URI, authority, or document ID.
- Old sessions and handles fail after stop, restart, PIN change, or share-set change.
- Untrusted content never renders under the authenticated server origin.
- Resource use stays bounded under slow, malformed, and concurrent requests.
- Every shutdown path closes the listener, streams, cursors, and file descriptors.

## Automated attack cases

| Area | Cases |
| --- | --- |
| Authorization | Missing, malformed, expired, revoked, and previous-run cookies on every protected route |
| PIN limits | Parallel guesses, spoofed forwarding headers, IPv4 and IPv6 peers, global exhaustion, and bounded limiter state |
| Handles | Unknown, truncated, oversized, previous-run, expired, cross-share, disabled-share, and removed-document handles |
| Browser | Cross-origin form and fetch requests, bad Host headers, iframe embedding, CORS preflights, and active file content |
| Output | HTML, quotes, CRLF, slashes, dot segments, bidi controls, emoji, invalid Unicode, and extreme filename lengths |
| HTTP | Unsupported methods, oversized headers and bodies, slow clients, disconnects, malformed encoding, and invalid ranges |
| Storage | Revoked grants, removed media, provider crash, null columns, duplicate names, huge directories, and non-seekable streams |
| Concurrency | Simultaneous login, listing, download, share disable, root removal, network loss, and repeated start or stop |
| Shutdown | Stop during login, listing, and download, plus task removal, process death, and engine start failure |

## Adversarial document provider

Instrumentation tests use a test `DocumentsProvider` that can return malformed metadata, delay calls, throw during a stream, revoke a node, and report a child outside the selected tree. Any write-mode open or mutation call fails the test immediately.

## Static read-only check

CI searches the serving code for write grants and mutation methods. Any intentional future write support must live behind a separate capability and security review.

Forbidden in the MVP serving path:

```text
FLAG_GRANT_WRITE_URI_PERMISSION
openOutputStream
createDocument
deleteDocument
moveDocument
renameDocument
```

## Device checks

- Minimum supported Android version
- Android 12 foreground start restrictions
- Android 14 foreground service type enforcement
- Android 17 local network permission grant, denial, and revocation
- Local Downloads provider
- Removable storage when available
- One cloud-backed document provider
- Two hotspot implementations from different Android vendors

## End-to-end security gate

1. Start with one selected disposable folder.
2. Prove anonymous requests cause zero storage calls.
3. Prove modified and previous-run credentials fail.
4. Prove an unselected sibling is unreachable.
5. Prove active HTML and SVG download instead of executing.
6. Stop during a large download and verify the port and stream close.
7. Scan logs and confirm they contain no secrets or private storage identifiers.
