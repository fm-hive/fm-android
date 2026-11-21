package lan.fmnode.android.crypto

import java.security.MessageDigest
import java.util.Base64

/** Hex and base64 helpers matching the fm-app conventions (base64url without padding). */
object Encoding {
    private val HEX = "0123456789abcdef".toCharArray()

    fun toHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    /** base64url without padding (JWS/JWE/thumbprint encoding). */
    fun base64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text)

    /** Standard base64 (the API convention for raw keys, e.g. sig_pkey/pubkey). */
    fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun base64Decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha512(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(bytes)
}
