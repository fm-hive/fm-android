package lan.fmnode.android.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.Vault
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An imported identity must be usable when the node is unreachable: its keys are
 * in the vault and `openSession` must not depend on the network, otherwise the
 * user is bounced back to the networks screen right after the import.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenSessionOfflineTest {
    private lateinit var db: AppDatabase
    private lateinit var profileDb: ProfileDatabase
    private lateinit var vault: Vault
    private lateinit var session: Session
    private var server: MockWebServer? = null

    private val seed = ByteArray(32) { (it + 23).toByte() }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profileDb = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = Vault(
            context,
            appDbFactory = { _, _ -> db },
            profileDbFactory = { _, _, _ -> profileDb },
        ).apply {
            attachForTests(db)
            setExistsForTests(true)
        }
        session = Session().apply { setPassword("secret-pass") }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server?.shutdown()
        if (::profileDb.isInitialized) profileDb.close()
        if (::db.isInitialized) db.close()
    }

    /** A node that fails every request the way an offline node does. */
    private fun offlineNode(): String {
        val started = MockWebServer()
        started.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(503).setBody("""{"error":"node is offline"}""")
        }
        started.start()
        server = started
        return started.url("/api").toString()
    }

    @Test
    fun `an imported identity is stored in the vault before the session opens`() = runBlocking {
        val apiUrl = offlineNode()
        val manager = NetworkManager(vault)

        val profile = manager.adoptProfile(
            password = "secret-pass",
            seed = seed,
            address = ":0:1",
            displayName = "K",
            nodePublicKeyB64 = Encoding.base64Encode(CryptoService.publicKeyFromSeed(seed)),
            appUrl = apiUrl.removeSuffix("/api"),
            apiUrl = apiUrl,
        )

        val stored = requireNotNull(db.profileDao().getById(profile.id))
        assertEquals(profile.id, manager.activeProfileId())
        assertEquals(32, KeyVault.decrypt(stored.encryptedSeed(), "secret-pass").size)
        assertTrue(CryptoService.publicKeyFromSeed(seed).contentEquals(
            CryptoService.publicKeyFromSeed(KeyVault.decrypt(stored.encryptedSeed(), "secret-pass")),
        ))
    }

    @Test
    fun `opening the session does not need the node`() = runBlocking {
        val apiUrl = offlineNode()
        val manager = NetworkManager(vault)
        val profile = manager.adoptProfile(
            password = "secret-pass",
            seed = seed,
            address = ":0:1",
            displayName = "K",
            nodePublicKeyB64 = Encoding.base64Encode(CryptoService.publicKeyFromSeed(seed)),
            appUrl = apiUrl.removeSuffix("/api"),
            apiUrl = apiUrl,
        )

        val opened = runCatching { manager.openSession(profile, "secret-pass") }
        assertTrue("opening the session must not touch the node: ${opened.exceptionOrNull()}", opened.isSuccess)
        assertEquals(0, server?.requestCount ?: 0)
    }

    @Test
    fun `a profile without a node key still opens`() = runBlocking {
        val apiUrl = offlineNode()
        val manager = NetworkManager(vault)
        // A keys export carries no node key: the endpoint must still work.
        val profile = manager.adoptProfile(
            password = "secret-pass",
            seed = seed,
            address = ":0:1",
            displayName = "K",
            nodePublicKeyB64 = null,
            appUrl = apiUrl.removeSuffix("/api"),
            apiUrl = apiUrl,
        )
        assertEquals("", profile.nodePublicKeyB64)

        val opened = runCatching { manager.openSession(profile, "secret-pass") }
        assertTrue("opening the session must not throw: ${opened.exceptionOrNull()}", opened.isSuccess)
    }

    @Test
    fun `the stored profile keeps the keys and endpoint of the export`() = runBlocking {
        val apiUrl = offlineNode()
        val manager = NetworkManager(vault)
        val expectedPublic = Encoding.base64Encode(CryptoService.publicKeyFromSeed(seed))

        manager.adoptProfile(
            password = "secret-pass",
            seed = seed,
            address = ":0:1",
            displayName = "K",
            nodePublicKeyB64 = Encoding.base64Encode(CryptoService.publicKeyFromSeed(ByteArray(32) { 5 })),
            appUrl = apiUrl.removeSuffix("/api"),
            apiUrl = apiUrl,
        )

        val stored = db.profileDao().getAll().single()
        assertEquals(expectedPublic, stored.publicKeyB64)
        assertEquals(":0:1", stored.address)
        assertEquals("K", stored.displayName)
        assertEquals(apiUrl, stored.apiUrl)
    }
}
