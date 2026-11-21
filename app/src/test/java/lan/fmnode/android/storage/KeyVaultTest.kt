package lan.fmnode.android.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class KeyVaultTest {
    private val vectors = Json.parseToJsonElement(
        javaClass.getResource("/crypto-vectors.json")!!.readText(),
    ).jsonObject
    private val keystore = vectors["keystore"]!!.jsonObject

    private fun text(name: String): String = keystore[name]!!.jsonPrimitive.content

    @Test
    fun `decrypts a keystore vector produced by fm-app web crypto`() {
        val seed = KeyVault.decrypt(
            encrypted = Encoding.base64Decode(text("encrypted_b64")),
            salt = Encoding.fromHex(text("salt")),
            iv = Encoding.fromHex(text("iv")),
            iterations = text("iterations").toInt(),
            password = text("password"),
        )
        assertArrayEquals(Encoding.fromHex(text("seed")), seed)
    }

    @Test
    fun `re-encrypts the same vector byte for byte`() {
        val encrypted = KeyVault.encrypt(
            seed = Encoding.fromHex(text("seed")),
            password = text("password"),
            salt = Encoding.fromHex(text("salt")),
            iv = Encoding.fromHex(text("iv")),
            iterations = text("iterations").toInt(),
        )
        assertEquals(text("encrypted_b64"), Encoding.base64Encode(encrypted.encrypted))
    }

    @Test
    fun `wrong password fails and a random round trip works`() {
        val seed = CryptoService.generateSeed()
        val encrypted = KeyVault.encrypt(seed, "correct horse")
        assertArrayEquals(seed, KeyVault.decrypt(encrypted, "correct horse"))
        try {
            KeyVault.decrypt(encrypted, "wrong")
            fail("expected a decryption error")
        } catch (_: Exception) {
            // expected: AES-GCM tag mismatch
        }
    }

    @Test
    fun `imports an unencrypted fm-app keys export`() {
        val seed = CryptoService.generateSeed()
        val publicKey = CryptoService.publicKeyFromSeed(seed)
        val json = """{"version":"1.0","publicKey":"${Encoding.base64Encode(publicKey)}","metadata":{"encrypted":false,"timestamp":1},"timestamp":1,"privateKey":"${Encoding.base64Encode(seed)}","encrypted":false}"""
        val imported = KeystoreExportCodec.decode(json)
        assertArrayEquals(seed, imported.plainSeed)
        assertNull(imported.encryptedSeed)
    }

    @Test
    fun `keystore export survives an encode-decode round trip`() {
        val seed = CryptoService.generateSeed()
        val publicKey = CryptoService.publicKeyFromSeed(seed)
        val encrypted = KeyVault.encrypt(seed, "pass")
        val json = KeystoreExportCodec.encode(publicKey, encrypted, timestamp = 123)

        val imported = KeystoreExportCodec.decode(json)
        assertArrayEquals(publicKey, imported.publicKey)
        val restored = imported.encryptedSeed!!
        assertArrayEquals(seed, KeyVault.decrypt(restored, "pass"))
        assertNull(imported.plainSeed)
        assertNotNull(restored)
    }
}
