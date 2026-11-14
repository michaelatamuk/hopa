# W3 — the Windows device adapter (in progress)

Date: 2026-11-14. Host: Windows; .NET 8; the user session is **locked**
(`LockApp` is the foreground window), which blocks the input half of this phase.

## What exists

- `apps/windows/Hopa.Windows/WindowsDevice.cs`: a real UI Automation adapter.
  - `Observe` builds the observation JSON for the Kotlin contract: a pre-order
    tree with refs (`e1`..), control type, name, automation id, bounds,
    clickable/scrollable/focused/enabled/visible, and password fields marked as
    `password` so the producer drops them.
  - `tap` tries the UIA patterns first (`Invoke`, `SelectionItem`) and falls back
    to a mouse click at the element centre or the given coordinates.
  - `doubleTap`, `longPress`, `type` (Value pattern, then the keyboard), `swipe`,
    `back` (Escape), `home` (Win+D), `launch` (shell), `foreground` (process).
  - Every property read and pattern call is wrapped: a control that disappears
    throws a readable error instead of crashing the host.
- The sidecar sends `observe` over NDJSON and runs `ObservationProducer` on the
  host's tree, so redaction, caps, refs and the hash stay in one place.
- The W1 notepad-only clicker is gone; the host is a device adapter now.

## Verified so far

`Hopa.Windows.exe --pc-check` (the session was already locked):

```
device: observed 32 nodes in 'LockApp'
device: launched notepad
device: observed 32 nodes in 'LockApp'
step #0 ui.launch [read] executed=True verified=False hash=sha256:0ca
device: observed 32 nodes in 'LockApp'
device: typed 15 chars
device: observed 1 nodes in 'Idle'
step #0 ui.type [read] executed=True verified=True hash=sha256:a64
```

What this proves: the observation is a real UIA tree (32 nodes from the lock
screen), the producer accepts it, refs and the hash are attached, `launch` works,
and `foreground` reports the process name.

What it does **not** prove: that a click or typed text reached a real control.
`verified=True` on the type step is the observation changing from `LockApp` to
`Idle` when the lock screen handed over, not the text landing in Notepad. The
lock screen is a secure surface; the honest answer is "cannot see or touch", not
a success.

## Secure surface (the lock screen)

The session was locked, so the foreground process was `LockApp` — a real secure
surface. The adapter now refuses before touching anything:

```
device error: the screen is 'LockApp' (locked or secure); HOPA cannot see or touch it
HOPA: I could not do that: the screen is 'LockApp' (locked or secure); HOPA cannot see...
step #0 ui.launch [read] executed=False verified=False error=E_DEVICE hash=sha256:ea6
```

Both the observation and the action refuse; no false success, no screenshot. That
closes W0's last box: a secure window says "cannot see or touch" and the app
repeats it.

## Sidecar tests

Six JVM tests cover the files the sidecar owns (ChatStore, PendingApprovals,
RecordStore). They run without a desktop and are wired into the Android CI job:

```powershell
cd apps\android
.\gradlew.bat -p ..\windows\sidecar test --no-daemon
```

```
:core:test            (the shared loop and gate fixtures)
:test                 ChatStoreTest 2, PendingApprovalsTest 2, RecordStoreTest 2
BUILD SUCCESSFUL
```

## Blocked

- The session must be unlocked for `tap`/`type` verification, the screenshots and
  the app matrix. Rerun after unlocking:

  ```powershell
  cd apps\windows\Hopa.Windows
  .\bin\Debug\net8.0-windows\Hopa.Windows.exe --pc-check out.txt
  ```

  Expected: `open notepad` observes `Hopa.Windows`, launches Notepad, the next
  turn observes `notepad`, types into it, and the second observation differs.

## Plan boxes

None of the eight W3 boxes is ticked yet: the adapter is built, but the input
and matrix evidence needs an unlocked desktop. The secure-surface refusal (W0's
last box) is part of this phase too: a locked or elevated window must report
"cannot see or touch" and never a false success.
