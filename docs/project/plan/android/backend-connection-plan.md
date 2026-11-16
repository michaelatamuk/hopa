# Android backend connection and FCM wake — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pair the HOPA Android app with the deployed backend, connect it to the gateway, run a delivered task on the configured surface, and prove an FCM wake on the Xiaomi 15T Pro.

**Architecture:** A pure-JVM `BackendClient` in `:capabilities` (OkHttp WebSocket, no Android imports) beside `BridgeClient`, wired by an app-level `BackendSession` in `AppContainer`; a `FirebaseMessagingService` that reconnects for a wake; `RemoteTaskRunner` that reuses `AgentLoop` on the same surface `ChatViewModel` uses; two PC scripts that mint the pairing link and send a task.

**Tech Stack:** Kotlin 2.0.21 / JVM 17, OkHttp 4.12.0, kotlinx.serialization 1.7.3, MockWebServer, Firebase BOM 33.7.0 + messaging, Google services plugin 4.4.2, AGP 8.7.3.

**Spec:** `docs/project/plan/android/backend-connection-design.md`

## Global Constraints

- Every file has one public declaration and its name matches the file (`check-android-structure.py`, `check-structure.py`); files stay under 250 lines.
- `:capabilities` is pure JVM: no `android.*` imports. `:core:check` enforces the same rule for `:core`.
- Tokens, the pairing session and the private key never appear in logs, notifications, commit messages or fixture files.
- Protocol v1 is frozen: envelope fields `v, id, type, ts, trace, from, to, task, body`; no backend change.
- Deployed gateway: `wss://hopa-backend-test.onrender.com/v1/gateway`; HTTP base derived by `https` + stripping `/v1/gateway`.
- Android: `minSdk 29`, `targetSdk 35`, applicationId `com.hopa.android`, Kotlin `jvmTarget 17`.
- Run cap 170 s inside `RunService` (`shortService`, 3-minute platform limit); approval wait 120 s inside it.
- Test commands: `apps\android\gradlew.bat -p apps/android :capabilities:test`, `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest`, `apps\android\gradlew.bat -p apps/android :app:assembleDebug`.

## Review Focus

1. A malformed or unknown frame from the server must not close the socket — the person loses presence and the next wake is the only way back.
2. A redelivered `task.create` must never execute twice — double taps on a phone are a safety bug.
3. A rotated refresh token must never be presented twice — the server revokes the device family on reuse.
4. A task delivered while HOPA is not foreground must still be acked — otherwise the sender sees "delivered" and waits forever.
5. Pairing text pasted from a QR with parameters in any order, or a bare session with the URL already in settings, must both parse.

---

### Task 1: Pairing link, credentials and X25519 keys

**Files:**
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendCredentials.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/PairingLink.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/PairingLinks.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/X25519Keys.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendPairing.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/PairingLinksTest.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendPairingTest.kt`

**Interfaces:**
- Produces:
  - `data class PairingLink(val householdId: String, val session: String, val gatewayUrl: String)`
  - `object PairingLinks { fun parse(text: String, fallbackGatewayUrl: String = ""): PairingLink? }`
  - `data class BackendCredentials(val deviceId: String, val householdId: String, val accessToken: String, val refreshToken: String, val expiresAt: String, val gatewayUrl: String)`
  - `object BackendUrls { fun httpBase(gatewayUrl: String): String }`
  - `object X25519Keys { fun generate(): Pair; fun publicKeyBytes(publicKey: java.security.PublicKey): ByteArray; fun privateKeyBase64(privateKey: java.security.PrivateKey): String }` where `Pair` is `java.security.KeyPair`
  - `class BackendPairing(private val gatewayUrl: String, private val http: okhttp3.OkHttpClient = ...) { suspend fun claim(link: PairingLink, name: String, publicKey: String, pushToken: String?): BackendCredentials; suspend fun refresh(refreshToken: String): BackendCredentials }`

- [ ] **Step 1: Write the failing parse tests**

```kotlin
@Test fun `parses a hopa pairing uri`() {
    val link = PairingLinks.parse(
        "hopa://pair?v=1&h=h1&p=s1&u=wss%3A%2F%2Fexample.test%2Fv1%2Fgateway",
    )!!
    assertEquals("h1", link.householdId)
    assertEquals("s1", link.session)
    assertEquals("wss://example.test/v1/gateway", link.gatewayUrl)
}

@Test fun `parameter order does not matter`() {
    val link = PairingLinks.parse("hopa://pair?u=wss://a/v1/gateway&p=s1&v=1&h=h1")!!
    assertEquals("s1", link.session)
}

@Test fun `a bare session uses the configured gateway`() {
    val link = PairingLinks.parse("s1", "wss://fallback/v1/gateway")!!
    assertEquals("s1", link.session)
    assertEquals("wss://fallback/v1/gateway", link.gatewayUrl)
}

@Test fun `garbage is refused`() {
    assertNull(PairingLinks.parse("hello", ""))
    assertNull(PairingLinks.parse("", ""))
}
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*PairingLinksTest*"`
Expected: FAIL, unresolved reference `PairingLinks`.

- [ ] **Step 3: Implement `PairingLinks` and `BackendUrls`**

