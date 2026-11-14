# W4 — desktop fit (code; visual checks pending)

Date: 2026-11-14. The session was locked, so this phase is written and tested
headlessly; the visual checks wait for an unlocked desktop.

## What exists

- **History**: search box filters titles and message text; the row menu offers
  Rename, Export and Delete; export writes the conversation JSON through a save
  dialog.
- **Tray**: a notification-area icon with Open and Exit; an answer that arrives
  while the window is unfocused shows a balloon with its first 140 characters.
- **Clipboard**: a Copy button puts the last answer on the clipboard.
- **Read aloud**: a Speak button uses `System.Speech`; Escape stops it.
- **Keyboard**: `Ctrl+N` new chat, `Ctrl+F`/`Ctrl+K` focus the search, `Esc`
  stops speech.
- **Window state**: position, size and maximized state are saved in
  `settings.json` and restored on start.
- **Accessibility**: names on the composer, send, search, conversations, New
  chat, Settings, Copy, Speak and Run.
- Sidecar operations: `conversation.search`, `conversation.rename`,
  `conversation.delete` (soft, tombstoned), `conversation.export`.

## Verified headlessly

```
Hopa.Windows.exe --history-check out.txt
pin: True
archive: True; hidden: True; shown when asked: True
created: ok
renamed: Groceries
search hits: 1
export has title: True
delete: True
conversations left: 5
```

Pinned conversations sort first; archived ones leave the list unless "Show
archived" is on; the row menu toggles both. The seven JVM tests for the stores
also run in CI (`gradlew -p apps/windows/sidecar test`).

## Not verified yet

- The tray icon, the balloon, the shortcuts and the window restore need an
  unlocked desktop and a person to look at them.
- `System.Speech` needs an installed voice; speaking is not exercised here.

## Not done

- **Voice input** (microphone capture and bridge transcription) is not built; it
  needs an audio device and a real bridge round-trip.
- **Share out** through the Windows share sheet is not built; Copy covers the
  clipboard path.
- Multi-monitor placement is saved as coordinates only; no per-monitor
  validation yet.
