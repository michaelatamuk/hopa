package com.hopa.sidecar

import com.hopa.core.contracts.Observation
import com.hopa.core.json.HopaJson
import com.hopa.core.observation.ObservationProducer
import com.hopa.core.ports.DevicePort
import com.hopa.core.ports.InstalledApp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * W3: the PC target. Observation and every action travel to the .NET host,
 * which runs UI Automation; the producer applies refs, redaction and the hash
 * on this side so the observation contract stays in one place.
 */
class HostDevice(private val io: Stdio) : DevicePort {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val counter = AtomicInteger(0)
    private val producer = ObservationProducer()

    fun resolve(id: String, result: JsonObject) {
        pending.remove(id)?.complete(result)
    }

    override suspend fun observe(): Observation {
        val result = request("observe") {}
        val payload = result["observation"] as? JsonObject
            ?: throw IllegalStateException("the host sent no observation")
        val raw = HopaJson.element.decodeFromJsonElement(Observation.serializer(), payload)
        return producer.produce(raw)
    }

    override suspend fun tap(ref: String?, x: Int?, y: Int?) {
        request("tap") {
            ref?.let { put("ref", it) }
            x?.let { put("x", it) }
            y?.let { put("y", it) }
        }
    }

    override suspend fun doubleTap(ref: String?, x: Int?, y: Int?) {
        request("doubleTap") {
            ref?.let { put("ref", it) }
            x?.let { put("x", it) }
            y?.let { put("y", it) }
        }
    }

    override suspend fun longPress(ref: String?, x: Int?, y: Int?) {
        request("longPress") {
            ref?.let { put("ref", it) }
            x?.let { put("x", it) }
            y?.let { put("y", it) }
        }
    }

    override suspend fun type(text: String, ref: String?) {
        request("type") {
            put("text", text)
            ref?.let { put("ref", it) }
        }
    }

    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, velocity: Int) {
        request("swipe") {
            put("fromX", fromX)
            put("fromY", fromY)
            put("toX", toX)
            put("toY", toY)
        }
    }

    override suspend fun pressBack() {
        request("key") { put("key", "back") }
    }

    override suspend fun pressHome() {
        request("key") { put("key", "home") }
    }

    override suspend fun launch(packageName: String) {
        request("launch") { put("app", packageName) }
    }

    override suspend fun stop(packageName: String) {
        throw UnsupportedOperationException("the Windows adapter does not stop apps yet")
    }

    override suspend fun foreground(): String = request("foreground") {}.let { result ->
        result["app"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    override suspend fun screenshot(): ByteArray? = null

    override suspend fun apps(): List<InstalledApp> = emptyList()

    private suspend fun request(op: String, fields: JsonObjectBuilder.() -> Unit): JsonObject {
        val id = "req-${counter.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        io.send(Protocol.deviceRequest(id, op, fields))
        val result = withTimeout(20_000) { deferred.await() }
        val ok = result["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            throw IllegalStateException(
                result["error"]?.jsonPrimitive?.contentOrNull ?: "the host refused $op",
            )
        }
        return result
    }
}
