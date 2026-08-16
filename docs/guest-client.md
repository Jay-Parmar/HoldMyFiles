# Guest client design

## Goal

The guest client gives any hotspot participant with a modern browser a small, install-free way to enter the current PIN, browse shared folders, and download files.

The client uses browser-native TypeScript and DOM APIs. React is not included because the MVP has one short state flow and a small component surface. Avoiding a framework keeps the APK and every guest response smaller.

## State flow

```text
PIN form
  -> authenticate
  -> load shared roots
  -> render directory
  -> open child directory
  -> follow direct file download link

Any protected request returning 401
  -> discard browser navigation
  -> return to PIN form
```

Folder handles live only in JavaScript closures. They are never placed in the address bar, local storage, session storage, or logs. Breadcrumbs keep the current stack in memory and reload the selected level through the API.

## Browser boundary

- The PIN stays a six-character string so leading zeroes survive.
- Requests use same-origin credentials, same-origin mode, no caching, and rejected redirects.
- Listing responses are checked for valid handles, node kinds, names, sizes, and entry counts.
- Provider names enter the page only through `textContent` inside `<bdi dir="auto">`.
- File actions are normal links to `/api/v1/files/{handle}`. JavaScript never buffers a file into a Blob.
- Fixed user-facing errors never include server response text, a PIN, a cookie, or a handle.
- Logging out calls `DELETE /api/v1/session` and removes every in-memory breadcrumb.

## Accessibility

- The PIN has a visible label, numeric keyboard hint, six-digit pattern, and one-time-code autocomplete.
- Loading uses `role="status"`; failures use `role="alert"`.
- Focus moves to the heading after navigation and back to the PIN after authentication failure.
- Controls are at least 44 CSS pixels tall and input text is at least 16 CSS pixels.
- Long and bidirectional filenames wrap without changing surrounding interface direction.

## Build

The source lives in `web/`. Vite writes three fixed production files into `app/src/main/assets/web/`:

```text
index.html
assets/app.css
assets/app.js
```

Run the full web gate with Node 24:

```powershell
cd web
npm ci
npm run check
```

The gate runs strict type checking, Vitest DOM tests, and the production build. CI then checks that the committed Android assets match the source build.
