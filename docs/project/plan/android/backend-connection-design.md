# Android backend connection and FCM wake — design

Written 2026-11-16. Scope: the Android client for the week-1 backend that is
already deployed. This closes the client half of exit-check row 4 in
[`../../evidence/B1-gateway.md`](../../evidence/B1-gateway.md) and gives the phone a
real gateway link.

## Intent

The phone should become a household device: paired once from inside the app,
connected to the deployed gateway while it runs, woken by FCM when it does not,
and able to execute a task the other device sent and report the result back. The
server half is proven; this is the missing client half, on a Xiaomi 15T Pro
(Android 15/16, Google Play services present).

## Goals

1. Pair from the app: paste the `hopa://pair?v=1&…` URI, claim it, keep the
   device id and tokens in the Android Keystore. No adb provisioning, no
   bootstrap token on the phone.
2. Hold a gateway socket while the app is in the foreground: `device.hello` with
   the FCM registration, `device.hello.ack`, queued deliveries drained on hello,
   ack every delivered `task.create`.
3. Execute a delivered task with the existing `AgentLoop`, on the surface the
   person's Settings already choose (`screenPath`: auto, hidden, onscreen or
   bridge), in a foreground service, and answer with `task.result` or
   `task.error`.
4. Wake: a data-only high-priority FCM message starts the process, the app
   reconnects, acknowledges the task, and runs it on that surface. When no
   surface answers — the phone locked on the onscreen path, or the hidden
   display not set up — the reply says exactly that instead of pretending.
5. Approvals behave as they do in chat: the phone's person is asked on any tab;
   nobody answers within 120 s and the step is refused and reported.
6. Repeatable: a settings section drives it, and two small PC scripts mint the
   pairing URI and send a task.

## Non-goals

- The hidden display as a *new* mechanism. Remote runs do not add a second way to
  choose a surface: they reuse the existing `screenPath` setting and its
  fallbacks. If the person has hidden or auto configured, that is what runs — and
  it needs the one-time hidden-display setup the app already documents.
- Household end-to-end encryption (week 2). `publicKey` is generated, claimed and
  stored; bodies are plaintext in v0.
- QR scanning. The URI or bare session string is pasted.
- Chat sync, presence UI, remote cancel, push for anything other than a wake.
- HMS/Huawei push, and any device without Google Play services.
- Resuming a run after the process dies mid-task (see Risks).
- `planFirst` for remote tasks: there is no remote plan-approval surface.

## Anti-goals

- The bootstrap token, or an access token, reaching the phone's logs, a
  notification or the repository.
- A payment, or an action in an app the person has not trusted, running without
  a tap on the phone. The trusted-app list is the person's own grant and is
  honoured.
- Making the build depend on a file that is not in the repository and cannot be
  produced by a documented script.
- A task executing twice because it was redelivered.

## Constraints

- The v1 protocol and OpenAPI are frozen: no backend change is expected. If one
  turns out to be needed, it is a contract version bump, not a patch.
- The free Render instance sleeps after ~15 min; the wake test warms it first.
- HyperOS restricts background work: the app needs "no restrictions" battery
  setting for a reliable wake.
- Execution needs a model provider key on the phone. Without one the demo model
  answers text and runs no actions; the link, wake and transport are still
  provable.
- Android 13+ for X25519 (`KeyPairGenerator("X25519")`); the target phone
  qualifies. Older devices get a clear "pairing unsupported here" message.
- `RunService` is a `shortService`, so a remote run is capped at 170 s, inside
  Android's three-minute limit. The 120 s approval wait lives inside that cap.
- The surface is whatever `screenPath` resolves to at run time: auto tries the
  hidden display, then the bridge, then the visible screen; a configured path
  that cannot answer is reported as an error rather than silently switched.

## Approaches

| # | Approach | Trade-off | Verdict |
|---|---|---|---|
| A | Wake path only: gateway client, FCM, ack but no execution | Cheapest proof of row 4; the phone still cannot do anything with a task | Rejected: the person wants the task to run |
| B | Wake path plus execution through `AgentLoop`, approvals as in chat | Uses the loop, recorder and gate that already exist; needs a foreground service and an approval path while the screen is off | **Chosen** |
| C | Debug shortcut: token seeded over adb, only FCM and reconnect | Half the work, not repeatable, leaves the installed app unpaired | Rejected |

## Design

### 1. Client package (pure JVM, testable)

New package `com.hopa.capabilities.backend` in `:capabilities`, beside
`bridge/`. It has no Android imports, so `:capabilities:test` covers it with
MockWebServer.

- `BackendEnvelope` — the v1 envelope as `kotlinx.serialization` with
  `JsonObject` body; encodes and decodes the fields the client uses, leaves
  unknown fields alone.
- `BackendPairing` — `claim(uri, platform, name, publicKey, pushToken)` and
  `refresh(refreshToken)` over OkHttp; parses `hopa://pair?v=1&h=…&p=…&u=…`
  into household, session and gateway URL.
- `BackendCredentials` — deviceId, householdId, accessToken, refreshToken,
  expiresAt, gateway URL.