Use `java.net.URI`; read query parameters by splitting on `&` and `=`, decoding with `java.net.URLDecoder` (UTF-8), so order and encoding both work. `BackendUrls.httpBase` replaces `wss://` with `https://`, `ws://` with `http://`, keeps host and port, drops everything from `/v1/`.

- [ ] **Step 4: Write the claim test against MockWebServer**

```kotlin
@Test fun `claim sends the session and returns the tokens`() = runBlocking {
    val server = MockWebServer().apply {
        enqueue(MockResponse().setResponseCode(201).setBody("""
            {"deviceId":"d1","householdId":"h1","accessToken":"a1","refreshToken":"r1",
             "expiresAt":"2026-11-17T00:00:00Z"}""".trimIndent()))
        start()
    }
    val pairing = BackendPairing("wss://${server.hostName}:${server.port}/v1/gateway")
    val credentials = pairing.claim(link(server), name = "Xiaomi 15T Pro", publicKey = "pk", pushToken = "tok")
    assertEquals("a1", credentials.accessToken)
    assertEquals("wss://${server.hostName}:${server.port}/v1/gateway", credentials.gatewayUrl)
    val request = server.takeRequest()
    assertEquals("/v1/pair/claim", request.path)
    assertTrue(request.body.readUtf8().contains("\"pushToken\":\"tok\""))
    server.shutdown()
}

@Test fun `refresh posts the refresh token and keeps the gateway`() = runBlocking { /* same shape, path /v1/token/refresh */ }

@Test fun `a refused claim throws with the server message`() = runBlocking { /* 400 + {"code":"E_SCHEMA","message":"expired"} */ }
```

- [ ] **Step 5: Run the tests and watch them fail**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendPairingTest*"`
Expected: FAIL, unresolved reference `BackendPairing`.

- [ ] **Step 6: Implement `BackendPairing` and `X25519Keys`**

`claim` posts `{session, platform:"android", name, publicKey, alg:"x25519", pushToken}` to `httpBase + "/v1/pair/claim"` with a 20 s call timeout, and maps `201` to `BackendCredentials` (gateway from the constructor). Non-2xx throws `IllegalStateException("pairing refused: <code> <message>")` without echoing the session. `refresh` posts `{refreshToken}` to `/v1/token/refresh` and never logs the token. `X25519Keys.generate` uses `KeyPairGenerator.getInstance("X25519")`; `publicKeyBytes` reads `XECPublicKey.u` big-endian into 32 bytes; `privateKeyBase64` encodes `PKCS8EncodedKeySpec` bytes.

- [ ] **Step 7: Add the key-shape test**

```kotlin
@Test fun `an x25519 public key is thirty two bytes and a private key round-trips`() {
    val pair = X25519Keys.generate()
    assertEquals(32, X25519Keys.publicKeyBytes(pair.public).size)
    assertTrue(X25519Keys.privateKeyBase64(pair.private).isNotBlank())
}
```

- [ ] **Step 8: Run the package tests**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend
git commit -m "feat(backend-client): parse pairing links, claim a session, generate x25519 keys"
```

---

### Task 2: Envelope codec

**Files:**
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendEnvelope.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendCodec.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendCodecTest.kt`

**Interfaces:**
- Produces:
  - `data class BackendEnvelope(val v: Int = 1, val id: String, val type: String, val ts: Long, val trace: String? = null, val from: String? = null, val to: String? = null, val task: String? = null, val body: JsonObject = JsonObject(emptyMap()))`
  - `object BackendCodec { fun encode(frame: BackendEnvelope): String; fun decodeOrNull(text: String): BackendEnvelope?; fun hello(name: String, appVersion: String, pushToken: String?): BackendEnvelope }`

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `round trip keeps the fields and drops nothing`() {
    val frame = BackendEnvelope(id = "i1", type = "task.ack", ts = 5, trace = "t1", to = "d2", task = "k1",
        body = buildJsonObject { put("state", "delivered") })
    val decoded = BackendCodec.decodeOrNull(BackendCodec.encode(frame))!!
    assertEquals(frame, decoded)
}

@Test fun `unknown extra fields are ignored`() {
    val decoded = BackendCodec.decodeOrNull("""{"v":1,"id":"i1","type":"presence","ts":1,"extra":true}""")!!
    assertEquals("presence", decoded.type)
}

@Test fun `a frame with no type or id is refused`() {
    assertNull(BackendCodec.decodeOrNull("""{"v":1,"id":"i1","ts":1}"""))
    assertNull(BackendCodec.decodeOrNull("not json"))
    assertNull(BackendCodec.decodeOrNull(""))
}

@Test fun `hello carries the push token only when there is one`() {
    val withToken = BackendCodec.hello("Xiaomi 15T Pro", "0.1.0", "tok")
    assertEquals("tok", withToken.body["pushToken"]!!.jsonPrimitive.content)
    assertNull(BackendCodec.hello("Xiaomi 15T Pro", "0.1.0", null).body["pushToken"])
}
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendCodecTest*"`
Expected: FAIL, unresolved reference `BackendCodec`.

- [ ] **Step 3: Implement the codec**

