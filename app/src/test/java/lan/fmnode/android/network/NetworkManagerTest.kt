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
import lan.fmnode.android.storage.Vault
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
        val vault = Vault(
            context,
            appDbFactory = { _, _ -> db },
            profileDbFactory = { _, _, _ -> error("profile database is not used in this test") },
        ).apply { attachForTests(db) }
        manager = NetworkManager(vault)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val opened = node.open(request.body.readUtf8())
                val control = Json.parseToJsonElement(opened.control).jsonObject
                val data = control["data"]?.jsonObject
                val requestName = control["request"]?.jsonPrimitive?.content
                if (requestName == "recover") {
                    val userPub = Encoding.base64Decode(data!!["pubkey"]!!.jsonPrimitive.content)
                    val jws = data["jws"]!!.jsonPrimitive.content
                    check(CryptoService.verifyJws(jws, userPub))
                    val payload = CryptoService.jwsPayload(jws)!!
                    check(payload.contains(":0:1"))
                    val response = buildJsonObject {
                        put("request", "recover")
                        put(
                            "data",
                            buildJsonObject {
                                put("status", "ok")
                                put("emergency_echo", "Emergency")
                            },
                        )
                    }.toString()
                    return MockResponse().setBody(node.encryptedResponse(response, userPub))
                }
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
    fun `registration can reuse an imported key`() = runBlocking {
        val importedSeed = ByteArray(32) { (it + 99).toByte() }
        val invite = Invite(server.url("/api").toString(), server.url("/api").toString(), node.publicKey, null, null)
        val policy = manager.fetchPolicy(invite)
        val profile = manager.register(
            invite = invite,
            policy = policy,
            password = "secret-pass",
            displayName = "Ivan",
            seed = importedSeed,
        )
        assertEquals(
            Encoding.base64Encode(CryptoService.publicKeyFromSeed(importedSeed)),
            profile.publicKeyB64,
        )
        val stored = db.profileDao().getById(profile.id)!!
        assertArrayEquals(importedSeed, KeyVault.decrypt(stored.encryptedSeed(), "secret-pass"))
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

    @Test
    fun `an imported identity is adopted without contacting the node`() = runBlocking {
        val seed = ByteArray(32) { (it + 41).toByte() }
        val requestsBefore = server.requestCount

        val profile = manager.adoptProfile(
            password = "secret-pass",
            seed = seed,
            address = ":0:1",
            displayName = "K",
            nodePublicKeyB64 = node.sigPkeyB64(),
            appUrl = "https://node.example/app",
            apiUrl = "https://node.example/api",
        )

        assertEquals(requestsBefore, server.requestCount)
        assertEquals(":0:1", profile.address)
        assertEquals("K", profile.displayName)
        assertEquals(node.sigPkeyB64(), profile.nodePublicKeyB64)
        assertEquals(Encoding.base64Encode(CryptoService.publicKeyFromSeed(seed)), profile.publicKeyB64)
        assertEquals("node.example (:0:1)", profile.title)
        assertEquals(profile.id, manager.activeProfileId())
        assertArrayEquals(seed, KeyVault.decrypt(db.profileDao().getById(profile.id)!!.encryptedSeed(), "secret-pass"))
    }

    @Test
    fun `importing the same identity twice updates one profile`() = runBlocking {
        val seed = ByteArray(32) { (it + 51).toByte() }
        val adopt = suspend {
            manager.adoptProfile(
                password = "secret-pass",
                seed = seed,
                address = ":0:1",
                displayName = "K",
                nodePublicKeyB64 = node.sigPkeyB64(),
                appUrl = "https://node.example/app",
                apiUrl = "https://node.example/api",
            )
        }

        val first = adopt()
        val second = adopt()

        assertEquals(first.id, second.id)
        assertEquals(1, db.profileDao().getAll().size)
    }

    @Test
    fun `recover keeps the identity address and signs the recovery request`() {
        val profile = addNetwork("secret-pass")
        val recoveryInvite = InviteParser.parse(
            "${server.url("/api")}?pubkey=${Encoding.base64UrlEncode(node.publicKey)}&address=:0:1:0"
        )!!

        val policy = runBlocking { manager.recover(recoveryInvite, profile, "secret-pass") }

        assertNotNull(policy)
        val stored = runBlocking { db.profileDao().getAll() }.single()
        assertEquals(profile.address, stored.address)
        assertEquals(server.url("/api").toString(), stored.apiUrl)
        assertEquals(profile.id, runBlocking { manager.activeProfileId() })
    }
}
