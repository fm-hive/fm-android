package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding

/** Phase 1 result: the node-signed policy and its payload. */
data class PolicyInfo(
    val policyJws: String,
    val nodeSigPkey: String,
    val payload: JsonElement,
)

/** Phase 2 result: the address assigned by the node. */
data class Registration(
    val address: String,
    val isAdmin: Boolean = false,
    val isModerator: Boolean = false,
    val name: String = "",
)

/**
 * Two-phase policy flow (docs/api/policy.md), matching fm-app's usePolicy():
 * phase 1 fetches the node-signed policy, phase 2 signs the same policy
 * payload with the user's key and receives the network address.
 */
class PolicyClient(private val api: FmApiClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Phase 1. When [userPublicKey] is given, the request carries it (the node
     * encrypts its response for the user) and the response key must match the
     * invite key when it is already known.
     */
    suspend fun fetchPolicy(userPublicKey: ByteArray? = null): PolicyInfo {
        val data = userPublicKey?.let {
            """{"pubkey":"${Encoding.base64Encode(it)}"}"""
        }
        val response = api.post("policy", data, auth = null)
        val obj = response as? JsonObject
            ?: throw FmApiException(0, "invalid policy response")
        val policyJws = obj.string("policy")
            ?: throw FmApiException(0, "invalid policy response: no policy")
        val sigPkey = obj.string("sig_pkey")
            ?: throw FmApiException(0, "invalid policy response: no sig_pkey")

        val nodeKey = try {
            Encoding.base64Decode(sigPkey)
        } catch (e: Exception) {
            throw FmApiException(0, "invalid node public key: ${e.message}")
        }
        val invited = api.nodeEd25519PublicKey
        if (invited != null && !invited.contentEquals(nodeKey)) {
            throw FmApiException(0, "node public key does not match the invite")
        }
        api.setNodeEd25519PublicKey(nodeKey)

        if (!CryptoService.verifyJws(policyJws, nodeKey)) {
            throw FmApiException(0, "node policy signature is invalid")
        }
        val payloadString = CryptoService.jwsPayload(policyJws)
            ?: throw FmApiException(0, "invalid policy JWS payload")
        val payload = json.parseToJsonElement(payloadString)

        return PolicyInfo(policyJws = policyJws, nodeSigPkey = sigPkey, payload = payload)
    }

    /**
     * Phase 2: signs the same policy payload with the user's Ed25519 key and
     * submits it (plus the display name and an optional voucher) to receive
     * the address.
     */
    suspend fun acceptPolicy(
        policy: PolicyInfo,
        seed: ByteArray,
        publicKey: ByteArray,
        name: String,
        voucher: String? = null,
    ): Registration {
        // fm-app re-serializes the parsed policy payload (compact JSON, the
        // same field order as the node's JWS payload).
        val canonicalPolicy = policy.payload.toString()
        val userJws = CryptoService.createJws(seed, canonicalPolicy, publicKey)

        val data = buildJsonObject {
            put("policy", userJws)
            put("sig_pkey", Encoding.base64Encode(publicKey))
            put("name", name.trim())
            if (voucher != null) put("voucher", voucher)
        }.toString()

        val response = api.post("policy", data, auth = null)
        return when (response) {
            is JsonPrimitive -> Registration(address = response.content, name = name.trim())
            is JsonObject -> {
                val address = response.string("address")
                    ?: throw FmApiException(0, "invalid policy response: no address")
                Registration(
                    address = address,
                    isAdmin = response.boolean("is_admin"),
                    isModerator = response.boolean("is_moderator"),
                    name = response.string("name") ?: name.trim(),
                )
            }
            else -> throw FmApiException(0, "invalid policy response")
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull ?: false
}
