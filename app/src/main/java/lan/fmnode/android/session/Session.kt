package lan.fmnode.android.session

import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.ProfileEntity

/**
 * In-memory vault session: the app password is kept only while the app is
 * unlocked (fm-app keeps it in sessionStorage the same way). Every profile's
 * key is encrypted with this password.
 */
class Session {
    @Volatile
    private var password: String? = null

    val isUnlocked: Boolean get() = password != null

    fun passwordOrNull(): String? = password

    /** Unlocks the session by verifying the password against [profile]. */
    fun unlock(password: String, profile: ProfileEntity): Boolean {
        val ok = runCatching { KeyVault.decrypt(profile.encryptedSeed(), password) }.isSuccess
        if (ok) this.password = password
        return ok
    }

    /** Marks the session as unlocked with a freshly created password. */
    fun setPassword(password: String) {
        this.password = password
    }

    fun lock() {
        password = null
    }
}
