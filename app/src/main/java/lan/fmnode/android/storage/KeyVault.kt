package lan.fmnode.android.storage

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM encrypted Ed25519 seed with its KDF parameters. */
data class EncryptedSeed(
    val encrypted: ByteArray,
    val salt: ByteArray,
    val iv: ByteArray,
    val iterations: Int,
)

/**
 * Password protection of private keys, matching fm-app's keystore.js:
 * PBKDF2-HMAC-SHA256 (100k iterations by default, 16-byte salt) derives a
 * 256-bit AES key, the seed is encrypted with AES-GCM (12-byte IV, 128-bit tag).
 */
object KeyVault {
    const val DEFAULT_ITERATIONS = 100_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(
        seed: ByteArray,
        password: String,
        salt: ByteArray = randomBytes(SALT_LENGTH),
        iv: ByteArray = randomBytes(IV_LENGTH),
        iterations: Int = DEFAULT_ITERATIONS,
    ): EncryptedSeed {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        return EncryptedSeed(
            encrypted = cipher.doFinal(seed),
            salt = salt,
            iv = iv,
            iterations = iterations,
        )
    }

    fun decrypt(encryptedSeed: EncryptedSeed, password: String): ByteArray =
        decrypt(encryptedSeed.encrypted, encryptedSeed.salt, encryptedSeed.iv, encryptedSeed.iterations, password)

    fun decrypt(
        encrypted: ByteArray,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int,
        password: String,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
