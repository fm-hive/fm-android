package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeTest {
    private val userSeed = ByteArray(32) { (it + 3).toByte() }
    private val userPublicKey = CryptoService.publicKeyFromSeed(userSeed)
    private val nodePublicKey = CryptoService.publicKeyFromSeed(ByteArray(32) { (it + 7).toByte() })

    @Test
    fun `authenticated envelope signs the hash of the transmitted jwe`() {
        val api = FmApiClient("https://fm.example.com/pre/api")
        api.setNodeEd25519PublicKey(nodePublicKey)

        val body = api.buildEnvelope("send_receive", """{"messages":[]}""", ApiKeys(userSeed, userPublicKey))
        val envelope = Json.parseToJsonElement(body).jsonObject
        val jwe = envelope["jwe"]?.jsonPrimitive?.content
        val jws = envelope["jws"]?.jsonPrimitive?.content
        assertNotNull("the request must be encrypted for the node", jwe)
        assertNotNull(jws)
        assertNull("encrypted envelopes carry no plain data field", envelope["data"])

        assertTrue(CryptoService.verifyJws(jws!!, userPublicKey))
        assertEquals(
            """{"jwe_sha256":"${Encoding.base64UrlEncode(Encoding.sha256(jwe!!.toByteArray()))}"}""",
            CryptoService.jwsPayload(jws),
        )
    }

    @Test
    fun `plaintext envelope signs the hash of the control data`() {
        // No node key yet (bootstrap): the control data travels in clear.
        val api = FmApiClient("https://fm.example.com/api")

        val body = api.buildEnvelope("policy", """{"pubkey":"AAECAw=="}""", ApiKeys(userSeed, userPublicKey))
        val envelope = Json.parseToJsonElement(body).jsonObject
        assertNull(envelope["jwe"])
        val data = envelope["data"]!!.toString()
        val jws = envelope["jws"]!!.jsonPrimitive.content
        assertTrue(CryptoService.verifyJws(jws, userPublicKey))
        assertEquals(
            """{"data_sha256":"${Encoding.base64UrlEncode(Encoding.sha256(data.toByteArray()))}"}""",
            CryptoService.jwsPayload(jws),
        )
    }
}
