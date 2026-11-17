package lan.fmnode.android.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Local storage of the application password for the "save password" option (#22). */
interface PasswordStore {
    fun load(): String?
    fun save(password: String)
    fun clear()
}

/** Default used by tests and by callers that do not need credential storage. */
object NoopPasswordStore : PasswordStore {
    override fun load(): String? = null
    override fun save(password: String) = Unit
    override fun clear() = Unit
}

/**
 * Stores the application password encrypted with a non-exportable Android
 * Keystore AES key. Only the ciphertext and IV live in SharedPreferences, so a
 * root-level file copy does not expose the vault password.
 */
class KeystorePasswordStore(context: Context) : PasswordStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): String? {
        val stored = prefs.getString(KEY_PASSWORD, null) ?: return null
        val separator = stored.indexOf(':')
        if (separator <= 0) return null
        val result = runCatching {
            val iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "saved password could not be decrypted", it) }.getOrNull()
        return result
    }

    override fun save(password: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.edit().putString(KEY_PASSWORD, encoded).apply()
        }.onFailure { Log.w(TAG, "saved password could not be stored", it) }
    }

    override fun clear() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "fm_credentials"
        const val KEY_PASSWORD = "saved_password"
        const val ALIAS = "fm_saved_password"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG = "FmPassword"
    }
}
