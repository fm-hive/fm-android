package lan.fmnode.android.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FmApiClientPollingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun control(dataJson: String): String =
        """{"type":"control","data":{"request":"send_receive","data":$dataJson}}"""

    @Test
    fun `send_receive follows an in-progress response and returns the final data`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(control("""{"connect_id":"poll-1","status":"in progress"}""")),
        )
        server.enqueue(
            MockResponse().setBody(control("""{"messages":[],"local_packet_ids":[1]}""")),
        )
        val client = FmApiClient(server.url("/api").toString())

        val result = client.postSendReceive("""{"messages":[]}""").jsonObject

        assertEquals(1, result["local_packet_ids"]!!.jsonArray[0].jsonPrimitive.int)
        assertEquals(2, server.requestCount)
        server.takeRequest() // the original send_receive
        val pollText = server.takeRequest().body.readUtf8()
        val controlData = json(pollText)["data"]!!.jsonObject
        val payload = controlData["data"]!!.jsonObject
        assertEquals("poll-1", payload["connect_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a normal send_receive response is returned without polling`() = runBlocking {
        server.enqueue(MockResponse().setBody(control("""{"messages":[]}""")))
        val client = FmApiClient(server.url("/api").toString())

        val result = client.postSendReceive("""{"messages":[]}""").jsonObject

        assertTrue(result.containsKey("messages"))
        assertEquals(1, server.requestCount)
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
