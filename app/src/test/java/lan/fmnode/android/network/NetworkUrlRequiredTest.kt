package lan.fmnode.android.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.Vault
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A network profile needs a real endpoint. The import used to store an empty
 * `apiUrl`, and the failure only surfaced much later as OkHttp's
 * "Expected URL scheme http or https but no scheme was found".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkUrlRequiredTest {
    private lateinit var db: AppDatabase
    private lateinit var profileDb: ProfileDatabase
    private lateinit var manager: NetworkManager

    private val seed = ByteArray(32) { (it + 61).toByte() }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profileDb = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vault = Vault(
            context,
            appDbFactory = { _, _ -> db },
            profileDbFactory = { _, _, _ -> profileDb },
        ).apply { attachForTests(db) }
        manager = NetworkManager(vault)
    }

    @After
    fun tearDown() {
        profileDb.close()
        db.close()
    }

    private suspend fun adopt(apiUrl: String) = manager.adoptProfile(
        password = "secret-pass",
        seed = seed,
        address = ":0:1",
        displayName = "K",
        nodePublicKeyB64 = null,
        appUrl = apiUrl,
        apiUrl = apiUrl,
    )

    @Test
    fun `an empty endpoint is rejected with a readable message`() = runBlocking {
        val error: Throwable? = runCatching { adopt("") }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(
            "unexpected message: ${error?.message}",
            error!!.message.orEmpty().contains("http://"),
        )
        assertTrue(db.profileDao().getAll().isEmpty())
    }

    @Test
    fun `an endpoint without a scheme is rejected`() = runBlocking {
        for (raw in listOf("2.28.13.140", "2.28.13.140/msoijwer43asdf", "//node.example", "node.example")) {
            val error: Throwable? = runCatching { adopt(raw) }.exceptionOrNull()
            assertNotNull("expected \"$raw\" to be rejected", error)
        }
        assertTrue(db.profileDao().getAll().isEmpty())
    }

    @Test
    fun `a proper endpoint is accepted and stored`() = runBlocking {
        val profile = adopt("https://2.28.13.140/msoijwer43asdf/api")

        val stored = db.profileDao().getById(profile.id)
        assertNotNull(stored)
        assertTrue(stored!!.apiUrl.startsWith("https://"))
        // The profile opens without touching the node.
        val opened = runCatching { manager.openSession(stored, "secret-pass") }
        assertTrue("opening must not throw: ${opened.exceptionOrNull()}", opened.isSuccess)
    }
}
