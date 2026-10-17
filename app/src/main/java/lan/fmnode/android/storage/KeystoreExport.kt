package lan.fmnode.android.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lan.fmnode.android.crypto.Encoding

/** fm-app keys export JSON (keystore.js exportKeys). */
@Serializable
data class KeystoreExport(
    val version: String = "1.0",
    val publicKey: String,
    val metadata: KeystoreMetadata,
    val timestamp: Long,
    val encryptedPrivateKey: String,
    val salt: List<Int>,
    val iterations: Int,
    val iv: List<Int>,
    val encrypted: Boolean = true,
)

@Serializable
data class KeystoreMetadata(
    val encrypted: Boolean,
    val salt: List<Int>,
    val iterations: Int,
    val iv: List<Int>,
)

/** Decoded keys export (either an encrypted or a legacy plaintext private key). */
data class ImportedKeystore(
    val publicKey: ByteArray,
    val encryptedSeed: EncryptedSeed?,
    val plainSeed: ByteArray?,
)

/**
 * Encodes/decodes the fm-app keys export format, so backups made by the web
 * client can be imported and vice versa.
 */
object KeystoreExportCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(publicKey: ByteArray, encryptedSeed: EncryptedSeed, timestamp: Long = System.currentTimeMillis()): String {
        val salt = encryptedSeed.salt.map { it.toInt() and 0xff }
        val iv = encryptedSeed.iv.map { it.toInt() and 0xff }
        val payload = KeystoreExport(
            publicKey = Encoding.base64Encode(publicKey),
            metadata = KeystoreMetadata(
                encrypted = true,
                salt = salt,
                iterations = encryptedSeed.iterations,
                iv = iv,
            ),
            timestamp = timestamp,
            encryptedPrivateKey = Encoding.base64Encode(encryptedSeed.encrypted),
            salt = salt,
            iterations = encryptedSeed.iterations,
            iv = iv,
        )
        return json.encodeToString(KeystoreExport.serializer(), payload)
    }

    fun decode(text: String): ImportedKeystore {
        val payload = json.decodeFromString(KeystoreExport.serializer(), text)
        val publicKey = Encoding.base64Decode(payload.publicKey)
        return if (payload.encrypted && payload.encryptedPrivateKey.isNotEmpty() && payload.salt.isNotEmpty()) {
            ImportedKeystore(
                publicKey = publicKey,
                encryptedSeed = EncryptedSeed(
                    encrypted = Encoding.base64Decode(payload.encryptedPrivateKey),
                    salt = payload.salt.map { it.toByte() }.toByteArray(),
                    iv = payload.iv.map { it.toByte() }.toByteArray(),
                    iterations = payload.iterations,
                ),
                plainSeed = null,
            )
        } else {
            ImportedKeystore(
                publicKey = publicKey,
                encryptedSeed = null,
                plainSeed = Encoding.base64Decode(payload.encryptedPrivateKey),
            )
        }
    }
}