Build the JSON with `buildJsonObject` and ignore unknown keys on decode; `decodeOrNull` returns null on any exception. `hello` sets `body` to `platform:"android", name, appVersion` plus `pushToken` when non-null, and `type:"device.hello"`, `id = UUID.randomUUID().toString()`, `ts = System.currentTimeMillis()`.

- [ ] **Step 4: Run the tests**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendCodecTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendEnvelope.kt packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendCodec.kt packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendCodecTest.kt
git commit -m "feat(backend-client): encode and decode the v1 gateway envelope"
```

---

### Task 3: Client connects, says hello, and reports live

**Files:**
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendState.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendLink.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendClient.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendClientTest.kt`

**Interfaces:**
- Consumes: `BackendCodec`, `BackendEnvelope`, `BackendCredentials`.
- Produces:
  - `sealed interface BackendState { data object Disconnected; data object Connecting; data class Live(val deviceId: String, val queued: Int, val peers: List<String>); data class Failed(val reason: String, val needsPairing: Boolean) }`
  - `interface BackendLink { val state: StateFlow<BackendState>; var onTask: (suspend (BackendEnvelope) -> Unit)?; fun start(scope: CoroutineScope); suspend fun stop(); suspend fun send(frame: BackendEnvelope) }`
  - `class BackendClient(private val credentials: () -> BackendCredentials?, private val pushToken: () -> String?, private val refresh: suspend () -> BackendCredentials?, private val deviceName: String, private val appVersion: String, private val http: OkHttpClient = defaultHttp()) : BackendLink`
  - `BackendClient.defaultHttp()` builds OkHttp with 15 s connect and 0 s read (WebSocket pings keep it alive).

- [ ] **Step 1: Write the failing hello test**

```kotlin
@Test fun `hello is answered and the client goes live`() = runBlocking {
    val server = MockWebServer()
    val seen = CompletableDeferred<JsonObject>()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            runBlocking { seen.complete(Json.decodeFromString<JsonObject>(text)) }
            webSocket.send("""{"v":1,"id":"a1","type":"device.hello.ack","ts":1,"to":"dev-1",
                "body":{"session":"s","serverTime":1,"heartbeatSec":25,"queued":2,"peers":["dev-2"]}}""".trimIndent())
        }
    }))
    val client = clientFor(server)
    client.start(this)
    withTimeout(5_000) { client.state.first { it is BackendState.Live } }
    assertEquals("device.hello", seen.await()["type"]!!.jsonPrimitive.content)
    assertEquals("dev-1", (client.state.value as BackendState.Live).deviceId)
    assertEquals(2, (client.state.value as BackendState.Live).queued)
    client.stop()
    server.shutdown()
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendClientTest*"`
Expected: FAIL, unresolved reference `BackendClient`.

- [ ] **Step 3: Implement connect + hello**

`start` launches a coroutine that opens `credentials()!!.gatewayUrl` with `Authorization: Bearer <accessToken>`, sends `BackendCodec.hello(deviceName, appVersion, pushToken())`, and parses frames with `BackendCodec.decodeOrNull`; a null decode is ignored, never fatal. On `device.hello.ack` read `to`, `body.queued`, `body.peers` and publish `Live`. `state` starts `Disconnected` and moves to `Connecting` before the handshake.

- [ ] **Step 4: Run the test**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendClientTest*"`
Expected: PASS.

- [ ] **Step 5: Add the malformed-frame test (Review Focus 1)**

```kotlin
@Test fun `a malformed frame does not close the socket`() = runBlocking {
    // server sends "not json", then a valid hello.ack; assert state becomes Live
}
```

- [ ] **Step 6: Run it, then commit**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendClientTest*"`
Expected: PASS.

```bash
git add packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendState.kt packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendClient.kt packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendClientTest.kt
git commit -m "feat(backend-client): connect to the gateway and go live on hello.ack"
```

---

### Task 4: Deliveries, acks and replies

