package lan.fmnode.android.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.network.FakeNode
import lan.fmnode.android.network.FmApiClient
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.storage.Vault
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #14: a node reports a rejected message as a control message of type "error".
 * The client must turn it into a visible "message not sent" indicator instead
 * of dropping it or showing it as an ordinary message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendErrorIndicatorTest {
    private lateinit var server: MockWebServer
    private lateinit var appDb: AppDatabase
    private lateinit var profileDb: ProfileDatabase

    private val node = FakeNode(ByteArray(32) { (it + 61).toByte() })
    private val userSeed = ByteArray(32) { (it + 11).toByte() }
    private val userPublicKey = CryptoService.publicKeyFromSeed(userSeed)

    private var response: JsonElement = emptySendReceive()

    /** Address the fake node reports for identity; a merge changes it (#11). */
    private var identityAddress = ":0:1"

    private fun emptySendReceive(): JsonObject = buildJsonObject {
        put("messages", buildJsonArray {})
        put("local_packet_ids", buildJsonArray {})
    }

    /** The exact shape the node uses for `deliverErrorMessage` (#14). */
    private fun nodeErrorMessage(text: String): JsonObject = buildJsonObject {
        put("type", "control")
        put("message_id", "error_:0:1_1")
        put("sender_address", ":0:0")
        put("recipient_address", ":0:1")
        put("timestamp", 1000L)
        // A signed message carries no data field: the client reads the JWS payload.
        put(
            "jws",
            node.policyJws(buildJsonObject { put("type", "error"); put("error", text) }.toString()),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        appDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profileDb = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val opened = node.open(request.body.readUtf8())
                val control = Json.parseToJsonElement(opened.control).jsonObject
                val name = (control["request"] as? JsonPrimitive)?.content
                val payload: JsonElement = when (name) {
                    "send_receive" -> response
                    "identity" -> buildJsonObject {
                        put("address", identityAddress)
                        put("name", "Ivan")
                        put("is_admin", false)
                        put("is_moderator", false)
                    }
                    else -> emptySendReceive()
                }
                val encrypted = CryptoService.encryptJwe(
                    payload.toString().toByteArray(),
                    CryptoService.x25519PublicKeyFromEd25519(userPublicKey),
                )
                return MockResponse().setBody(
                    buildJsonObject {
                        put("type", "control")
                        put("jwe", encrypted)
                    }.toString(),
                )
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        profileDb.close()
        appDb.close()
        server.shutdown()
    }

    private fun profile(): ProfileEntity {
        // The seed must be decryptable with the session password, otherwise the
        // session never opens and nothing is synced.
        val encrypted = KeyVault.encrypt(userSeed, "secret", iterations = 1000)
        return ProfileEntity(
            id = "p1",
            title = "Test",
            appUrl = server.url("/app").toString(),
            apiUrl = server.url("/api").toString(),
            nodePublicKeyB64 = Encoding.base64Encode(node.publicKey),
            address = ":0:1",
            displayName = "Ivan",
            isAdmin = false,
            isModerator = false,
            publicKeyB64 = Encoding.base64Encode(userPublicKey),
            encryptedSeedB64 = Encoding.base64Encode(encrypted.encrypted),
            seedSaltB64 = Encoding.base64Encode(encrypted.salt),
            seedIvB64 = Encoding.base64Encode(encrypted.iv),
            seedIterations = encrypted.iterations,
            policyJson = "{}",
            createdAt = 0,
            lastSyncAt = null,
        )
    }

    private suspend fun viewModel(): AppViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = Vault(
            context,
            appDbFactory = { _, _ -> appDb },
            profileDbFactory = { _, _, _ -> profileDb },
        ).apply {
            attachForTests(appDb)
            setExistsForTests(true)
        }
        appDb.profileDao().upsert(profile())
        appDb.appStateDao().put(lan.fmnode.android.storage.AppStateEntity("active_profile_id", "p1"))
        val api = FmApiClient(server.url("/api").toString())
        api.setNodeEd25519PublicKey(node.publicKey)
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(userSeed)
        val session = Session().apply { setPassword("secret") }
        return AppViewModel(NetworkManager(vault) { api }, session)
    }

    @Test
    fun `node rejection becomes a send error in the ui state`() = kotlinx.coroutines.runBlocking {
        response = emptySendReceive()
        val vm = viewModel()
        vm.selectProfile("p1")
        awaitEchoes(vm)

        response = buildJsonObject {
            put("messages", buildJsonArray { add(nodeErrorMessage("echomail requires subject")) })
            put("local_packet_ids", buildJsonArray {})
        }
        vm.refresh()
        awaitSendErrors(vm)

        // The banner shows the node's reason, not a fake chat message.
        assertTrue(
            "send errors: ${vm.state.value.sendErrors}",
            vm.state.value.sendErrors.contains("echomail requires subject"),
        )

        // The refresh that produced the error may still be finishing; a
        // running sync re-adds the same node error after dismiss.
        withTimeout(10_000) { vm.state.first { !it.syncing } }
        vm.dismissSendErrors()
        assertTrue(vm.state.value.sendErrors.isEmpty())
    }

    @Test
    fun `ordinary message is not reported as a send error`() = kotlinx.coroutines.runBlocking {
        response = emptySendReceive()
        val vm = viewModel()
        vm.selectProfile("p1")
        awaitEchoes(vm)

        val otherSeed = ByteArray(32) { (it + 71).toByte() }
        response = buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "echomail")
                            put("message_id", "m1")
                            put("sender_address", ":0:2")
                            put("timestamp", 1000L)
                            put("echo_name", "Newcomers")
                            put(
                                "jws",
                                CryptoService.createJws(
                                    otherSeed,
                                    buildJsonObject {
                                        put("subject", "hi")
                                        put("body", "hello")
                                    }.toString(),
                                    CryptoService.publicKeyFromSeed(otherSeed),
                                ),
                            )
                        },
                    )
                },
            )
            put("local_packet_ids", JsonArray(emptyList()))
        }
        vm.refresh()
        // Let the sync finish: the message must be stored, not reported.
        withTimeout(10_000) {
            while (profileDb.messageDao().getByEchoAndId("Newcomers", "m1") == null) {
                kotlinx.coroutines.delay(50)
            }
        }

        assertFalse(
            "unexpected errors: ${vm.state.value.sendErrors}",
            vm.state.value.sendErrors.isNotEmpty(),
        )
    }

    /**
     * #11: the node renumbers users during a merge; the app adopts the new
     * address, keeps it in the profile row and tells the user about it.
     */
    @Test
    fun `address reported by the node replaces the stale profile address`(): Unit =
        kotlinx.coroutines.runBlocking {
        response = emptySendReceive()
        val vm = viewModel()
        vm.selectProfile("p1")
        awaitEchoes(vm)

        identityAddress = ":0:1:2"
        vm.refresh()
        val notice = withTimeout(10_000) {
            vm.state.first { it.addressNotice != null }.addressNotice
        }

        assertEquals(":0:1:2", notice?.address)
        assertEquals(":0:1:2", vm.state.value.activeProfile?.address)
        assertEquals(":0:1:2", appDb.profileDao().getById("p1")?.address)

        vm.dismissAddressNotice()
        assertNull(vm.state.value.addressNotice)
    }
    private suspend fun awaitEchoes(vm: AppViewModel) =
        withTimeout(10_000) {
            vm.state.first { it.screen == AppScreen.Echoes || it.screen == AppScreen.Networks }
            vm.state.first { it.screen == AppScreen.Echoes }
        }

    private suspend fun awaitSendErrors(vm: AppViewModel) =
        withTimeout(10_000) { vm.state.first { it.sendErrors.isNotEmpty() } }
}
