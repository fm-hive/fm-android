package lan.fmnode.android.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Legacy plaintext databases are removed once (#3, "wipe all"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultWipeTest {
    @Test
    fun wipesPlaintextDatabasesOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // FmApplication already ran the one-time wipe; reset the marker so the
        // test can exercise it deterministically.
        context.getSharedPreferences("fm_vault", Context.MODE_PRIVATE).edit().clear().commit()
        context.openOrCreateDatabase("fm_app.db", Context.MODE_PRIVATE, null).close()
        context.openOrCreateDatabase("fm_profile_legacy.db", Context.MODE_PRIVATE, null).close()

        Vault(context).wipeLegacyDatabases()
        assertFalse(context.getDatabasePath("fm_app.db").exists())
        assertFalse(context.getDatabasePath("fm_profile_legacy.db").exists())

        // The new (encrypted) databases of this schema are never wiped again.
        context.openOrCreateDatabase("fm_app.db", Context.MODE_PRIVATE, null).close()
        Vault(context).wipeLegacyDatabases()
        assertTrue(context.getDatabasePath("fm_app.db").exists())
    }
}
