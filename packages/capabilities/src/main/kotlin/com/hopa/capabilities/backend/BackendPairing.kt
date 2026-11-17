package com.hopa.capabilities.backend

import com.hopa.core.json.HopaJson
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The unauthenticated half of the backend: trade a pairing session for device
 * tokens and rotate them later. Neither call puts a secret in a message, and
 * a refusal is reported with the server's own code and text.
 */
class BackendPairing(
    private val gatewayUrl: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val base = BackendUrls.httpBase(gatewayUrl)

    suspend fun claim(
        link: PairingLink,
        name: String,
        publicKey: String,
        pushToken: String?,
    ): BackendCredentials {
        val body = buildJsonObject {
            put("session", link.session)
            put("platform", "android")
            put("name", name)
            put("publicKey", publicKey)
            put("alg", "x25519")
            if (pushToken != null) put("pushToken", pushToken)
        }
        return credentials(
            post(path = "/v1/pair/claim", body = body.toString(), secrets = listOf(link.session)),
            identityRequired = true,
        )
    }

    suspend fun refresh(refreshToken: String): BackendCredentials = credentials(
        post(
            path = "/v1/token/refresh",
            body = buildJsonObject { put("refreshToken", refreshToken) }.toString(),
            secrets = listOf(refreshToken),
        ),
        identityRequired = false,
    )

    private fun credentials(payload: JsonObject, identityRequired: Boolean): BackendCredentials {
        val credentials = BackendCredentials(
            deviceId = payload["deviceId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            householdId = payload["householdId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            accessToken = payload["accessToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            refreshToken = payload["refreshToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            expiresAt = payload["expiresAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            gatewayUrl = gatewayUrl,
        )
        if (credentials.accessToken.isBlank() ||
            credentials.refreshToken.isBlank() ||
            credentials.expiresAt.isBlank()
        ) {
            throw IllegalStateException("pairing response is incomplete: missing tokens")
        }
        if (identityRequired && (credentials.deviceId.isBlank() || credentials.householdId.isBlank())) {
            throw IllegalStateException("pairing response is incomplete: missing device identity")
        }
        return credentials
    }

    private suspend fun post(path: String, body: String, secrets: List<String>): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(base + path)
                .post(body.toRequestBody(JSON))
                .build()
            val client = http.newBuilder().callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
            val response = try {
                client.newCall(request).execute()
            } catch (error: IOException) {
                throw BackendPairingFailure.Unavailable(
                    scrub("backend unreachable: ${error.message.orEmpty()}", secrets),
                )
            }
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val message = scrub(refusal(it.code, text), secrets)
                    throw if (it.code in 400..499) {
                        BackendPairingFailure.Refused(it.code, message)
                    } else {
                        BackendPairingFailure.Unavailable(message)
                    }
                }
                parse(text)
            }
        }

    private fun refusal(status: Int, text: String): String {
        val error = runCatching { HopaJson.element.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = runCatching { error?.get("code")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val message = runCatching { error?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        return "pairing refused: ${code ?: "HTTP $status"} ${message.orEmpty()}".trim()
    }

    /** Never echo the response: a malformed body may itself contain tokens. */
    private fun parse(text: String): JsonObject = try {
        HopaJson.element.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw IllegalStateException("pairing returned no JSON object")
    }

    /** A refusal may come back with the session or token echoed; it never leaves here. */
    private fun scrub(message: String, secrets: List<String>): String = secrets.fold(message) { text, secret ->
        if (secret.isBlank()) text else text.replace(secret, "<redacted>")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val TIMEOUT_SECONDS = 20L
    }
}