- `BackendClient` — OkHttp WebSocket, state machine
  `Disconnected → Connecting → Helloing → Live → Reconnecting`; sends
  `device.hello` and waits for `.ack`; dispatches incoming frames; answers
  application `ping`; reconnects with 1/2/4/8 s backoff capped at 30 s; on an
  `E_AUTH` close it refreshes **once**, and if the refresh fails it stops and
  reports "pair again". Exactly one client exists per process, so a refresh is
  never raced: a rotated token presented twice would revoke the family.
- `WakeWindow` — opens the socket for the wake, drains the queue, and hands the
  live socket to the run; the socket stays open until `task.result` or
  `task.error` is sent (bounded by the run cap), then closes.
- `ProcessedTasks` — a 24 h durable set of `task.id` written before execution
  starts, so a redelivery is acked and skipped instead of run twice.

Tests: hello/ack over a fake server; a delivered `task.create` gets a
`task.ack` with the same trace and task; a malformed frame is ignored without
closing; a 401 close refreshes once and a second 401 stops; a duplicate task id
is acked but not reported twice; the wake window keeps the socket until the
result is written.

### 2. Android shell

- `BackendSession` (in `AppContainer`) owns the single `BackendClient`, persists
  credentials through `KeystoreSecretStore` + DataStore, and exposes
  `StateFlow<BackendState>` (disconnected / connecting / live / needs-pairing /
  error) and `StateFlow<BackendTaskState>` for the last task.
- **Socket lifetime.** Connected while HOPA is foreground (`MainActivity`
  resumed), closed 30 s after it leaves the foreground so presence stays honest
  and FCM is the only way in while asleep. A wake opens it again for the run.
- `RemoteTaskRunner` — on a delivered `task.create`: ack immediately under the
  task trace, start `RunService`, then resolve the surface exactly as chat does —
  the same `ScreenPathOrder` over `background` / `bridge` / `accessibility`
  sources, built from one shared helper so the two paths cannot drift. Then:

  | Situation | Result |
  |---|---|
  | a surface answered | run the goal on it, `task.result { summary }` with the loop's answer |
  | `hidden` or `bridge` configured but not answering | `task.error E_DEVICE`, retryable, naming the surface and the setup step (start the hidden screen, or pair the computer) |
  | `onscreen` and the phone locked or the screen off | `task.error E_DEVICE`, retryable, "phone is locked; send it again when you pick it up" |
  | screen control off and onscreen is the chosen path | `task.error E_CAPABILITY`, "turn on screen control in HOPA" |
  | a local run is busy | wait ≤30 s, then `task.error E_DEVICE`, retryable, "the phone is busy" |

  The run reuses the `ChatViewModel` shape: `TurnContext` with installed apps,
  memory, skills, model key, `phoneAccess = device.sourceName != "fallback"`,
  `platform = "android"`, and `container.loopFor(device)`. Before each run it
  reloads `trustedApps` into `ApprovalPolicy` (the trusted list is the person's
  grant; everything else asks) and sends one `task.progress` at the start, then
  `task.result { summary }` with the loop's answer. The evidence records which
  surface actually ran (`background`, `bridge` or `accessibility`).
- `HopaMessagingService : FirebaseMessagingService` — data-only messages; on a
  message it enters `WakeWindow`; on a token refresh it stores the token for the
  next hello. It never renders the payload, only `taskId` and `traceId`.
- `RunService` is the foreground vehicle (type `shortService`, 170 s cap, Stop
  action). A high-priority FCM message grants the background-start exemption; if
  the start is refused anyway the run happens inline in the messaging service
  for the wake window and reports a timeout.
- Approvals: `UiApprovalGateway` and the pending-approval store are reused. The
  sheet is **hoisted from `ChatScreen` to the activity root** so it appears on
  any tab, not only Chat. When HOPA is not foreground, `RemoteTaskRunner` posts
  a "HOPA needs your OK" notification that opens the sheet. A refusal is
  reported in `task.result` (loop continued) or `task.error E_POLICY` (loop
  stopped).

### 3. Settings and pairing

A "Backend" card on the Computer screen (already "address, pairing and the
two checks"), above the PC-bridge block:

- gateway URL, prefilled with the pairing URI's `u=` or the deployed test host;
- pairing field (the whole `hopa://pair…` URI or the bare session);
- Pair / Disconnect;
- status line: device id (short), connected since, FCM token (masked, with a
  copy action for debugging), last task state;
- a reminder that remote runs use the "How apps open" setting (auto / hidden /
  onscreen / bridge), with what each needs, and that HyperOS needs "no
  restrictions" for wakes. The card links to that setting instead of duplicating
  it.

Pairing generates an X25519 keypair, stores the private key base64 in the
Keystore store for week 2, sends the public key base64url (32 bytes) and the
current FCM token, and saves the returned credentials.

### 4. Firebase configuration

The project `hopa-prod-d767c` has no Android app. Add one for
`com.hopa.android` through the Firebase Management API with the service account
already used for FCM, then fetch its config:

- `tools/scripts/fetch-google-services.ps1` — creates the app if missing, then
  writes `apps/android/app/google-services.json`; prints only ids, never the
  service-account key.
- The file is **gitignored**. `:app` applies the Google services plugin only
  when the file exists (`apply false` at the root keeps the plugin on the
  classpath); without it the app builds and the Backend card says FCM is
  unavailable. This keeps CI and other developers green.
- The service account's read access to the project is verified (the Management
  API answered `200` for the project and its Android apps). If app creation is
  refused, that one step is done in the dashboard once and the fetch still
  works.
