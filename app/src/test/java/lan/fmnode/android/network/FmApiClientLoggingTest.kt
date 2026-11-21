package lan.fmnode.android.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lan.fmnode.android.log.AppLog
import lan.fmnode.android.log.LogCategory
import lan.fmnode.android.log.LogLevel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FmApiClientLoggingTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        AppLog.resetForTests()
        AppLog.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        AppLog.resetForTests()
    }

    @Test
    fun `network request and response are logged`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"control","data":{"request":"identity","data":{}}}""",
            ),
        )
        val client = FmApiClient(server.url("/api").toString())
        client.post("identity")

        val entries = AppLog.entries.value
        assertTrue(entries.any {
            it.category == LogCategory.NETWORK && it.message.contains("→ identity")
        })
        assertTrue(entries.any {
            it.category == LogCategory.NETWORK && it.message.contains("← identity HTTP 200")
        })
    }

    @Test
    fun `transient network failure is retried for an idempotent request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(
            MockResponse().setBody(
                """{"type":"control","data":{"request":"identity","data":{}}}""",
            ),
        )
        val client = FmApiClient(server.url("/api").toString())

        client.post("identity")

        assertTrue(server.requestCount >= 2)
        assertTrue(
            AppLog.entries.value.any {
                it.category == LogCategory.NETWORK && it.message == "retry identity"
            },
        )
    }

    @Test
    fun `network error is logged`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))
        val client = FmApiClient(server.url("/api").toString())
        runCatching { client.post("identity") }

        val entries = AppLog.entries.value
        assertTrue(entries.any {
            it.category == LogCategory.NETWORK &&
                it.level == LogLevel.WARN &&
                it.message.contains("✕ identity HTTP 500")
        })
    }
}