**Files:**
- Modify: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendClient.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendReplies.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendDeliveriesTest.kt`

**Interfaces:**
- Produces:
  - `var onTask: (suspend (BackendEnvelope) -> Unit)?` on `BackendClient`
  - `suspend fun ack(task: BackendEnvelope)` — sends `task.ack` with `trace = task.trace`, `to = task.from`, `task = task.task`, body `{"state":"delivered"}`
  - `object BackendReplies { fun progress(task: BackendEnvelope, deviceId: String, note: String): BackendEnvelope; fun result(task: BackendEnvelope, deviceId: String, summary: String): BackendEnvelope; fun error(task: BackendEnvelope, deviceId: String, code: String, message: String, retryable: Boolean): BackendEnvelope }`
  - `suspend fun ping()` — sends `{"type":"ping"}` and expects `pong`

- [ ] **Step 1: Write the failing delivery test**

```kotlin
@Test fun `a delivered task is handed to onTask and acked under its trace`() = runBlocking {
    val received = CompletableDeferred<BackendEnvelope>()
    val server = MockWebServer()
    val acked = CompletableDeferred<JsonObject>()
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"v":1,"id":"a1","type":"device.hello.ack","ts":1,"to":"dev-1","body":{"queued":1,"peers":[]}}""")
            webSocket.send("""{"v":1,"id":"m1","type":"task.create","ts":2,"from":"dev-9","to":"dev-1",
                "task":"task-1","trace":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "body":{"goal":"open settings"}}""")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = Json.decodeFromString<JsonObject>(text)
            if (frame["type"]!!.jsonPrimitive.content == "task.ack") runBlocking { acked.complete(frame) }
        }
    }))
    val client = clientFor(server)
    client.onTask = { task -> received.complete(task); client.ack(task) }
    client.start(this)
    val frame = withTimeout(5_000) { received.await() }
    assertEquals("open settings", frame.body["goal"]!!.jsonPrimitive.content)
    val ack = withTimeout(5_000) { acked.await() }
    assertEquals("task-1", ack["task"]!!.jsonPrimitive.content)
    assertEquals("dev-9", ack["to"]!!.jsonPrimitive.content)
    client.stop(); server.shutdown()
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendDeliveriesTest*"`
Expected: FAIL, unresolved `onTask`/`ack`.

- [ ] **Step 3: Implement delivery dispatch, ack and the reply builders**

Dispatch `task.create` on a coroutine so a slow handler cannot stall the reader; never call `onTask` for other types. Reply builders set `from = deviceId`, `to = task.from!!`, `task = task.task!!`, `trace = task.trace`, and the body named in the signature (`{"summary":…}`, `{"code":…,"message":…,"retryable":…}`, `{"step":1,"note":…}` for progress). `ping()` sends a `ping` and completes when a `pong` arrives, with a 10 s timeout.

- [ ] **Step 4: Run the tests**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test`
Expected: PASS.

- [ ] **Step 5: Add the "unknown type is ignored" test**

Server sends `type:"task.needs_approval"`, then a valid hello.ack; assert `Live` and `onTask` not called.

- [ ] **Step 6: Commit**

```bash
git add packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend
git commit -m "feat(backend-client): dispatch delivered tasks, ack them, and build result frames"
```

---

### Task 5: Refresh once, reconnect, wake window, and the processed log

**Files:**
- Modify: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/BackendClient.kt`
- Create: `packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend/ProcessedTaskLog.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/BackendReconnectTest.kt`
- Test: `packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend/ProcessedTaskLogTest.kt`

**Interfaces:**
- Produces:
  - `BackendClient(..., private val refresh: suspend () -> BackendCredentials?)`; on close code 1008 with reason `E_AUTH` it calls `refresh()` once, and if it returns null publishes `Failed(needsPairing = true)` and stops.
  - `class ProcessedTaskLog(private val load: suspend () -> Map<String, Long>, private val save: suspend (Map<String, Long>) -> Unit, private val now: () -> Long = System::currentTimeMillis, private val ttlMs: Long = 86_400_000) { suspend fun claim(taskId: String): Boolean }`

- [ ] **Step 1: Write the failing reconnect test (Review Focus 3)**

```kotlin
@Test fun `an E_AUTH close refreshes once and the second one asks for pairing`() = runBlocking {
    var refreshes = 0
    // two responses: each upgrades then closes 1008 "E_AUTH"
    val client = clientFor(server, refresh = { refreshes++; if (refreshes == 1) credentials("a2") else null })
    client.start(this)
    withTimeout(5_000) { client.state.first { it is BackendState.Failed && it.needsPairing } }
    assertEquals(2, refreshes)
    client.stop(); server.shutdown()
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test --tests "*BackendReconnectTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement backoff and the single refresh**

Reconnect delays 1/2/4/8 s capped at 30 s; a refresh happens at most once per `E_AUTH` event and the new credentials replace the old ones for the next connect. Any other close reconnects without refreshing.

- [ ] **Step 4: Write the processed-log tests (Review Focus 2)**

```kotlin
@Test fun `the first claim is true and the second is false`() = runBlocking {
    val log = ProcessedTaskLog(load = { emptyMap() }, save = { }, now = { 1_000 })
    assertTrue(log.claim("t1")); assertFalse(log.claim("t1"))
}

@Test fun `an entry older than a day can run again`() = runBlocking {
    var stored = mapOf("t1" to 0L)
    val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 86_400_001L })
    assertTrue(log.claim("t1"))
}
```

- [ ] **Step 5: Implement `ProcessedTaskLog` and run all client tests**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add packages/capabilities/src/main/kotlin/com/hopa/capabilities/backend packages/capabilities/src/test/kotlin/com/hopa/capabilities/backend
git commit -m "feat(backend-client): reconnect with backoff, refresh once per auth close, and dedupe tasks"
```

---

### Task 6: Persist the backend identity

**Files:**
- Modify: `packages/data/src/main/kotlin/com/hopa/data/settings/AppSettings.kt`
- Modify: `packages/data/src/main/kotlin/com/hopa/data/settings/SettingsRepository.kt`
- Modify: `apps/android/data/src/main/kotlin/com/hopa/data/settings/DataStoreSettingsRepository.kt`
- Test: `tools/checks/check-settings-fields.py` (existing check, run as the test)

**Interfaces:**
- Produces on `AppSettings`: `backendUrl: String = ""`, `backendDeviceId: String = ""`, `backendHouseholdId: String = ""`.
- Produces on `SettingsRepository`: `suspend fun backendAccessToken(): String`, `setBackendAccessToken(value: String)`, `backendRefreshToken(): String`, `setBackendRefreshToken(value: String)`, `backendPrivateKey(): String`, `setBackendPrivateKey(value: String)`, `clearBackend()`.

- [ ] **Step 1: Run the settings check to see the baseline**

Run: `.\.venv\Scripts\python.exe tools/checks/check-settings-fields.py`
Expected: `0 setting storage problem(s)`.

- [ ] **Step 2: Add the three fields and the six secret accessors**

DataStore keys `backend_url`, `backend_device_id`, `backend_household_id`; secret aliases `backend_access`, `backend_refresh`, `backend_private_key`. `clearBackend` blanks the token, refresh and key and clears the three fields.

- [ ] **Step 3: Run the check again**

Run: `.\.venv\Scripts\python.exe tools/checks/check-settings-fields.py`
Expected: `0 setting storage problem(s)`.

- [ ] **Step 4: Build the data module**

Run: `apps\android\gradlew.bat -p apps/android :data-android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add packages/data/src/main/kotlin/com/hopa/data/settings apps/android/data/src/main/kotlin/com/hopa/data/settings
git commit -m "feat(data): store the backend device identity and keep its tokens in the keystore"
```

---

### Task 7: `BackendSession` in the app container

**Files:**
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/backend/BackendSession.kt`
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/di/AppContainer.kt`
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/MainActivity.kt` (`onStart` → `onForeground`, `onStop` → `onBackground`)
- Modify: `apps/android/app/src/main/AndroidManifest.xml` (INTERNET is already there; no change expected)
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/backend/BackendSessionTest.kt`

**Interfaces:**
- Consumes: `SettingsRepository` (Task 6), `BackendPairing`, `BackendLink`/`BackendClient`.
- Produces:
  - `class BackendSession(private val settings: SettingsRepository, private val pairingFactory: (String) -> BackendPairing, private val linkFactory: (BackendCredentials, String, String) -> BackendLink, private val deviceName: String, private val appVersion: String)`
  - `val state: StateFlow<BackendState>`; `val task: StateFlow<BackendTaskState>`; `var onTask: (suspend (BackendEnvelope) -> Unit)?`
  - `suspend fun pair(link: PairingLink): Result<Unit>`; `fun onForeground()`; `fun onBackground()`; `suspend fun disconnect()`
  - `fun wake(scope: CoroutineScope)` — opens the link for a wake, keeps it while `taskInFlight` is true, and stops it 45 s after the queue drains when nothing is running.
  - `fun taskStarted()` / `fun taskFinished()` — the runner brackets a run with these so the wake window knows to stay open.
  - `val deviceId: String`; `val gatewayUrl: String`
  - `data class BackendTaskState(val taskId: String = "", val goal: String = "", val state: String = "idle", val note: String = "")`
- `AppContainer` gains `val backend: BackendSession by lazy { ... }` and wires `backend.onTask = { task -> runner.handle(task) }` after both exist.

- [ ] **Step 1: Write the failing session test**

```kotlin
@Test fun `pairing stores the credentials and reports the device`() = runBlocking {
    val settings = FakeSettings()             // in-memory SettingsRepository in this test file
    val session = BackendSession(settings, pairingFactory = { FakePairing() },
        linkFactory = { _, _, _ -> FakeLink() }, deviceName = "Xiaomi", appVersion = "0.1.0")
    val result = session.pair(PairingLink("h1", "s1", "wss://x/v1/gateway"))
    assertTrue(result.isSuccess)
    assertEquals("d1", settings.backendDeviceId)
    assertEquals("a1", settings.backendAccessToken())
}

@Test fun `a failed pair leaves the old identity alone`() = runBlocking { /* pairing throws; assert device id unchanged */ }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*BackendSessionTest*"`
Expected: FAIL, unresolved reference `BackendSession`.

- [ ] **Step 3: Implement `BackendSession`**

`pair` generates a keypair with `X25519Keys`, claims, stores device/household/url in settings plus tokens and the private key in the secrets, and leaves everything untouched on failure. `onForeground` creates the link from the stored credentials (with `pushToken = { FirebaseToken.current() }`, `refresh = { pairing.refresh(stored refresh) persisted }`) and starts it; `onBackground` schedules `disconnect` 30 s later unless a run is in flight. `wake` starts the same link and arms the 45 s idle stop.

- [ ] **Step 4: Run the tests**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*BackendSessionTest*"`
Expected: PASS.

- [ ] **Step 5: Wire `AppContainer`, the activity lifecycle, and build**

Add `val backend = BackendSession(settings, ::BackendPairing, { c, n, v -> BackendClient(...) }, Build.MODEL, BuildConfig.VERSION_NAME)`; expose `val firebaseAvailable: Boolean` (Task 8 fills it). `MainActivity.onStart` calls `container.backend.onForeground()` and `onStop` calls `onBackground()`.

Run: `apps\android\gradlew.bat -p apps/android :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add apps/android/app/src/main/kotlin/com/hopa/app/backend apps/android/app/src/main/kotlin/com/hopa/app/di/AppContainer.kt apps/android/app/src/test/kotlin/com/hopa/app/backend
git commit -m "feat(app): add the backend session that pairs, connects and wakes"
```

---

### Task 8: Firebase messaging and the wake service

**Files:**
- Modify: `apps/android/gradle/libs.versions.toml`
- Modify: `apps/android/build.gradle.kts`
- Modify: `apps/android/app/build.gradle.kts`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/backend/FirebaseToken.kt`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/backend/WakeRequest.kt`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/backend/HopaMessagingService.kt`
- Create: `tools/scripts/fetch-google-services.ps1`
- Modify: `.gitignore`
- Modify: `apps/android/app/src/main/AndroidManifest.xml`
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/backend/WakeRequestTest.kt`

**Interfaces:**
- Produces:
  - `object FirebaseToken { fun current(): String?; fun set(value: String?) }`
  - `data class WakeRequest(val taskId: String, val traceId: String)` and `object WakeRequests { fun from(data: Map<String, String>): WakeRequest? }`
  - `class HopaMessagingService : FirebaseMessagingService` registered in the manifest with the `com.google.firebase.MESSAGING_EVENT` intent filter.
  - `fetch-google-services.ps1` writes `apps/android/app/google-services.json`.

- [ ] **Step 1: Write the failing wake-request test**

```kotlin
@Test fun `a data message with a task id is a wake`() {
    assertEquals(WakeRequest("t1", "tr1"), WakeRequests.from(mapOf("taskId" to "t1", "traceId" to "tr1")))
}

@Test fun `a message without a task id is not a wake`() {
    assertNull(WakeRequests.from(mapOf("traceId" to "tr1")))
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*WakeRequestTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement the holders and the service**

`WakeRequests.from` requires a non-blank `taskId`; `traceId` defaults to `""`. `HopaMessagingService.onNewToken` stores the token and, when connected, re-sends hello by reconnecting. `onMessageReceived` calls `container.backend.wake(scope)` where `scope` is a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` cancelled after 50 s; post a "HOPA is checking for a task" notification only when `!AppVisibility.foreground`, and never include the payload.

- [ ] **Step 4: Add Gradle and the manifest**

Catalog: `google-services = { id = "com.google.gms.google-services", version = "4.4.2" }`, `firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version = "33.7.0" }`, `firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging" }`. Root: `id("com.google.gms.google-services") version "4.4.2" apply false`. App: `implementation(platform(libs.firebase.bom)); implementation(libs.firebase.messaging)`, and at the end of the file:

```kotlin
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
```

- [ ] **Step 5: Build with and without the config**

Run: `apps\android\gradlew.bat -p apps/android :app:assembleDebug`
Expected: BUILD SUCCESSFUL without `google-services.json` (FCM off).

- [ ] **Step 6: Write the fetch script and the ignore rule**

PowerShell with .NET RSA signs the service-account JWT (scope `https://www.googleapis.com/auth/cloud-platform`), lists `projects/<id>/androidApps`, creates one with `packageName: "com.hopa.android"` when absent, then writes `configFileContents` (base64) to `apps/android/app/google-services.json`. Prints only app id and package. `.gitignore` gains `apps/android/app/google-services.json`.

- [ ] **Step 7: Run the script and build again**

Run: `.\tools\scripts\fetch-google-services.ps1` then `apps\android\gradlew.bat -p apps/android :app:assembleDebug`
Expected: `google-services.json` written, BUILD SUCCESSFUL with FCM compiled in.

- [ ] **Step 8: Commit**

```bash
git add apps/android/gradle/libs.versions.toml apps/android/build.gradle.kts apps/android/app/build.gradle.kts apps/android/app/src/main/AndroidManifest.xml apps/android/app/src/main/kotlin/com/hopa/app/backend tools/scripts/fetch-google-services.ps1 .gitignore
git commit -m "feat(app): wake from FCM and keep the firebase config out of git"
```

---

### Task 9: Remote runs on the configured surface

**Files:**
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/backend/RemoteRunPlan.kt`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/backend/RemoteTaskRunner.kt`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/di/Surfaces.kt`
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/feature/chat/ChatViewModel.kt` (use `Surfaces`)
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/di/AppContainer.kt` (`processed` store + `runner`)
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/backend/RemoteRunPlanTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface RemoteRunPlan { data object Run; data class Refuse(val code: String, val message: String, val retryable: Boolean) }`
  - `object RemoteRunPlanner { fun plan(surfaceName: String, screenPath: String, screenOn: Boolean, keyguardLocked: Boolean, accessibilityOn: Boolean, busy: Boolean): RemoteRunPlan }`
  - `class RemoteTaskRunner(...) { suspend fun handle(task: BackendEnvelope) }`
  - `AppContainer.surfaces: suspend (AppSettings) -> ScreenSourceDevice` (moved from `ChatViewModel`)

- [ ] **Step 1: Write the failing plan tests**

```kotlin
@Test fun `a hidden or bridge surface runs even with the screen off`() {
    assertEquals(RemoteRunPlan.Run, RemoteRunPlanner.plan("background", "hidden", false, true, false, false))
    assertEquals(RemoteRunPlan.Run, RemoteRunPlanner.plan("bridge", "bridge", false, true, false, false))
}

@Test fun `onscreen while locked is refused with a retryable device error`() {
    val plan = RemoteRunPlanner.plan("accessibility", "onscreen", screenOn = false, keyguardLocked = true, accessibilityOn = true, busy = false)
    assertEquals(RemoteRunPlan.Refuse("E_DEVICE", "phone is locked; send it again when you pick it up", true), plan)
}

@Test fun `no surface at all names the setup step`() {
    val plan = RemoteRunPlanner.plan("fallback", "hidden", true, false, false, false)
    assertEquals("E_DEVICE", (plan as RemoteRunPlan.Refuse).code)
    assertTrue(plan.message.contains("hidden screen"))
}

@Test fun `a busy phone is refused as retryable`() {
    assertEquals("E_DEVICE", (RemoteRunPlanner.plan("accessibility", "auto", true, false, true, busy = true) as RemoteRunPlan.Refuse).code)
}

@Test fun `screen control off on the onscreen path is a capability error`() {
    assertEquals("E_CAPABILITY", (RemoteRunPlanner.plan("fallback", "onscreen", true, false, false, false) as RemoteRunPlan.Refuse).code)
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*RemoteRunPlanTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement the planner**

Order matters and is exactly as the tests state: a live `background`/`bridge` surface always runs; `accessibility` needs the screen on, unlocked and accessibility enabled; otherwise refuse with the code and message named above (`E_CAPABILITY` when the chosen path is `onscreen` and screen control is off, `E_DEVICE` otherwise), `retryable = true` for `E_DEVICE`.

- [ ] **Step 4: Run the plan tests**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*RemoteRunPlanTest*"`
Expected: PASS.

- [ ] **Step 5: Implement `RemoteTaskRunner` and the shared surfaces helper**

`handle`: ack first (Review Focus 4), then if `processed.claim(taskId)` is false return; start `RunService` (if `startForegroundService` throws, run inline in the wake coroutine and report a timeout if it cannot finish); resolve the surface with `AppContainer.surfaces(settings)`; plan; on `Refuse` send the error frame and stop; on `Run` build the `TurnContext` exactly as `ChatViewModel` does (installed apps, memory + character, skills, typed functions, vision, `platform = "android"`, `phoneAccess = device.sourceName != "fallback"`), reload `trustedApps` into `policy`, send one `task.progress`, then `withTimeoutOrNull(170_000) { container.loopFor(device).run(...) }`; send `task.result {summary}` or `task.error E_MODEL`/`E_BUDGET` on null; always `stopService` and stop the background device. A `Mutex` serialises `handle`, so a second delivery waits instead of racing the screen; bracket the run with `backend.taskStarted()` / `taskFinished()` so the wake socket stays open. When `!AppVisibility.foreground`, post a "HOPA needs your OK" notification before the loop so an approval is visible on any tab.

- [ ] **Step 6: Add the ack-first test (Review Focus 4)**

Use a `FakeLink` that records frames: call `runner.handle(task)` with the phone locked and assert the first frame is `task.ack`, the second `task.error` with `E_DEVICE`, and that the task id is now in the processed log.

- [ ] **Step 7: Build and run the app unit tests**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/android/app/src/main/kotlin/com/hopa/app/backend apps/android/app/src/main/kotlin/com/hopa/app/di apps/android/app/src/main/kotlin/com/hopa/app/feature/chat/ChatViewModel.kt apps/android/app/src/test/kotlin/com/hopa/app/backend
git commit -m "feat(app): run a remote task on the configured screen path and report the result"
```

---

### Task 10: Settings card, approval sheet, and launch extras

**Files:**
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/feature/settings/SettingsUiState.kt`
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/feature/settings/SettingsViewModel.kt`
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/feature/runningapps/ComputerScreen.kt`
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/HopaApp.kt` (approval sheet at the root)
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/feature/chat/ChatScreen.kt` (stop composing it)
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/MainActivity.kt` (backend extras)
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/feature/settings/PairInputTest.kt`

**Interfaces:**
- Produces: `object PairInput { fun normalise(text: String, fallbackGatewayUrl: String): PairingLink? }` (delegates to `PairingLinks`), `SettingsUiState` fields `backendUrl`, `backendDeviceId`, `backendState`, `backendToken`, `fcmAvailable`.
- `MainActivity` extras: `hopa.backendUrl`, `hopa.backendPair`, `hopa.backendName`.

- [ ] **Step 1: Write the failing input test**

```kotlin
@Test fun `the field accepts a whole uri or a bare session`() {
    assertEquals("s1", PairInput.normalise("hopa://pair?p=s1&h=h1&u=wss://x/v1/gateway", "")!!.session)
    assertEquals("s1", PairInput.normalise("  s1  ", "wss://y/v1/gateway")!!.session)
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*PairInputTest*"`
Expected: FAIL.

- [ ] **Step 3: Implement the UI wiring**

The card shows URL, device id (first 8 chars), the FCM token masked with a copy action, the state, Pair / Disconnect, and a line naming the current "How apps open" setting with its setup requirement. `SettingsViewModel.pair(url, text)` calls `container.backend.pair`. Focus is refreshable without a hidden view. The approval sheet moves to `HopaApp` observing `container.approval.pending` and calling `container.approval.resolve`. `MainActivity.applyLaunchConfig` handles `hopa.backendUrl` / `hopa.backendPair` / `hopa.backendName` **before** its early return for a blank bridge URL.

- [ ] **Step 4: Build**

Run: `apps\android\gradlew.bat -p apps/android :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add apps/android/app/src/main/kotlin/com/hopa/app
git commit -m "feat(app): pair the backend from settings and show approvals on every tab"
```

---

### Task 11: PC scripts to mint a pairing link and send a task

**Files:**
- Create: `tools/scripts/pair-device.ps1`
- Create: `tools/scripts/SendTask.java`
- Create: `tools/scripts/send-task.ps1`

**Interfaces:**
- `pair-device.ps1` prints one line `hopa://pair?…` and its expiry; reads `HOPA_BACKEND_URL` (default `https://hopa-backend-test.onrender.com`) and `HOPA_BOOTSTRAP_TOKEN`.
- `send-task.ps1 -Goal "…" [-To <deviceId>]` pairs once as a `windows` device when `%TEMP%\hopa-pc-tokens.json` is absent, sends one `task.create`, prints `ack`, then `result`/`error` and the trace id.

- [ ] **Step 1: Run the pairing script against the deployed stack**

Run: `$env:HOPA_BOOTSTRAP_TOKEN="…"; .\tools\scripts\pair-device.ps1`
Expected: a `hopa://pair?…` line and an expiry ~5 minutes out; exit 0. No token printed.

- [ ] **Step 2: Build the sender and prove the refusal path**

Run: `.\tools\scripts\send-task.ps1 -Goal "open settings"`
Expected: with no phone paired, the script exits 2 and prints `no paired windows device; run send-task.ps1 -Pair first` — not a stack trace.

- [ ] **Step 3: Implement the pair mode and print the ack**

`send-task.ps1 -Pair` claims with the link from `pair-device.ps1`, writes device id plus tokens to `%TEMP%\hopa-pc-tokens.json` with `-rw` intent, and exits. Otherwise it connects to `wss://…/v1/gateway`, sends `task.create` with `to` = the phone's device id, waits for `task.ack`, then up to 200 s for `task.result`/`task.error`, and prints `trace=<trace>`.

- [ ] **Step 4: Commit**

```bash
git add tools/scripts/pair-device.ps1 tools/scripts/SendTask.java tools/scripts/send-task.ps1
git commit -m "feat(tools): mint a pairing link and send a task from the PC"
```

---

### Task 12: On the phone, then in the evidence

**Files:**
- Modify: `docs/project/evidence/B1-gateway.md` (row 4 and a new section)
- Modify: `docs/project/evidence/A0-android-running.md` (the install note points at the script and this run)

- [ ] **Step 1: Build, install, and launch**

Run: `apps\android\gradlew.bat -p apps/android :app:assembleDebug` then `.\tools\scripts\android-device-checks.ps1 -InstallOnly`
Expected: `install only: done`, version line printed.

- [ ] **Step 2: Pair and connect**

Run: `.\tools\scripts\pair-device.ps1`, then on the phone paste the link in Settings → Backend → Pair.
Expected: state `live`, device id shown; `render logs` has `device.hello` and `hello.ack`; the token is masked.

- [ ] **Step 3: Run a task with the phone awake**

Run: `.\tools\scripts\send-task.ps1 -Goal "open settings"` with the phone unlocked.
Expected: `ack task-…`, then `result` with a summary; `trace_spans` shows `accept, route, deliver, ack`; record the surface (`background`/`bridge`/`accessibility`) and the trace id.

- [ ] **Step 4: Wake it asleep**

Warm `/v1/health`, then background HOPA, turn the screen off, wait 2 minutes, and run `.\tools\scripts\send-task.ps1 -Goal "open settings"`.
Expected: a `wake` span appears with a measured delay; the socket reconnects; the task is acked; either it runs (auto/hidden with the helper) or the sender prints `E_DEVICE phone is locked`. Record which happened, the wake delay and the trace id.

- [ ] **Step 5: Record the evidence**

Add to `B1-gateway.md` row 4: phone model, Android version, pairing, the awake run trace, the wake trace and delay, and the surface. Note the `E_DEVICE` case if it was the one observed.

- [ ] **Step 6: Full verification**

Run: `apps\android\gradlew.bat -p apps/android :capabilities:test :app:testDebugUnitTest`, `apps\android\gradlew.bat -p services/backend :test` (Docker up), `.\.venv\Scripts\python.exe tools/checks/check-android-structure.py`, `check-structure.py`, `check-links.py`.
Expected: all green; only the known `check-tree.py` stale-folder warnings remain.

- [ ] **Step 7: Commit**

```bash
git add docs/project/evidence
git commit -m "docs(android): record the paired phone and the FCM wake on the deployed gateway"
```
