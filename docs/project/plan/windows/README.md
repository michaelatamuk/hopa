# Windows client plan

Status: **v1, W0 complete (7 of 7); W3 in progress: the adapter is built, live input verification is blocked by the locked session.** Written 2026-11-14 from the
Android plan, the expansion portfolio and ADR-0021.

Android is the active client; Windows is next; Harmony is frozen (ADR-0020).
This plan is counted on its own:

```bash
python tools/scripts/plan-status.py --plan docs/project/plan/windows
```

A box is ticked only when the behaviour exists **and** an artifact proves it
(screenshot, transcript, CI run, installer hash). Every tick carries
`(evidence: ...)`, and phase evidence lives in `docs/project/evidence/W<N>-*.md`.

## 1. Goals

1. A Windows client that looks and behaves like the HarmonyOS laptop app: a
   sidebar of conversations, a chat column, settings, approvals.
2. **One brain:** the loop, the gate and the contracts come from `packages/`;
   no second implementation.
3. Two targets from one window: the **phone** through the bridge, and the
   **PC itself** through UI Automation.
4. Honest failure: elevated windows, secure screens and apps without an
   accessibility tree say so instead of pretending.
5. Sideload-first: a signed MSI on a clean Windows VM, with a checksum.

## 2. Non-goals

- A full .NET port of the loop (ADR-0021).
- MSIX, the Microsoft Store and auto-update in v0; the installer is an MSI.
- Windows on ARM as the first target.
- Multi-user or enterprise (SSO, MDM, DLP).
- macOS or Linux hosts; Avalonia is not chosen.
- Screenshots or screen text in logs, ever.

## 3. Decisions already taken

| Decision | Where |
|---|---|
| Windows is the next client | ADR-0020 |
| .NET 8 + WPF shell, Kotlin engine as a sidecar, NDJSON over stdio | ADR-0021 |
| The client engine is `packages/core`, `packages/data`, `packages/capabilities` | ADR-0007 addendum |
| Clients own the loop; the bridge stays transport | ADR-0016 |
| Device control on the PC is a `DevicePort` adapter (FlaUI/UIA), like Android's accessibility adapter | ADR-0017 pattern |

## 4. Shape

```
apps/windows/
  Hopa.Windows/           WPF app (C#): windows, views, view models
  Hopa.Windows.Device/    WindowsDevice: UIA observe/act, screenshots (C#)
  Hopa.Windows.Ipc/       NDJSON framing and message types (C#)
  sidecar/                Kotlin engine host (Gradle build, uses packages/)
  installer/              WiX MSI

IPC (stdio, NDJSON):
  host -> sidecar   hello, set-model-key, turn.start, approval.resolve,
                    cancel, device.result
  sidecar -> host   ready, step, device.request, approval.request,
                    turn.end, log
```

Targets:

```
window -> sidecar -> core loop -> DevicePort
                                   ├─ bridge AdbDevice  -> the phone (adb)
                                   └─ host device proxy -> WindowsDevice (UIA)
```

## 5. Phases

### W0. Decision and spike (2 weeks, W0 in progress)

- [x] ADR-0021 accepted, linked from the decisions index and this plan. (evidence: docs/design/decisions/0021-windows-client-stack.md; decisions index)
- [x] `apps/windows/sidecar` Gradle build includes `packages/core` and `packages/capabilities`. (evidence: apps/windows/sidecar/settings.gradle.kts; `installDist` green; docs/project/evidence/W0-spike.md)
- [x] NDJSON protocol v0 written down: message names, fields, version, error shape. (evidence: docs/project/plan/windows/protocol.md)
- [x] A WPF window starts the sidecar, sends `turn.start` and shows the answer. (evidence: docs/media/windows-w0.png; W0-spike "One turn from the window")
- [x] One UIA click in Notepad goes through `device.request` -> FlaUI -> `device.result`. (evidence: W0-spike "One UIA click": "clicked Notepad ... at 640,464")
- [x] Honest failure: a secure window (UAC prompt or a `FLAG_SECURE`-like surface) returns "cannot see or touch"; the app says so. (evidence: W3-device "Secure surface": LockApp refused with E_DEVICE, message repeated by the app)
- [x] Spike notes in `docs/project/evidence/W0-spike.md` with screenshots and the protocol file. (evidence: docs/project/evidence/W0-spike.md)

