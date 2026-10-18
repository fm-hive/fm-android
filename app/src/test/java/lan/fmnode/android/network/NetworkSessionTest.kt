package lan.fmnode.android.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.storage.PubkeyEntity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var db: ProfileDatabase
    private lateinit var session: NetworkSession

    private val node = FakeNode(ByteArray(32) { (it + 61).toByte() })
    private val userSeed = ByteArray(32) { (it + 11).toByte() }
    private val userPublicKey = CryptoService.publicKeyFromSeed(userSeed)
    private val otherSeed = ByteArray(32) { (it + 71).toByte() }
    private val otherPublicKey = CryptoService.publicKeyFromSeed(otherSeed)
    private val otherX25519 = CryptoService.x25519PrivateKeyFromSeed(otherSeed)

    private val sentMessages = mutableListOf<JsonObject>()
    private val serviceRequests = mutableListOf<JsonObject>()
    private var lastSendReceiveControl: JsonObject? = null
    private var echoList: JsonElement = buildJsonArray {}
    private var sendReceiveResponse: JsonObject = emptySendReceive()
    private var otherAddress = ":0:2"

    private fun emptySendReceive(): JsonObject = buildJsonObject {
        put("messages", buildJsonArray {})
        put("local_packet_ids", buildJsonArray {})
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val opened = node.open(request.body.readUtf8())
                val control = Json.parseToJsonElement(opened.control).jsonObject
                val name = (control["request"] as? JsonPrimitive)?.content
                val data = control["data"] as? JsonObject
                val response: JsonElement = when (name) {
                    "echoes" -> echoList
                    "send_receive" -> {
                        lastSendReceiveControl = control
                        val outgoing = data?.get("messages") as? JsonArray
                        outgoing?.forEach { element ->
                            (element as? JsonObject)?.let { sentMessages += it }
                        }
                        // A real node does not re-deliver the same pending
                        // message in the response to a send: answer sends with
                        // an empty list (also prevents a feedback loop when the
                        // auto pubkey response itself is sent).
                        if ((outgoing?.size ?: 0) > 0) emptySendReceive() else sendReceiveResponse
                    }
                    "service" -> {
                        (data?.get("messages") as? JsonArray)?.forEach { element ->
                            (element as? JsonObject)?.let { serviceRequests += it }
                        }
                        buildJsonArray {
                            add(buildJsonObject { put("index", 0); put("status", "request_accepted") })
                        }
                    }
                    else -> JsonObject(emptyMap())
                }
                val encrypted = CryptoService.encryptJwe(
                    response.toString().toByteArray(),
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

        val profile = ProfileEntity(
            id = "profile-1",
            title = "Test",
            appUrl = server.url("/app").toString(),
            apiUrl = server.url("/api").toString(),
            nodePublicKeyB64 = Encoding.base64Encode(node.publicKey),
            address = ":0:1",
            displayName = "Ivan",
            isAdmin = false,
            isModerator = false,
            publicKeyB64 = Encoding.base64Encode(userPublicKey),
            encryptedSeedB64 = "",
            seedSaltB64 = "",
            seedIvB64 = "",
            seedIterations = 100_000,
            policyJson = "{}",
            createdAt = 0,
            lastSyncAt = null,
        )
        val api = FmApiClient(profile.apiUrl)
        api.setNodeEd25519PublicKey(node.publicKey)
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(userSeed)
        session = NetworkSession(profile, db, api, userSeed)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun data(vararg pairs: Pair<String, String>): String = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun incomingEcho(messageId: String, echoName: String, dataJson: String): JsonObject =
        buildJsonObject {
            put("type", "echomail")
            put("message_id", messageId)
            put("sender_address", otherAddress)
            put("timestamp", 1000)
            put("echo_name", echoName)
            put("jws", CryptoService.createJws(otherSeed, dataJson, otherPublicKey))
        }

    private fun incomingNetmail(messageId: String, payload: String): JsonObject = buildJsonObject {
        put("type", "netmail")
        put("message_id", messageId)
        put("sender_address", otherAddress)
        put("recipient_address", ":0:1")
        put("timestamp", 1000)
        put("jws", CryptoService.createJws(otherSeed, "", otherPublicKey))
        put(
            "jwe",
            CryptoService.encryptJwe(
                payload.toByteArray(),
                CryptoService.x25519PublicKeyFromEd25519(userPublicKey),
            ),
        )
    }

    @Test
    fun `refreshEchoes stores the list and keeps local read marks`() {
        echoList = buildJsonArray {
            add(buildJsonObject { put("id", 1); put("name", "General"); put("subscribed", true) })
            add(buildJsonObject { put("id", 2); put("name", "Tech"); put("subscribed", false) })
        }
        val echoes = runBlocking { session.refreshEchoes() }
        assertEquals(2, echoes.size)

        runBlocking { db.echoDao().setLastRead("General", 777) }
        runBlocking { session.refreshEchoes() }
        val general = runBlocking { db.echoDao().getByName("General") }!!
        assertEquals(777, general.lastReadTimestamp)
        assertTrue(general.subscribed)
        assertEquals(false, runBlocking { db.echoDao().getByName("Tech") }!!.subscribed)
    }

    @Test
    fun `sync stores echomail and netmail and confirms delivered packets`() {
        val echoData = data("subject" to "Hi", "body" to "Hello", "sender_name" to "Petya")
        val netmailData = data("subject" to "Secret", "body" to "Private")
        sendReceiveResponse = buildJsonObject {
            put("messages", buildJsonArray {
                add(incomingEcho("m1", "General", echoData))
                add(incomingNetmail("m2", netmailData))
            })
            put("local_packet_ids", buildJsonArray { add(JsonPrimitive(42)) })
        }

        val outcome = runBlocking { session.sync() }
        assertEquals(2, outcome.received)
        assertEquals(1, outcome.acknowledged)

        val echomail = runBlocking { db.messageDao().getById("m1") }!!
        assertEquals("General", echomail.echoName)
        assertEquals(echoData, echomail.dataJson)

        val netmail = runBlocking { db.messageDao().getById("m2") }!!
        assertEquals(NetworkSession.NETMAIL_ECHO, netmail.echoName)
        assertEquals(netmailData, netmail.dataJson)
        assertEquals("Petya", runBlocking { db.nameDao().get(otherAddress) }!!.name)

        // The delivered packet id is confirmed on the next sync.
        sendReceiveResponse = emptySendReceive()
        runBlocking { session.sync() }
        val delivered = (lastSendReceiveControl!!["data"] as JsonObject)["delivered_local_ids"] as JsonArray
        assertEquals(42L, (delivered[0] as JsonPrimitive).content.toLong())
    }

    @Test
    fun `pubkey response is cached and not stored as a message`() {
        val payload = data(
            "type" to "pubkey_response",
            "address" to otherAddress,
            "sign_public_key" to Encoding.base64Encode(otherPublicKey),
        )
        sendReceiveResponse = buildJsonObject {
            put("messages", buildJsonArray { add(incomingNetmail("k1", payload)) })
            put("local_packet_ids", buildJsonArray {})
        }
        runBlocking { session.sync() }

        assertNotNull(runBlocking { db.pubkeyDao().get(otherAddress) })
        assertNull(runBlocking { db.messageDao().getById("k1") })
    }

    @Test
    fun `postEcho sends a signed message`() {
        runBlocking {
            val result = session.postEcho("General", "Тема", "Текст")
            assertEquals(false, result.queued)
            assertEquals(1, sentMessages.size)
            val message = sentMessages[0]
            assertEquals("echomail", message["type"]?.jsonPrimitive?.content)
            assertEquals("General", message["echo_name"]?.jsonPrimitive?.content)
            assertEquals(":0:1", message["sender_address"]?.jsonPrimitive?.content)

            val jws = message["jws"]!!.jsonPrimitive.content
            assertTrue(CryptoService.verifyJws(jws, userPublicKey))
            val sentData = Json.parseToJsonElement(CryptoService.jwsPayload(jws)!!).jsonObject
            assertEquals("Тема", sentData["subject"]!!.jsonPrimitive.content)
            assertEquals("Текст", sentData["body"]!!.jsonPrimitive.content)
            assertEquals("Ivan", sentData["sender_name"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `netmail is encrypted for the recipient`() {
        runBlocking {
            db.pubkeyDao().upsert(
                PubkeyEntity(
                    address = ":0:9",
                    signPublicKeyB64 = Encoding.base64Encode(otherPublicKey),
                    thumbprint = CryptoService.thumbprint(otherPublicKey),
                    updatedAt = 0,
                ),
            )
            val result = session.sendNetmail(":0:9", "Subj", "Body")
            assertEquals(false, result.queued)
            assertEquals(1, sentMessages.size)
            val message = sentMessages[0]
            assertEquals("netmail", message["type"]?.jsonPrimitive?.content)
            assertEquals(":0:9", message["recipient_address"]?.jsonPrimitive?.content)
            val plaintext = CryptoService.decryptJwe(
                message["jwe"]!!.jsonPrimitive.content,
                otherX25519,
            )
            val decrypted = Json.parseToJsonElement(String(plaintext)).jsonObject
            assertEquals("Subj", decrypted["subject"]!!.jsonPrimitive.content)
            assertEquals("Body", decrypted["body"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `postVote sends a signed vote message`() {
        runBlocking {
            val result = session.postVote("General", "Vote: choose", "yes")
            assertEquals(false, result.queued)
            val message = sentMessages.last()
            assertEquals("vote", message["type"]!!.jsonPrimitive.content)
            assertEquals("General", message["echo_name"]!!.jsonPrimitive.content)
            val jws = message["jws"]!!.jsonPrimitive.content
            assertTrue(CryptoService.verifyJws(jws, userPublicKey))
            val data = Json.parseToJsonElement(CryptoService.jwsPayload(jws)!!).jsonObject
            assertEquals("vote", data["proto"]!!.jsonPrimitive.content)
            assertEquals("vote", data["type"]!!.jsonPrimitive.content)
            assertEquals("yes", data["vote"]!!.jsonPrimitive.content)
            assertEquals("Vote: choose", data["poster_message"]!!.jsonPrimitive.content)
            assertEquals(":0:1", data["from"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `missing recipient key requests it when enabled`() {
        runBlocking {
            session.setAutoRequestPubkeys(true)
            val result = session.sendNetmail(":0:8", "Subj", "Body")
            assertTrue(result.needsKey)
            assertTrue(sentMessages.isEmpty())
            assertEquals(1, serviceRequests.size)
            val request = serviceRequests[0]
            assertEquals("pubkey", request["type"]!!.jsonPrimitive.content)
            assertEquals(":0:8", request["address"]!!.jsonPrimitive.content)
            assertEquals(
                Encoding.base64Encode(userPublicKey),
                request["requestor_pubkey"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `pubkey requests are queued and answered when enabled`() {
        runBlocking {
            val requestPayload = data(
                "type" to "pubkey_request",
                "address" to ":0:7",
                "requestor_pubkey" to Encoding.base64Encode(otherPublicKey),
            )
            sendReceiveResponse = buildJsonObject {
                put("messages", buildJsonArray { add(incomingNetmail("r1", requestPayload)) })
                put("local_packet_ids", buildJsonArray {})
            }
            session.sync()

            val pending = session.pendingPubkeyRequests()
            assertEquals(1, pending.size)
            assertEquals(":0:7", pending[0].first)
            // The key from the request is also cached for verification.
            assertNotNull(db.pubkeyDao().get(":0:7"))

            // With auto-send enabled the answer goes out immediately.
            session.setAutoSendPubkey(true)
            sendReceiveResponse = buildJsonObject {
                put("messages", buildJsonArray { add(incomingNetmail("r2", requestPayload)) })
                put("local_packet_ids", buildJsonArray {})
            }
            session.sync()
            val response = sentMessages.last()
            assertEquals("netmail", response["type"]!!.jsonPrimitive.content)
            val plaintext = CryptoService.decryptJwe(
                response["jwe"]!!.jsonPrimitive.content,
                otherX25519,
            )
            val decrypted = Json.parseToJsonElement(String(plaintext)).jsonObject
            assertEquals("pubkey_response", decrypted["type"]!!.jsonPrimitive.content)
            assertEquals(":0:1", decrypted["address"]!!.jsonPrimitive.content)
            assertEquals(
                Encoding.base64Encode(userPublicKey),
                decrypted["sign_public_key"]!!.jsonPrimitive.content,
            )
        }
    }
}
