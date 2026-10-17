package lan.fmnode.android.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The fake node side of the protocol: opens envelopes addressed to it, signs
 * policies, encrypts responses for the client and verifies envelope JWS.
 */
private class FakeNode(private val seed: ByteArray) {
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

class PolicyClientTest {
    private lateinit var server: MockWebServer
    private val userSeed = ByteArray(32) { (it + 1).toByte() }
    private val userPublicKey = CryptoService.publicKeyFromSeed(userSeed)
    private val node = FakeNode(ByteArray(32) { (it + 101).toByte() })

    private val policyPayload = buildJsonObject {
        put("id", "policy-1")
        put(
            "policy",
            buildJsonObject {
                put("text", "Network rules")
                put("parameters", buildJsonObject { put("voting_days", "30") })
            },
        )
    }.toString()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): FmApiClient {
        val api = FmApiClient(server.url("/pre/api").toString())
        api.setNodeEd25519PublicKey(node.publicKey)
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(userSeed)
        return api
    }

    private fun phase1Response(): String {
        val policyJws = node.policyJws(policyPayload)
        val control = buildJsonObject {
            put("request", "policy")
            put(
                "data",
                buildJsonObject {
                    put("policy", policyJws)
                    put("sig_pkey", node.sigPkeyB64())
                },
            )
        }.toString()
        return node.encryptedResponse(control, userPublicKey)
    }

    @Test
    fun `phase 1 encrypts for the node and verifies the policy signature`() {
        server.enqueue(MockResponse().setBody(phase1Response()))

        val policy = kotlinx.coroutines.runBlocking { PolicyClient(client()).fetchPolicy(userPublicKey) }

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/pre/api", request.path)
        val opened = node.open(request.body.readUtf8())
        assertFalse("policy phase 1 must not be envelope-signed", opened.jws != null)
        val control = Json.parseToJsonElement(opened.control).jsonObject
        assertEquals("policy", control["request"]!!.jsonPrimitive.content)
        val sentPubkey = Encoding.base64Decode(control["data"]!!.jsonObject["pubkey"]!!.jsonPrimitive.content)
        assertTrue(sentPubkey.contentEquals(userPublicKey))

        assertNotNull(policy.payload.jsonObject["policy"])
        assertTrue(CryptoService.verifyJws(policy.policyJws, node.publicKey))
        assertEquals(node.sigPkeyB64(), policy.nodeSigPkey)
    }

    @Test
    fun `phase 1 rejects a node key that differs from the invite`() {
        val api = client()
        api.setNodeEd25519PublicKey(CryptoService.publicKeyFromSeed(CryptoService.generateSeed()))
        server.enqueue(MockResponse().setBody(phase1Response()))

        val error = runCatching {
            kotlinx.coroutines.runBlocking { PolicyClient(api).fetchPolicy(userPublicKey) }
        }.exceptionOrNull()
        assertTrue(error is FmApiException)
        assertTrue(error!!.message!!.contains("does not match the invite"))
    }

    @Test
    fun `phase 2 signs the policy payload with the user key`() {
        server.enqueue(MockResponse().setBody(phase1Response()))
        server.enqueue(MockResponse().setBody(phase2Response()))

        val api = client()
        val policyClient = PolicyClient(api)
        val policy = kotlinx.coroutines.runBlocking { policyClient.fetchPolicy(userPublicKey) }
        server.takeRequest(5, TimeUnit.SECONDS)

        val registration = kotlinx.coroutines.runBlocking {
            policyClient.acceptPolicy(
                policy = policy,
                seed = userSeed,
                publicKey = userPublicKey,
                name = "Ivan Petrov",
                voucher = "invite-1",
            )
        }

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val opened = node.open(request.body.readUtf8())
        val control = Json.parseToJsonElement(opened.control).jsonObject
        val data = control["data"]!!.jsonObject
        val userJws = data["policy"]!!.jsonPrimitive.content
        assertTrue(CryptoService.verifyJws(userJws, userPublicKey))
        assertEquals(policy.payload.toString(), CryptoService.jwsPayload(userJws))
        assertEquals(Encoding.base64Encode(userPublicKey), data["sig_pkey"]!!.jsonPrimitive.content)
        assertEquals("Ivan Petrov", data["name"]!!.jsonPrimitive.content)
        assertEquals("invite-1", data["voucher"]!!.jsonPrimitive.content)

        assertEquals(":0:1", registration.address)
        assertTrue(registration.isAdmin)
        assertEquals("Ivan Petrov", registration.name)
    }

    @Test
    fun `api errors carry the status and the protocol hint`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":"No policy signature","next":"policy"}""")
        )
        val error = runCatching {
            kotlinx.coroutines.runBlocking { PolicyClient(client()).fetchPolicy(userPublicKey) }
        }.exceptionOrNull()
        assertTrue(error is FmApiException)
        error as FmApiException
        assertEquals(400, error.status)
        assertEquals("policy", error.next)
    }

    private fun phase2Response(): String {
        val control = buildJsonObject {
            put("request", "policy")
            put(
                "data",
                buildJsonObject {
                    put("address", ":0:1")
                    put("is_admin", true)
                    put("is_moderator", false)
                    put("name", "Ivan Petrov")
                },
            )
        }.toString()
        return node.encryptedResponse(control, userPublicKey)
    }
}
