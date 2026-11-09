package lan.fmnode.android.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.storage.OutboxEntity
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.ProfileEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #11: a merge renumbers users, so queued payloads keep the old address and are
 * rejected as a sender mismatch until the payload is rewritten.
 */
class OutboxAddressTest {
    @Test
    fun `from field is rewritten`() {
        val data = """{"type":"echomail","from":":0:1:2","to":"all","body":"hi"}"""

        val rewritten = OutboxAddress.rewrite(data, ":0:1:2", ":0:2:2")

        assertEquals("""{"type":"echomail","from":":0:2:2","to":"all","body":"hi"}""", rewritten)
    }

    @Test
    fun `protocol payload node1 field is rewritten`() {
        val data = """{"proto":"vote","from":":0:1:2","node1":":0:1:2","vote":"accept"}"""

        val rewritten = OutboxAddress.rewrite(data, ":0:1:2", ":0:3:2")

        assertEquals("""{"proto":"vote","from":":0:3:2","node1":":0:3:2","vote":"accept"}""", rewritten)
    }

    @Test
    fun `payload of another sender is left alone`() {
        val data = """{"from":":0:9:9","body":"hi"}"""

        assertNull(OutboxAddress.rewrite(data, ":0:1:2", ":0:2:2"))
    }

    @Test
    fun `unchanged or empty addresses are ignored`() {
        val data = """{"from":":0:1:2"}"""

        assertNull(OutboxAddress.rewrite(data, ":0:1:2", ":0:1:2"))
        assertNull(OutboxAddress.rewrite(data, "", ":0:2:2"))
        assertNull(OutboxAddress.rewrite(data, ":0:1:2", ""))
    }

    @Test
    fun `invalid json is skipped`() {
        assertNull(OutboxAddress.rewrite("not json", ":0:1:2", ":0:2:2"))
    }
}

/**
 * #11: the session adopts the address the node reports and rewrites the queued
 * messages before the next flush.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionAddressChangeTest {
    private lateinit var db: ProfileDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(address: String): NetworkSession {
        val profile = ProfileEntity(
            id = "p1",
            title = "node1",
            appUrl = "http://10.0.2.2:8444/app",
            apiUrl = "http://10.0.2.2:8444/api",
            nodePublicKeyB64 = "",
            address = address,
            displayName = "android1",
            isAdmin = false,
            isModerator = false,
            publicKeyB64 = "",
            encryptedSeedB64 = "",
            seedSaltB64 = "",
            seedIvB64 = "",
            seedIterations = 1,
            policyJson = "",
            createdAt = 0,
            lastSyncAt = null,
        )
        return NetworkSession(profile, db, FmApiClient("http://127.0.0.1:1"), CryptoService.generateSeed())
    }

    @Test
    fun `adopting a new address rewrites the outbox`() = runBlocking {
        val opened = session(":0:1:2")
        db.outboxDao().insert(
            OutboxEntity(
                type = "echomail",
                echoName = "Newcomers",
                recipientAddress = null,
                dataJson = """{"from":":0:1:2","subject":"hi"}""",
                createdAt = 0,
                attempts = 0,
                lastError = null,
            ),
        )

        opened.applyAddressChange(":0:2:2")

        assertEquals(":0:2:2", opened.profile.address)
        assertEquals("""{"from":":0:2:2","subject":"hi"}""", db.outboxDao().getAll().single().dataJson)
    }

    @Test
    fun `same address keeps the queue untouched`() = runBlocking {
        val opened = session(":0:1:2")
        db.outboxDao().insert(
            OutboxEntity(
                type = "echomail",
                echoName = "Newcomers",
                recipientAddress = null,
                dataJson = """{"from":":0:1:2"}""",
                createdAt = 0,
                attempts = 0,
                lastError = null,
            ),
        )

        opened.applyAddressChange(":0:1:2")

        assertEquals(":0:1:2", opened.profile.address)
        assertEquals("""{"from":":0:1:2"}""", db.outboxDao().getAll().single().dataJson)
    }
}
