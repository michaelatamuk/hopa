# B1 — week-1 gateway evidence

Date: 2026-11-16 (definition-of-done run). Spec:
[`../plan/backend/week1.md`](../plan/backend/week1.md) section 8. Slice plan:
[`../plan/backend/tasks/week1b.md`](../plan/backend/tasks/week1b.md).

This file records what is proven, with which command, and what still needs a
credential or a device. A box is not ticked on intention.

## Deployed instance

| Field | Value |
|---|---|
| Host | `https://hopa-backend-test.onrender.com` |
| Region | `frankfurt` |
| Plan | `free` (the web service sleeps after ~15 minutes idle) |
| Web service | `srv-dbtfbnff3r2c73b45ri0` |
| Postgres 16 | `dpg-dbtfb0brjlhs73f6biug-a`, database `hopa`, user `hopa_user`, Frankfurt |
| Deploy commit | `3f2cfcea92fde9bc586c752c1c8c2cb264948e99` (the merge of PR #35) |
| Health | `200 {"ok":true,"version":"0.1.0","db":"ok"}` |

This is a throwaway test stack, not the production Blueprint: Render's API can
create Postgres and a Docker service, but Blueprint creation is dashboard-only.
The service is wired the way `render.yaml` describes it (Docker, Frankfurt,
`/v1/health`, `DATABASE_URL` from the database, dashboard secrets). Delete it
with:

```powershell
# needs RENDER_API_KEY
curl -X DELETE -H "Authorization: Bearer $env:RENDER_API_KEY" `
  https://api.render.com/v1/services/srv-dbtfbnff3r2c73b45ri0
curl -X DELETE -H "Authorization: Bearer $env:RENDER_API_KEY" `
  https://api.render.com/v1/postgres/dpg-dbtfb0brjlhs73f6biug-a
```

## Exit check

| # | Check | Target | Result | Where it is proven |
|---|---|---|---|---|
| 1 | Scripted round-trips on the deployed EU instance | 20/20, 10 each direction | **deployed 20/20, twice in a row** (13.5 s and 11.7 s) | `exitRun` against `wss://hopa-backend-test.onrender.com/v1/gateway`; trace ids below; local test `ExitRunTest > twenty round-trips run twice over a real socket stay clean` |
| 2 | Replay: the same `task.create` 3× | 1 task, 1 delivery | passed | `PgTaskRoutingTest > the same message id three times creates one row and one delivery` |
| 3 | Cross-household attempts | 1,000 refused `E_TENANT`, 0 rows | CI runs 1,000 (`HOPA_ISOLATION_ATTEMPTS=1000`); deployed: an outsider household gets 404 for another household's trace | `TenantIsolationTest > a thousand cross-household reads leak nothing`; deployed `GET /v1/traces/{id}` with a foreign token |
| 4 | Real FCM wake on a sleeping phone | delivered, reconnect, task arrives | **proven end to end on a Xiaomi 15T Pro (Android 17)**: the phone was paired, backgrounded and locked; a task produced a `wake` span of 5256 ms, the socket reconnected, the task was delivered and acked (trace `fa130edad4a341614902b8959adea77b`). Execution while locked is refused (`E_DEVICE`), as designed | `send-task.ps1` against the deployed gateway; spans `accept, route, wake, deliver, accept, route, ack` in `GET /v1/traces/{id}` |
| 5 | Route latency in-region | p95 < 150 ms | **deployed p50 23 ms, p95 117 ms, max 204 ms** over 40 route spans (second, warm run); one cold-start message was 318 ms | trace `durationMs` from `/v1/traces`, warm run |
| 6 | No secrets or payloads in logs | a day's logs are clean | **last 100 log entries: 0** occurrences of `Bearer `, either access token or the task body | `GET /v1/logs?ownerId=…&resource=srv-dbtfbnff3r2c73b45ri0` scan |
| 7 | Migrations | fresh and an existing schema both apply, no manual SQL | deployed: Flyway read `<< Empty Schema >>`, created the history table and applied V1 at boot; a second boot logged `Schema "public" is up to date. No migration necessary.` | boot log lines below; second deploy |
| 8 | No client regression | HOPA-Bench 20/20 | **deferred: `bench run` drives a HarmonyOS device through the bridge; no device in this environment** | not run in this slice |

Row 4 is closed end to end: the server side (credential, project, send,
invalid-token clearing, wake span) and the client side (pairing, socket, FCM
registration, wake, delivery, ack) both ran on the deployed stack and a physical
phone. Row 8 still needs a HarmonyOS device.

## The phone run (2026-11-17)

| Item | Value |
|---|---|
| Phone | Xiaomi 2506BPN68G (15T Pro), Android 17 / API 37, Google Play services present |
| Install | `tools/scripts/android-device-checks.ps1 -InstallOnly` — the exec-in stager, because the host blocks `adb install`; HyperOS still needs **Install via USB** and one confirmation tap |
| App | `0.1.0-261117-2114` (wake run) and `0.1.0-261118-0951` (visible-screen run), `applicationId com.hopa.android` |
| Pairing | `pair-device.ps1 -LinkOnly` → the pairing code pasted via `adb shell am start` extras; `Gateway: not an X25519 public key` exposed a real bug (desktop `XECPublicKey` vs Android's X.509-only key), fixed in `30d44c3` |
| FCM token | fetched on start (`7cc3935`); before that the card said "FCM waiting for a token" and no wake was possible |
| Socket | `device.hello` → `hello.ack`; a delivered task was acked `state=delivered` |

Two runs:

| Run | Sender saw | Spans |
|---|---|---|
| awake, delivered | `ack state=delivered` | `accept, route, deliver, …` |
| backgrounded and locked | `ack state=queued`, then `E_DEVICE`, trace `00-fa130edad4a341614902b8959adea77b-…` | `accept 1ms, route 5462ms, **wake 5256ms**, deliver 9ms, accept 2ms, route 20ms, ack 1ms, accept 1ms, route 93ms` |

The wake is the real FCM path: the phone was off the socket for more than the
30 s background grace, the push took 5256 ms on the free plan, the socket came
back, the task was delivered and acked, and the locked phone answered
`E_DEVICE` instead of pretending to run.

**Closed on 2026-11-18:** the fix wave on `38adc3d` gives the planner, the
Running apps pill and `Surfaces.resolve` one screen-access answer (HOPA's switch
on, Android's service on, service bound). On the same phone, build
`0.1.0-261118-0951`, with "How apps open" set to **Here**, a delivered task ran
instead of refusing: `ack state=delivered`, then `result` (trace
`d6caa7a4348d40d88a84498a6ac0ee49`). The phone still has no provider key, so the
result is the stub model's answer; what this run proves is the surface half —
the stored identity reconnects after a reboot, the runner resolves the visible
screen, and a terminal frame comes back.

Two refusals seen while getting there are the fix working as designed: with
`bridge` selected and no PC bridge the task was refused `E_DEVICE` (no surface),
and with Android's service on but HOPA's own switch off it was refused
`E_CAPABILITY "screen control is off; turn on HOPA's screen access"` rather than
the generic text.

Device note for the next run: HyperOS clears `enabled_accessibility_services`
on force-stop and on reinstall, and after re-setting it the app can report the
service off while the system still lists it bound. Toggling HOPA's screen-access
switch off and on rebinds it; a reboot is the reliable reset. Check both
`dumpsys accessibility` ("Bound services") and the app's Screen access page
("Android service: On", "Connection: Live") before sending a task.

## Carried out of week 1

| Item | Why it left this slice | Where it is picked up |
|---|---|---|
| Row 8: HOPA-Bench 20/20 | `bench run` drives a HarmonyOS device through the bridge; no device here | week 3 demo gate |

The wake check, now run:

1. Install the app, grant notifications, let it register a push token.
2. Background the app, turn the screen off, let the socket drop (30 s grace).
3. Send a `task.create` from the PC to that device.
4. Expect one `wake` span for that trace, a reconnect, the task arriving and an
   ack. Done on 2026-11-17; the trace is above.

## Deployed trace ids (second run)

```
ok 1/20  trace=00-9063e9b62d06f8e04bfc7b3702d463eb-23084b0a332decac-01
ok 2/20  trace=00-0746d100708c0aece2ef12651b48141d-751dff8cf35036f2-01
ok 3/20  trace=00-c0de72e8b05e6044cc41ba524d5e128b-19050197f47284cc-01
ok 4/20  trace=00-9ce0b31f48b3d8aaa512a94a29652483-fc8eff5f61753dbe-01
ok 5/20  trace=00-0887bc844c3d31d0087232dde67d5dc7-0fdcb8d2e3322bf5-01
ok 6/20  trace=00-72eef4f285b50f55bdf2baaf08803c32-dc028ee96bbed959-01
ok 7/20  trace=00-b669f11aa7a806a320013f658e0335cd-ca2176198fc61489-01
ok 8/20  trace=00-103fface50a9bce819506405c50e7de7-44af6899c0253679-01
ok 9/20  trace=00-d75914ea90069eb5e9c59e4633f4ca1e-daf388fda2fe2576-01
ok 10/20 trace=00-533eae4bd7b8de40dcd2cc3510c99b56-3612f93b0db70c76-01
ok 11/20 trace=00-26e80d0395c6c5e49e99c7ef14566506-5bb97deaecd1ec60-01
ok 12/20 trace=00-d55c6c15156c5ac2c21340eaa12d4bdd-670fa934acec0d3d-01
ok 13/20 trace=00-729cd0bd1e321e26ea44f3bb5f219628-1c368ddd469db243-01
ok 14/20 trace=00-ec5192574ffbf5337bdfda5cc1b74777-d567088fd3b7e854-01
ok 15/20 trace=00-f5cd6d798a31441fd648ad3e3153d28d-a132e0c9dcb19b3a-01
ok 16/20 trace=00-0ff05dd8a0e38603654e64c19405880f-4c8522477e5c3fa6-01
ok 17/20 trace=00-e3e3e00799e6c1fa1ef5c0abe680057b-4384073b4bf79acf-01
ok 18/20 trace=00-6742154a8684a1f425d2d656f50a2b5a-2735d4bc3232808c-01
ok 19/20 trace=00-3cf297193d387b2b856812b44d191b6b-7a46fce26680bc2f-01
ok 20/20 trace=00-6853f45e18f9ad30c37220cd6b27e77a-2c0c7ff8fc6003e1-01
```

## Latency (second, warm run)

`GET /v1/traces/{traceId}` for the 20 traces above, reading `durationMs`:

| Operation | n | p50 | p95 | max |
|---|---|---|---|---|
| `accept` | 40 | 1 ms | 27 ms | 84 ms |
| `route` | 40 | 23 ms | 117 ms | 204 ms |
| `deliver` | 20 | 8 ms | 97 ms | 97 ms |
| `ack` | 20 | 1 ms | 92 ms | 92 ms |

The first message after the free service wakes is the outlier (318 ms in the
cold run). The target is p95 < 150 ms and the warm run meets it, on the free
plan; a `starter` instance should remove the ~100–200 ms scheduler spikes. Two
`accept` spans per round are expected: the gateway accepts the `task.create` and
the target's `task.ack` as separate frames.

## FCM on the deployed instance

The two dashboard variables are set on the service (`HOPA_FCM_PROJECT` and
`HOPA_FCM_CREDENTIALS`, never in the repository). With them the deploy proves:

- Service-account exchange: `oauth_status=200` against `oauth2.googleapis.com`,
  and the credential's `project_id` equals `HOPA_FCM_PROJECT`.
- Send: `messages:send` to a well-formed synthetic token answers
  `404 NOT_FOUND / UNREGISTERED`. The backend maps exactly that pair to
  `InvalidToken`.
- Wake: a `task.create` to an offline device whose `devices.push_token` was a
  synthetic token recorded `accept`, `route` and `wake` spans; the wake took
  3999 ms on the free plan (it includes the first OAuth exchange).
- Token clearing: the next `task.create` to the same device recorded `accept`
  and `route` only. FCM's `UNREGISTERED` had cleared the token, so no wake was
  attempted, which is the W1.5 behaviour.

What is missing for row 4 is only the client half: a physical Android device
holding a real registration token, asleep, receiving the wake and reconnecting.
That half belongs to the Android track; see "Carried out of week 1" above.

## Migration log

First boot, empty database:

```
Schema history table "public"."flyway_schema_history" does not exist yet
Successfully validated 1 migration (execution time 00:00.498s)
Current version of schema "public": << Empty Schema >>
Migrating schema "public" to version "1 - init"
Successfully applied 1 migration to schema "public", now at version v1
```

Second boot, same database:

```
Successfully validated 1 migration (execution time 00:00.498s)
Current version of schema "public": 1
Schema "public" is up to date. No migration necessary.
```

## Commands

```powershell
# Whole backend suite (Docker Desktop must be running; Testcontainers)
apps\android\gradlew.bat -p services/backend :test --rerun-tasks

# One thousand isolation attempts explicitly
$env:HOPA_ISOLATION_ATTEMPTS = "1000"
apps\android\gradlew.bat -p services/backend :test --tests "*TenantIsolationTest*"

# Deployed exit run (pair two devices first; the harness now acks, so it re-runs)
apps\android\gradlew.bat -p services/backend exitRun --args="--url wss://hopa-backend-test.onrender.com/v1/gateway --token-a <a> --token-b <b> --rounds 20"

# Contracts and repo checks
python tools/checks/check-gateway-fixtures.py
python tools/checks/check-links.py
python services/tools/checks/check-openapi.py
python services/tools/checks/check-render.py

# Image smoke (no database: db must be "unconfigured")
docker build -f services/backend/Dockerfile -t hopa-backend .
docker run -d --rm -p 18083:8080 -e PORT=8080 hopa-backend
curl http://127.0.0.1:18083/v1/health
```

## Notes

- The gateway is one Render web service in Frankfurt with Render Postgres 16 in
  the same region; `render.yaml` is the source of truth. The test stack above was
  created through the API, so it is not a Blueprint sync.
- The container builds on distroless Java 21 and boots on the free 512 MB plan.
- The free web service sleeps when idle. Warm `/v1/health` before the exit run;
  move to `starter` before beta.
- The exit run acks every delivered task, so a second run does not receive the
  first run's tasks again. Before that fix the second run failed on a stale
  `task.create`, which is correct at-least-once behaviour surfaced by a harness
  that never acked.
- RLS is enabled and forced on all ten tables. Production connects as the
  Render-created role; the `rolsuper` check in
  [`backend-deploy.md`](../../../services/docs/guides/backend-deploy.md) must
  return `false`. Render reset external `psql` connections from this network, so
  the check belongs in the dashboard shell; the deployed trace query above shows
  the tenant policy refusing another household.
- `queueDepth` is returned by `/v1/health` only when the request carries a valid
  token; an unauthenticated global count would need an RLS bypass.
- Deviations from the plan's checklist, unchanged: the backend CI job uses
  Testcontainers rather than a Postgres service container, and `ktlintCheck` is
  not wired because the shared catalog has no ktlint plugin.
