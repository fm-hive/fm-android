package lan.fmnode.android.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.KeyVault
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-network core: adding networks through the policy flow stores a profile
 * with its own encrypted key; switching and deleting keep one active profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var manager: NetworkManager
    private val node = FakeNode(ByteArray(32) { (it + 31).toByte() })

    private val policyPayload = buildJsonObject {
        put("id", "policy-1")
        put("policy", buildJsonObject { put("text", "Network rules") })
    }.toString()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = NetworkManager(db)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val opened = node.open(request.body.readUtf8())
                val control = Json.parseToJsonElement(opened.control).jsonObject
                val data = control["data"]?.jsonObject
                return if (data?.get("policy") != null) {
                    // Phase 2: verify the user's policy signature and reply with
                    // the assigned address, encrypted for that user key.
                    val userPub = Encoding.base64Decode(data["sig_pkey"]!!.jsonPrimitive.content)
                    val userJws = data["policy"]!!.jsonPrimitive.content
                    check(CryptoService.verifyJws(userJws, userPub))
                    val response = buildJsonObject {
                        put("request", "policy")
                        put(
                            "data",
                            buildJsonObject {
                                put("address", ":0:1")
                                put("is_admin", false)
                                put("is_moderator", false)
                                put("name", "Ivan")
                            },
                        )
                    }.toString()
                    MockResponse().setBody(node.encryptedResponse(response, userPub))
                } else {
                    // Phase 1: plain control response with the signed policy.
                    val envelope = buildJsonObject {
                        put("type", "control")
                        put(
                            "data",
                            buildJsonObject {
                                put("request", "policy")
                                put(
                                    "data",
                                    buildJsonObject {
                                        put("policy", node.policyJws(policyPayload))
                                        put("sig_pkey", node.sigPkeyB64())
                                    },
                                )
                            },
                        )
                    }.toString()
                    MockResponse().setBody(envelope)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun invite(): Invite = InviteParser.parse(
        "${server.url("/api")}?pubkey=${Encoding.base64UrlEncode(node.publicKey)}"
    )!!

    private fun addNetwork(password: String, name: String = "Ivan"): lan.fmnode.android.storage.ProfileEntity =
        runBlocking {
            val invite = Invite(server.url("/api").toString(), server.url("/api").toString(), node.publicKey, null, null)
            val policy = manager.fetchPolicy(invite)
            manager.register(invite, policy, password, name)
        }

    @Test
    fun `registers a network with its own encrypted key`() {
        val profile = addNetwork("secret-pass")

        val stored = runBlocking { db.profileDao().getAll() }
        assertEquals(1, stored.size)
        assertEquals(":0:1", stored[0].address)
        assertEquals(node.sigPkeyB64(), stored[0].nodePublicKeyB64)
        assertEquals(server.url("/api").toString(), stored[0].apiUrl)
        assertEquals(profile.id, runBlocking { manager.activeProfileId() })

        val seed = KeyVault.decrypt(stored[0].encryptedSeed(), "secret-pass")
        assertEquals(32, seed.size)
        assertArrayEquals(
            Encoding.base64Decode(stored[0].publicKeyB64),
            CryptoService.publicKeyFromSeed(seed),
        )
    }

    @Test
    fun `password validation and multiple networks`() {
        val first = addNetwork("secret-pass")
        val second = addNetwork("secret-pass", name = "Petr")

        assertEquals(2, runBlocking { db.profileDao().getAll() }.size)
        assertEquals(second.id, runBlocking { manager.activeProfileId() })
        assertTrue(runBlocking { manager.isPasswordValid("secret-pass") })
        assertFalse(runBlocking { manager.isPasswordValid("nope") })
        assertNotNull(runBlocking { manager.activeProfile() })
        assertTrue(first.id != second.id)
    }

    @Test
    fun `deleting the active network switches to the remaining one`() {
        val first = addNetwork("secret-pass")
        val second = addNetwork("secret-pass")

        runBlocking { manager.deleteProfile(second.id) }
        assertEquals(first.id, runBlocking { manager.activeProfileId() })

        runBlocking { manager.deleteProfile(first.id) }
        assertNull(runBlocking { manager.activeProfileId() })
        assertTrue(runBlocking { db.profileDao().getAll() }.isEmpty())
    }
}
