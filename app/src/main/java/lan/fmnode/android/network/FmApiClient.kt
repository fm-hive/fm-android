package lan.fmnode.android.network

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lan.fmnode.android.crypto.CryptoService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
     * Sends a control request and returns the response `data` (like fm-app's
     * request()): a decrypted JWE is parsed first, then the `data` field of the
     * control response is returned.
     */
    suspend fun post(
        request: String,
        dataJson: String? = null,
        auth: ApiKeys? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val body = buildEnvelope(request, dataJson, auth)
        val httpRequest = Request.Builder()
            .url(baseUrl)
            .post(body.toRequestBody(requestMediaType))
            .build()

        http.newCall(httpRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val error = parsed?.get("error")?.asText() ?: response.message
                val next = parsed?.get("next")?.asText()
                throw FmApiException(response.code, error, next)
            }
            unwrapResponse(text)
        }
    }

    /** Unwraps a response envelope; public for tests. */
    fun unwrapResponse(text: String): JsonElement {
        val envelope = json.parseToJsonElement(text).jsonObject
        val jwe = envelope["jwe"]?.asText()
        if (jwe != null) {
            val key = responseDecryptionKey
                ?: throw FmApiException(0, "response is encrypted and no user key is available")
            val plaintext = CryptoService.decryptJwe(jwe, key)
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
}