Gate: the four spike criteria in ADR-0021 hold on a Windows VM. All four do; the
secure-surface refusal is recorded in W3 (the lock screen is the test surface).

### W1. Shell (3 weeks)

- [x] Window chrome, sidebar, conversation list, chat column, composer; the laptop layout from `apps/harmony/laptop`. (evidence: docs/media/windows-w1.png; docs/project/evidence/W1-shell.md)
- [x] Conversation persistence through the sidecar (JSON store): restart keeps history. (evidence: W1-shell "Verification": same conversation id across two runs, 4 messages in the list)
- [x] Draft reuse per conversation, like Android. (evidence: W1-shell "Settings and drafts": draft round-trip: ok; tools/scripts/windows-smoke.py drafts: ok)
- [x] Settings: model provider and key (Windows Credential Manager), bridge address and token, profile tone. (evidence: docs/media/windows-settings.png; W1-shell "Settings and drafts": settings/credential round-trip ok; configure log)
- [x] Theme: the same cream/orange palette and mascot rhythm. (evidence: docs/media/windows-w1.png; palette copied from HopaColor)
- [x] Smoke script `tools/scripts/windows-smoke.py` (start, send, screenshot, assert) and screenshots in `docs/project/evidence/W1-shell.md`. (evidence: W1-shell "Verification"; script output turn: ok / list: ok)

Gate: a person can chat, close the app, reopen it and find the thread. Met by
the restart check in W1-shell (two processes, one conversation).

### W2. Loop and the phone target (3 weeks)

- [x] Sidecar runs the full `AgentLoop` with the bridge `AdbDevice` (target: phone). (evidence: W2-loop "Bridge turn": com.android.settings launched on emulator-5554)
- [x] Approval sheet for sensitive and payment actions; a pending approval survives a restart and times out after 120 s. (evidence: W2-loop "Pending approvals": denied 1 pending approval(s) on restart; allow/deny transcripts; HostApproval 120 s timeout)
- [x] Run records through the sidecar; a runs view shows steps, classification, verified and the hash chain. (evidence: W2-loop "Records and the chain": chain: ok; records.json 912 B; Run dialog)
- [x] One real turn: "open Settings on the phone" through the bridge, with before/after observations. (evidence: W2-loop "Bridge turn": dumpsys shows Settings; verified=True, then False when already foreground)
- [x] Honest failure when the bridge is down or the token is stale; one 401 refresh. (evidence: W2-loop "Bridge down and the 401 refresh": E_DEVICE on 8898; "bridge token refreshed" then success on 8897)
- [x] Evidence: transcript, screenshots, record dump in `docs/project/evidence/W2-loop.md`. (evidence: docs/project/evidence/W2-loop.md)

Gate: a turn started in the Windows window acts on the phone through the bridge and is verified.

### W3. The PC as a target (6 weeks)

- [ ] `WindowsDevice` implements the same contract as the Android adapter: `observe`, `tap`, `doubleTap`, `longPress`, `type`, `swipe`, `back`, `home`, `launch`, `stop`, `foreground`, `screenshot`, `apps`.
- [ ] UIA observation: refs stable across one screen, bounds, password fields dropped, node and text caps, hash.
- [ ] Actions: pattern first (`Invoke`, `Toggle`, `SelectionItem`, `ValuePattern`), coordinates and `SendInput` as fallback; timeouts on every UIA call.
- [ ] Screenshots through `Windows.Graphics.Capture`; a protected window returns no frame.
- [ ] Launch by AUMID or executable; foreground by window handle; per-monitor DPI handled.
- [ ] Elevated windows: a signed `uiAccess` helper performs the action; without it, an honest refusal with the reason and a link to the setup.
- [ ] App matrix executed and recorded: Notepad, Calculator, Explorer, Chrome, an Electron app, a WPF app.
- [ ] Evidence: matrix table, screenshots, failure taxonomy in `docs/project/evidence/W3-device.md`.

Gate: the window acts on the PC (Notepad and Calculator) and refuses UAC, elevated windows and apps without a tree, each with a distinct message.