- Dependencies: `firebase-bom`, `firebase-messaging`; `POST_NOTIFICATIONS` is
  already declared.

### 5. PC side

- `tools/scripts/pair-device.ps1` — reads `HOPA_BACKEND_URL` and
  `HOPA_BOOTSTRAP_TOKEN` from the environment, `POST /v1/pair/sessions`, prints
  the `hopa://pair…` URI and its expiry. It never prints the bootstrap token.
- `tools/scripts/send-task.ps1` — pairs once as a `windows` device (session
  minted by the script above, tokens kept in `%TEMP%`), connects to the gateway,
  sends one `task.create` with a goal, waits for `task.ack`, then prints
  `task.result`/`task.error` and the trace id. This is the sender for
  verification 4 and 5 and the tool the person will actually use.

## Files (expected)

- `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/` — the
  client files, one declaration per file.
- `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/` — tests.
- `apps/android/app/src/main/kotlin/com/hopa/app/backend/` — `BackendSession`,
  `RemoteTaskRunner`, `HopaMessagingService`.
- `apps/android/app/src/main/kotlin/com/hopa/app/di/AppContainer.kt` — the shared
  surface helper (so chat and remote runs resolve `screenPath` the same way);
  `feature/chat/ChatViewModel.kt` switches to it.
- `apps/android/app/src/main/kotlin/com/hopa/app/ui/` — the hoisted approval
  sheet; `feature/chat/ChatScreen.kt` stops composing it.
- `apps/android/app/build.gradle.kts`, root `build.gradle.kts`,
  `gradle/libs.versions.toml`, `AndroidManifest.xml`, `.gitignore`.
- `apps/android/app/src/main/kotlin/com/hopa/app/feature/settings/` —
  `SettingsUiState`, `SettingsViewModel`, `ComputerScreen`.
- `tools/scripts/fetch-google-services.ps1`, `pair-device.ps1`,
  `send-task.ps1`, and the existing `android-device-checks.ps1` for the
  install.

## Verification

1. `:capabilities:test` green, including the new backend tests;
   `check-android-structure.py` and `check-structure.py` clean.
2. Phone installed through `android-device-checks.ps1 -InstallOnly` (the
   exec-in stager, because the host blocks "install").
3. Pair, then `render logs` shows `device.hello` and `hello.ack` for the new
   device id; the test stack's `devices` row has the FCM token.
4. Send `task.create` with the phone awake and unlocked: the task arrives, runs,
   `task.result` reaches the sender, and `trace_spans` shows `accept, route,
   deliver, ack`.
5. Background the app, screen off, send a task: a `wake` span appears, the
   socket reconnects, the task is acked, and it runs on the configured surface.
   With `auto` or `hidden` and the helper running it finishes headless; with
   `onscreen` the sender receives `E_DEVICE` "phone is locked", and unlocking
   plus resending runs it. Record which happened.
6. Record both in `B1-gateway.md` row 4 with the trace ids, and note the phone
   model, Android version and the measured wake delay.

## Risks

| Risk | Effect | Mitigation |
|---|---|---|
| HyperOS kills the process after screen-off | No wake or a late one | Battery "no restrictions"; test with screen off before trusting it; record the measured delay |
| Free Render instance asleep | Wake lands on a stopped service | Warm `/v1/health` before the test; `starter` before beta |
| The chosen surface is unavailable after a reboot | A wake arrives and the task reports "no surface" | `auto` falls back to the next path; the Backend card names the configured path and the setup step; the hidden helper is started on demand, as chat already does |
| Visible-screen path while locked | A task that arrives with the phone locked cannot run | `E_DEVICE` retryable, so the sender resends; choosing hidden or auto avoids it |
| Foreground start refused from the messaging service | A run that cannot finish in the window | Report a timeout as `task.error`; keep the Stop action and the cap |
| No model key on the phone | Task runs no actions | Settings check on the Backend card; the demo model still proves transport and wake |
| Refresh reuse revokes the token family | The phone drops to "pair again" | One client per process; refresh exactly once per 401; never retry a rotated token |
| A task acked but the process dies before the result | Sender waits forever | Accepted in v0; the sender applies its own deadline; resume is week-3 sync work |
| google-services.json in the build | CI red for everyone else | Gitignored and conditional plugin; the fetch script is documented |

## Open for the builder

Naming of the state classes, exact notification texts, the inline fallback when
the foreground service cannot start, whether `task.progress` reports each
recorded step or only the start, and the pairing/send scripts' language.
