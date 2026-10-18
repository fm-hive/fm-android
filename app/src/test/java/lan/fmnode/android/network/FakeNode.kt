package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding

/**
 * The fake node side of the protocol: opens envelopes addressed to it, signs
 * policies, encrypts responses for the client and verifies envelope JWS.
 */
internal class FakeNode(private val seed: ByteArray) {
    val publicKey: ByteArray = CryptoService.publicKeyFromSeed(seed)
    private val x25519Private: ByteArray = CryptoService.x25519PrivateKeyFromSeed(seed)

    fun policyJws(payloadJson: String): String =
        CryptoService.createJws(seed, payloadJson, publicKey)

    fun sigPkeyB64(): String = Encoding.base64Encode(publicKey)

    class Opened(val envelope: JsonObject, val control: String) {
        val jws: String? get() = envelope["jws"]?.jsonPrimitive?.content
    }

    fun open(body: String): Opened {
        val envelope = Json.parseToJsonElement(body).jsonObject
        val jwe = envelope["jwe"]?.jsonPrimitive?.content
        val control = if (jwe != null) {
            String(CryptoService.decryptJwe(jwe, x25519Private), Charsets.UTF_8)
        } else {
            envelope["data"]!!.toString()
        }
        return Opened(envelope, control)
    }

    /** Response envelope carrying [controlJson], encrypted for the user. */
    fun encryptedResponse(controlJson: String, userPublicKey: ByteArray): String {
        val x25519 = CryptoService.x25519PublicKeyFromEd25519(userPublicKey)
        val jwe = CryptoService.encryptJwe(controlJson.toByteArray(Charsets.UTF_8), x25519)
        return """{"type":"control","jwe":"$jwe"}"""
    }

    /** Verifies the envelope JWS over the hash of its carrier. */
    fun verifyEnvelopeSignature(opened: Opened, userPublicKey: ByteArray): Boolean {
        val jws = opened.jws ?: return false
        if (!CryptoService.verifyJws(jws, userPublicKey)) return false
        val payload = CryptoService.jwsPayload(jws) ?: return false
        val expected = run {
            val jwe = opened.envelope["jwe"]?.jsonPrimitive?.content
            if (jwe != null) {
                """{"jwe_sha256":"${Encoding.base64UrlEncode(Encoding.sha256(jwe.toByteArray()))}"}"""
            } else {
                val data = opened.envelope["data"]!!.toString()
                """{"data_sha256":"${Encoding.base64UrlEncode(Encoding.sha256(data.toByteArray()))}"}"""
            }
        }
        return payload == expected
    }
}