### W4. Desktop fit and parity (4 weeks)

- [ ] Clipboard and file drop into the composer; share out through the Windows share sheet or the clipboard.
- [ ] Toast notification when an answer arrives while the window is unfocused; tray icon with run state and Stop.
- [ ] Voice: bridge transcription into the composer and read-aloud through the bridge or Windows TTS.
- [ ] History: search, rename, delete, export; pinned and archived conversations.
- [ ] Keyboard: shortcuts for new chat, send, stop, search; full keyboard navigation.
- [ ] Accessibility: UIA names on every control, 200% scaling, high contrast.
- [ ] Multi-monitor and window-state restore.
- [ ] Evidence: parity table against the Harmony laptop app in `docs/project/evidence/W4-parity.md`.

Gate: the parity table has no unexplained gaps.

### W5. Delivery (3 weeks)

- [ ] Sidecar shipped as a `jlink` runtime plus a fat jar inside the MSI.
- [ ] `dotnet publish` self-contained build; WiX MSI with version metadata and a licence page.
- [ ] Code signing (the same certificate that signs the `uiAccess` helper) and a verified signature on the installer.
- [ ] Windows CI job: build, unit tests, smoke script; the sidecar keeps the JVM job.
- [ ] Clean-VM E2E: install, pair the bridge, run one phone turn and one PC turn.
- [ ] Release note, privacy note (what is read, what is stored, where), checksum.
- [ ] Evidence: `docs/project/evidence/W5-release.md` with the installer hash and the E2E transcript.

Gate: the signed MSI installs on a clean Windows VM and both targets work.

## 6. Acceptance: assertions, not "opens"

| Area | Assertion | Artifact |
|---|---|---|
| Startup | the window opens and the sidecar is ready within 10 s | log + screenshot |
| Chat | a message produces an answer; restart keeps it | screenshot + store dump |
| Phone target | `ui.launch` on the phone changes the foreground app | before/after observation |
| PC target | a click in Notepad changes the document text | before/after tree + screenshot |
| Gate | a payment action waits for approval; deny leaves `executed: false` | record dump |
| Honest failure | UAC, elevated window and no-tree app each give a different message | screenshots |
| Records | tampering a field makes `verifyChain` fail | test log |
| Installer | signed MSI installs on a clean VM; checksum recorded | MSI + hash |
| Locked down | screen text is absent from logs and crash dumps | grep + review |

## 7. Dependencies and risks

| Dependency | Owner | Blocks |
|---|---|---|
| `packages/core` and `packages/capabilities` | done (2026-11-14) | everything |
| NDJSON protocol v0 | W0 | W1 and later |
| Code-signing certificate for the `uiAccess` helper and the MSI | procurement | W3, W5 |
| Windows CI runner | architecture | W5 |
| A clean Windows VM for E2E | architecture | W5 |
| The bridge (paired) | platform | W2 |

| Risk | Mitigation |
|---|---|
| UIA calls hang | timeouts, caching, a watchdog on every call |
| `uiAccess` needs a signature and Program Files | plan the certificate early; refuse honestly without it |
| Two runtimes confuse support | one installer, one version number, one log file |
| IPC drift between C# and Kotlin | versioned protocol file plus a round-trip test on both sides |
| The sidecar leaks secrets | keys in Credential Manager only; the sidecar receives them in memory |
| Scope creep towards the Store | MSIX is explicitly out of v0 |

## 8. Definition of done

- All W0–W5 gates pass with artifacts.
- One brain: no gate, loop or contract code exists twice.
- Both targets work from the same window, with honest failures.
- Signed MSI on a clean VM, release note, privacy note, checksum.
- The parity table against the Harmony laptop app is signed off.

## 9. Open decisions

- WinUI 3 after v0, or stay on WPF? Decided by the first user feedback on look
  and by whether MSIX/auto-update is required.
- SQLite in the sidecar: when does the JSON store stop being enough?
- Is the `uiAccess` helper a separate signed executable (recommended) or the
  main app signed with `uiAccess`?
- Does the Windows client pair its own bridge token, or reuse the phone's?
- Auto-update mechanism after v0: MSIX App Installer, WinSparkle, or a manual
  download page.
