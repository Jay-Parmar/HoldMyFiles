# Repository instructions

## Product scope

Build Hold My Files as an Android-first, local-only, read-only file sharing app. The MVP uses an existing Wi-Fi or hotspot network. App-created hotspots, uploads, and WebDAV belong to later milestones.

## Working style

- Keep explanations concise and concrete.
- Write natural code. Avoid filler comments and tutorial-style docstrings.
- Do not use em dashes in code, documentation, issues, or commit messages.
- Do not add generated-by text, assistant attribution, or co-author trailers.
- Never store credentials, tokens, PINs, signing keys, or local machine paths in Git.
- Preserve user changes and keep unrelated edits out of a commit.

## Test loop

Use red, green, refactor for each behavior:

1. Add or update the smallest test that describes the behavior.
2. Run it and confirm the expected failure when practical.
3. Implement the smallest passing change.
4. Run the focused test again.
5. Run the affected module test suite.
6. Refactor only while tests stay green.

For each vertical slice, also run its end-to-end acceptance path before committing.

## Commits

- Commit one micro-feature at a time.
- Use imperative Conventional Commit subjects.
- Keep formatting-only changes separate from behavior changes.
- Do not amend published commits unless Jay asks.
- Do not push broken builds.
- Never add co-author trailers.

Examples:

```text
docs: record the MVP architecture
build: scaffold the Android application
test(auth): define PIN lockout behavior
feat(auth): issue sessions for valid PINs
```

## Architecture rules

- Treat every network client as untrusted.
- Access shared content only through persisted Storage Access Framework tree URIs.
- Never accept absolute paths, raw content URIs, or document IDs from a guest.
- Issue authenticated opaque handles for files and folders.
- Check that a share is enabled on every listing and download request.
- Keep the first release read-only.
- Stream file content. Do not buffer complete files in memory.
- Keep server ownership in the Android foreground service, not the Activity.
- Stopping the service must close sockets, cancel streams, and invalidate sessions.
- Keep domain and security logic independent of Android where possible so it can be unit tested on the JVM.
