package lan.fmnode.android.network

import android.os.NetworkOnMainThreadException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.log.AppLog
import lan.fmnode.android.log.LogCategory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Envelope authentication material of the active network profile. */
data class ApiKeys(
    val seed: ByteArray,
    val publicKey: ByteArray,
)

/** API error with the HTTP status and the optional protocol hint (`next`). */
class FmApiException(
    val status: Int,
    message: String,
    val next: String? = null,
) : IOException(message)

/**
 * Control-envelope client: matches fm-app's ApiService.
 *
 * Every request is a `{type:"control"}` envelope. When the node's public key is
 * known the control data is encrypted for the node (custom JWE); authenticated
 * requests additionally carry a JWS over the hash of the transmitted carrier
 * (`{"jwe_sha256": …}` or `{"data_sha256": …}`). Responses may be JWE-encrypted
 * for the user (policy flow, identity) and are unwrapped to their `data` field.
 */
class FmApiClient(
    baseUrl: String,
    private val http: OkHttpClient = FmHttp.client,
    private val sendReceiveHttp: OkHttpClient = http,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val requestMediaType = "application/json".toMediaType()

    var baseUrl: String = baseUrl.trimEnd('/')
        private set

    /** Node Ed25519 key (from the invite or the policy response). */
    var nodeEd25519PublicKey: ByteArray? = null
        private set

    /** Node X25519 key used to encrypt envelopes. */
    var nodeX25519PublicKey: ByteArray? = null
        private set

    /** User X25519 private key used to decrypt JWE responses of this profile. */
    var responseDecryptionKey: ByteArray? = null

    fun setBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun setNodeEd25519PublicKey(key: ByteArray?) {
        nodeEd25519PublicKey = key
        nodeX25519PublicKey = key?.let { CryptoService.x25519PublicKeyFromEd25519(it) }
    }

    /** Builds the envelope JSON exactly as fm-app does (the signed carrier). */
    fun buildEnvelope(
        request: String,
        dataJson: String? = null,
        auth: ApiKeys? = null,
    ): String {
        val control = if (dataJson == null) {
            """{"request":"$request"}"""
        } else {
            """{"request":"$request","data":$dataJson}"""
        }
        val jwe = nodeX25519PublicKey?.let {
            CryptoService.encryptJwe(control.toByteArray(Charsets.UTF_8), it)
        }
        val carrier = jwe ?: control
        val jws = auth?.let {
            val payload = CryptoService.buildEnvelopeAuthPayload(jwe, if (jwe == null) control else null)
            CryptoService.createJws(it.seed, payload, it.publicKey)
        }
        val sb = StringBuilder(256)
        sb.append("""{"type":"control",""")
        if (jwe != null) {
            sb.append("\"jwe\":\"").append(jwe).append('"')
        } else {
            sb.append("\"data\":").append(control)
        }
        if (jws != null) {
            sb.append(",\"jws\":\"").append(jws).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * Sends a regular control request and returns the response `data` (like
     * fm-app's request()). Short idempotent requests are retried once or twice
     * on transient network errors; POSTs that may create messages pass
     * `retryable = false`.
     */
    suspend fun post(
        request: String,
        dataJson: String? = null,
        auth: ApiKeys? = null,
        retryable: Boolean = true,
    ): JsonElement = postWith(http, request, dataJson, auth, retryable)

    /**
     * Sends a send_receive request and transparently follows the node's
     * polling protocol: when the node answers `{status:"in progress",
     * connect_id}`, the final result is fetched with short poll requests.
     *
     * The first request uses [sendReceiveHttp], whose timeout is long enough
     * for a node that still processes send_receive synchronously.
     */
    suspend fun postSendReceive(
        dataJson: String,
        auth: ApiKeys? = null,
        retryable: Boolean = false,
    ): JsonElement {
        var response = postWith(sendReceiveHttp, "send_receive", dataJson, auth, retryable)
        var polls = 0
        while (true) {
            val obj = response as? JsonObject ?: return response
            val status = (obj["status"] as? JsonPrimitive)?.content
            val connectId = (obj["connect_id"] as? JsonPrimitive)?.content
            if (status != "in progress" || connectId.isNullOrBlank()) return response
            if (polls++ >= MAX_POLLS) {
                AppLog.error(
                    LogCategory.NETWORK,
                    "send_receive polling timed out",
                    "connect_id=$connectId polls=$polls",
                )
                throw FmApiException(0, "send_receive polling timed out")
            }
            AppLog.debug(
                LogCategory.NETWORK,
                "send_receive in progress",
                "connect_id=$connectId poll=$polls",
            )
            delay(POLL_INTERVAL_MS)
            val poll = buildJsonObject { put("connect_id", connectId) }.toString()
            // Poll requests are read-only and safe to retry.
            response = postWith(http, "send_receive", poll, auth, retryable = true)
        }
    }

    private suspend fun postWith(
        client: OkHttpClient,
        request: String,
        dataJson: String?,
        auth: ApiKeys?,
        retryable: Boolean,
    ): JsonElement = withContext(Dispatchers.IO) {
        postWithIo(client, request, dataJson, auth, retryable)
    }

    private suspend fun postWithIo(
        client: OkHttpClient,
        request: String,
        dataJson: String?,
        auth: ApiKeys?,
        retryable: Boolean,
    ): JsonElement {
        val body = buildEnvelope(request, dataJson, auth)
        val httpRequest = Request.Builder()
            .url(baseUrl)
            .post(body.toRequestBody(requestMediaType))
            .build()

        var attempt = 0
        while (true) {
            val started = System.currentTimeMillis()
            AppLog.debug(
                LogCategory.NETWORK,
                "→ $request $baseUrl",
                buildString {
                    append("request=${body.length}B")
                    append(" thread=${Thread.currentThread().name}")
                    if (attempt > 0) append(" retry=$attempt")
                    NetworkState.snapshot?.let { append(" network=${it.describe()}") }
                },
            )
            try {
                val text = execute(client, httpRequest).use { response ->
                    val text = response.body?.string().orEmpty()
                    val elapsed = System.currentTimeMillis() - started
                    AppLog.debug(
                        LogCategory.NETWORK,
                        "← $request HTTP ${response.code} ${elapsed}ms",
                        "response=${text.length}B thread=${Thread.currentThread().name}",
                    )
                    if (!response.isSuccessful) {
                        val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        val error = parsed?.get("error")?.asText() ?: response.message
                        val next = parsed?.get("next")?.asText()
                        AppLog.warn(
                            LogCategory.NETWORK,
                            "✕ $request HTTP ${response.code}",
                            error,
                        )
                        throw FmApiException(response.code, error, next)
                    }
                    text
                }
                return unwrapResponse(text)
            } catch (e: FmApiException) {
                if (!retryable || e.status !in RETRYABLE_STATUS || attempt >= MAX_RETRIES) throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - started
                AppLog.error(
                    LogCategory.NETWORK,
                    "✕ $request ${elapsed}ms ${e.javaClass.simpleName}",
                    buildString {
                        append(e.message.orEmpty())
                        // Network timeouts are self-explanatory; unexpected
                        // failures need the stack (for example a body read on
                        // the main thread) (#50).
                        if (e is NetworkOnMainThreadException || !e.isTransientNetworkError()) {
                            if (isNotEmpty()) append('\n')
                            append(e.stackTraceToString())
                        }
                    },
                )
                if (!retryable || attempt >= MAX_RETRIES || !e.isTransientNetworkError()) throw e
            }
            attempt++
            val delayMs = RETRY_DELAYS_MS.getOrElse(attempt - 1) { RETRY_DELAYS_MS.last() }
            AppLog.warn(
                LogCategory.NETWORK,
                "retry $request",
                "attempt=$attempt delay=${delayMs}ms",
            )
            FmHttp.evictConnections()
            delay(delayMs)
        }
    }

    /** Executes a call without blocking a thread; cancellation cancels OkHttp. */
    private suspend fun execute(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    /** Unwraps a response envelope; public for tests. */
    fun unwrapResponse(text: String): JsonElement {
        val envelope = json.parseToJsonElement(text).jsonObject
        val jwe = envelope["jwe"]?.asText()
        if (jwe != null) {
            val key = responseDecryptionKey
                ?: throw FmApiException(0, "response is encrypted and no user key is available")
            val plaintext = try {
                CryptoService.decryptJwe(jwe, key)
            } catch (e: Exception) {
                AppLog.error(
                    LogCategory.NETWORK,
                    "response decrypt failed",
                    "${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            return unwrapControl(String(plaintext, Charsets.UTF_8))
        }
        val data = envelope["data"] ?: return envelope
        val control = when (data) {
            is JsonPrimitive -> if (data.isString) data.content else return data
            else -> data.toString()
        }
        return unwrapControl(control)
    }

    private fun unwrapControl(raw: String): JsonElement {
        val element = json.parseToJsonElement(raw)
        val obj = element as? JsonObject ?: return element
        return obj["data"] ?: obj
    }

    private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.content

    private fun Throwable.isTransientNetworkError(): Boolean =
        this is SocketTimeoutException ||
            this is ConnectException ||
            this is SocketException ||
            this is UnknownHostException ||
            this is SSLException ||
            // Connection resets and unexpected EOFs surface as plain IOException.
            this is IOException

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_POLLS = 40
        const val POLL_INTERVAL_MS = 1500L
        val RETRY_DELAYS_MS = longArrayOf(750L, 2000L)
        val RETRYABLE_STATUS = setOf(502, 503, 504)
    }
}
