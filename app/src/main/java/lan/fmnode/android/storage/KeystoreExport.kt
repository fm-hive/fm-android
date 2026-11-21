package lan.fmnode.android.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lan.fmnode.android.crypto.Encoding

/** fm-app keys export JSON (keystore.js exportKeys). */
@Serializable
data class KeystoreExport(
    val version: String = "1.0",
    val publicKey: String,
    val metadata: KeystoreMetadata? = null,
    val timestamp: Long = 0,
    val encryptedPrivateKey: String? = null,
    val salt: List<Int> = emptyList(),
    val iterations: Int = 100_000,
    val iv: List<Int> = emptyList(),
    val encrypted: Boolean = true,
    /** fm-app unencrypted export writes this field instead of encryptedPrivateKey. */
    val privateKey: String? = null,
)

@Serializable
data class KeystoreMetadata(
    val encrypted: Boolean = false,
    val salt: List<Int> = emptyList(),
    val iterations: Int = 100_000,
    val iv: List<Int> = emptyList(),
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
 *
 * The decoder reads the JSON structurally instead of mapping it onto
 * [KeystoreExport] directly: fm-app writes `metadata` either as a nested object
 * (standalone keys export) or as a JSON *string* (the full v2.0 export), and it
 * keeps `salt`/`iv`/`iterations` inside `metadata` in both cases.
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

    fun decode(text: String): ImportedKeystore = decode(json.parseToJsonElement(text))

    fun decode(root: JsonElement): ImportedKeystore {
        val obj = root as? JsonObject ?: error("keys export is not a JSON object")
        // A full v2.0 export keeps the key material under "keys"; a standalone
        // keys export has these fields at the top level.
        val keys = (obj["keys"] as? JsonObject) ?: obj
        val metadata = metadataOf(keys)

        val publicKeyB64 = keys.str("publicKey") ?: error("keys export has no publicKey")
        val publicKey = Encoding.base64Decode(publicKeyB64)
        val encryptedPrivate = keys.str("encryptedPrivateKey")
        val plainPrivate = keys.str("privateKey")

        val encrypted = metadata?.boolean("encrypted")
            ?: keys.boolean("encrypted")
            ?: true
        val salt = metadata?.ints("salt")?.ifEmpty { keys.ints("salt") } ?: keys.ints("salt")
        val iv = metadata?.ints("iv")?.ifEmpty { keys.ints("iv") } ?: keys.ints("iv")
        val iterations = metadata?.int("iterations")
            ?: keys.int("iterations")
            ?: KeyVault.DEFAULT_ITERATIONS

        return if (encrypted && !encryptedPrivate.isNullOrEmpty()) {
            require(salt.isNotEmpty()) { "encrypted keys export has no salt" }
            require(iv.isNotEmpty()) { "encrypted keys export has no iv" }
            ImportedKeystore(
                publicKey = publicKey,
                encryptedSeed = EncryptedSeed(
                    encrypted = Encoding.base64Decode(encryptedPrivate),
                    salt = salt.map { it.toByte() }.toByteArray(),
                    iv = iv.map { it.toByte() }.toByteArray(),
                    iterations = iterations,
                ),
                plainSeed = null,
            )
        } else {
            val plain = plainPrivate ?: encryptedPrivate
                ?: error("no private key in the export")
            ImportedKeystore(
                publicKey = publicKey,
                encryptedSeed = null,
                plainSeed = Encoding.base64Decode(plain),
            )
        }
    }

    /** `metadata` is a nested object in a keys export and a JSON string in a full export. */
    fun metadataOf(keys: JsonObject): JsonObject? = when (val raw = keys["metadata"]) {
        is JsonObject -> raw
        is JsonPrimitive -> raw.contentOrNull
            ?.takeIf { it.trimStart().startsWith("{") }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        else -> null
    }

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.ints(name: String): List<Int> {
        val array = this[name] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.jsonPrimitive?.intOrNull }
    }
}
