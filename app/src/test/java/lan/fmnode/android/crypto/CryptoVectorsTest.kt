package lan.fmnode.android.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors generated from the fm-app (@noble) implementation with
 * tools/gen-vectors.mjs: the Kotlin crypto must be byte-for-byte compatible.
 */
class CryptoVectorsTest {
    private val vectors = Json.parseToJsonElement(
        javaClass.getResource("/crypto-vectors.json")!!.readText()
    ).jsonObject

    private fun hex(name: String): ByteArray = Encoding.fromHex(vectors[name]!!.jsonPrimitive.content)
    private fun text(name: String): String = vectors[name]!!.jsonPrimitive.content

    private val jws = vectors["jws"]!!.jsonObject
    private val jwe = vectors["jwe"]!!.jsonObject
    private val envelope = vectors["envelope"]!!.jsonObject

    private fun objString(obj: kotlinx.serialization.json.JsonObject, name: String): String =
        obj[name]!!.jsonPrimitive.content

    private fun objHex(obj: kotlinx.serialization.json.JsonObject, name: String): ByteArray =
        Encoding.fromHex(objString(obj, name))

    @Test
    fun `ed25519 keys and thumbprint match fm-app`() {
        val seed = hex("seed")
        val publicKey = CryptoService.publicKeyFromSeed(seed)
        assertArrayEquals(hex("public_key"), publicKey)
        assertEquals(text("thumbprint"), CryptoService.thumbprint(publicKey))
    }

    @Test
    fun `jws matches fm-app byte for byte and verifies`() {
        val seed = hex("seed")
        val publicKey = hex("public_key")
        val payload = objString(jws, "payload")
        val token = CryptoService.createJws(seed, payload, publicKey)
        assertEquals(objString(jws, "token"), token)
        assertTrue(CryptoService.verifyJws(token, publicKey))
        assertEquals(payload, CryptoService.jwsPayload(token))

        // A tampered payload must not verify.
        val parts = token.split('.')
        val tampered = "${parts[0]}.${Encoding.base64UrlEncode("{}".toByteArray())}.${parts[2]}"
        assertFalse(CryptoService.verifyJws(tampered, publicKey))
    }

    @Test
    fun `ed25519 to x25519 conversions match fm-app`() {
        val seed = hex("seed")
        val publicKey = hex("public_key")
        assertArrayEquals(hex("x25519_private_key"), CryptoService.x25519PrivateKeyFromSeed(seed))
        assertArrayEquals(hex("x25519_public_key"), CryptoService.x25519PublicKeyFromEd25519(publicKey))

        assertArrayEquals(
            objHex(jwe, "recipient_x_private_key"),
            CryptoService.x25519PrivateKeyFromSeed(objHex(jwe, "recipient_seed")),
        )
        assertArrayEquals(
            objHex(jwe, "recipient_x_public_key"),
            CryptoService.x25519PublicKeyFromEd25519(objHex(jwe, "recipient_ed_public_key")),
        )
        // The X25519 public key derived from the X25519 private key agrees.
        assertArrayEquals(
            objHex(jwe, "recipient_x_public_key"),
            CryptoService.x25519PublicKeyFromPrivate(objHex(jwe, "recipient_x_private_key")),
        )
    }

    @Test
    fun `deterministic jwe encryption matches fm-app`() {
        val token = CryptoService.encryptJweDeterministic(
            plaintext = objString(jwe, "plaintext").toByteArray(Charsets.UTF_8),
            recipientX25519PublicKey = objHex(jwe, "recipient_x_public_key"),
            ephemeralSeed = objHex(jwe, "ephemeral_seed"),
            nonce = objHex(jwe, "nonce"),
        )
        assertEquals(objString(jwe, "token"), token)
    }

    @Test
    fun `jwe decrypts with the recipient private key`() {
        val plaintext = CryptoService.decryptJwe(
            objString(jwe, "token"),
            objHex(jwe, "recipient_x_private_key"),
        )
        assertEquals(objString(jwe, "plaintext"), String(plaintext, Charsets.UTF_8))
    }

    @Test
    fun `jwe round trip with a random ephemeral key`() {
        val recipientSeed = CryptoService.generateSeed()
        val recipientXPriv = CryptoService.x25519PrivateKeyFromSeed(recipientSeed)
        val recipientXPub = CryptoService.x25519PrivateKeyFromSeed(recipientSeed)
            .let { CryptoService.x25519PublicKeyFromPrivate(it) }
        val plaintext = """{"request":"policy"}""".toByteArray()
        val token = CryptoService.encryptJwe(plaintext, recipientXPub)
        assertArrayEquals(plaintext, CryptoService.decryptJwe(token, recipientXPriv))
    }

    @Test
    fun `envelope auth payloads match fm-app`() {
        val jweToken = objString(jwe, "token")
        assertEquals(
            objString(envelope, "jwe_sha256"),
            Regex("\"jwe_sha256\":\"([^\"]+)\"")
                .find(CryptoService.buildEnvelopeAuthPayload(jweToken, null))!!
                .groupValues[1],
        )
        assertEquals(
            objString(envelope, "data_sha256"),
            Regex("\"data_sha256\":\"([^\"]+)\"")
                .find(CryptoService.buildEnvelopeAuthPayload(null, objString(envelope, "data_json")))!!
                .groupValues[1],
        )
    }

    @Test
    fun `signature rejects a different message`() {
        val seed = CryptoService.generateSeed()
        val publicKey = CryptoService.publicKeyFromSeed(seed)
        val signature = CryptoService.sign(seed, "one".toByteArray())
        assertTrue(CryptoService.verify(publicKey, signature, "one".toByteArray()))
        assertFalse(CryptoService.verify(publicKey, signature, "two".toByteArray()))
    }
}
