package lan.fmnode.android.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys block of a full v2.0 export: fm-app writes `metadata` as an escaped
 * JSON string with the KDF parameters nested inside it, and `publicKey` as
 * base64 while `user.publicKey` is hex.
 */
class FullExportImportTest {
    private fun export(): String = FullExportFixtures.read(FullExportFixtures.FULL_EXPORT)

    @Test
    fun `keys of a full export decode with nested string metadata`() {
        val keystore = KeystoreExportCodec.decode(export())

        assertNull(keystore.plainSeed)
        val encrypted = requireNotNull(keystore.encryptedSeed)
        assertEquals(100_000, encrypted.iterations)
        assertEquals(16, encrypted.salt.size)
        assertEquals(12, encrypted.iv.size)
        // 32-byte seed + 16-byte GCM tag.
        assertEquals(48, encrypted.encrypted.size)
        assertEquals(32, keystore.publicKey.size)
    }

    @Test
    fun `metadata is read from the keys block of a full export`() {
        val root = Json.parseToJsonElement(export()).jsonObject
        val keys = root["keys"]!!.jsonObject
        val metadata = requireNotNull(KeystoreExportCodec.metadataOf(keys))
        assertEquals("true", metadata["encrypted"]!!.jsonPrimitive.content)
        assertEquals(100_000, metadata["iterations"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `full export is parsed as an identity`() {
        val full = requireNotNull(BackupService.parseFullBackup(export()))

        assertEquals(":0:1", full.address)
        assertEquals("Tester", full.displayName)
        assertArrayEquals(full.keystore.publicKey, Encoding.base64Decode(full.publicKeyB64))
        assertEquals(32, full.nodePublicKeyB64?.let { Encoding.base64Decode(it).size })
    }

    @Test
    fun `a full export carries the node endpoint`() {
        val full = requireNotNull(BackupService.parseFullBackup(export()))
        assertEquals("https://fm.example.test/sdfglj54t05jslkfls/app", full.nodeUrl)
    }

    @Test
    fun `a keys export without the full payload is not a full backup`() {
        assertNull(BackupService.parseFullBackup(FullExportFixtures.read(FullExportFixtures.KEYS_EXPORT)))
    }

    @Test
    fun `a wrong export password fails instead of producing a wrong key`() {
        val keystore = KeystoreExportCodec.decode(export())
        val encrypted = requireNotNull(keystore.encryptedSeed)
        // The export password is unknown here: any password must fail the GCM tag
        // check instead of silently producing a wrong key.
        assertTrue(runCatching { KeyVault.decrypt(encrypted, "not-the-export-password") }.isFailure)
    }

    @Test
    fun `standalone keys export with an object metadata still decodes`() {
        val decoded = KeystoreExportCodec.decode(FullExportFixtures.read(FullExportFixtures.KEYS_EXPORT))

        val encrypted = requireNotNull(decoded.encryptedSeed)
        assertEquals(100_000, encrypted.iterations)
        assertEquals(16, encrypted.salt.size)
        assertEquals(12, encrypted.iv.size)
        assertEquals(48, encrypted.encrypted.size)
    }

    @Test
    fun `a keys export round trips through the encoder`() {
        val seed = CryptoService.generateSeed()
        val encrypted = KeyVault.encrypt(seed, "secret-pass")
        val text = KeystoreExportCodec.encode(CryptoService.publicKeyFromSeed(seed), encrypted)

        val decoded = KeystoreExportCodec.decode(text)
        assertArrayEquals(seed, KeyVault.decrypt(requireNotNull(decoded.encryptedSeed), "secret-pass"))
    }
}
