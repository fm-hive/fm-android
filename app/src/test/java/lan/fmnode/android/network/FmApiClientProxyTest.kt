package lan.fmnode.android.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lan.fmnode.android.log.AppLog
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FmApiClientProxyTest {
    private lateinit var proxy: MockWebServer

    @Before
    fun setUp() {
        AppLog.resetForTests()
        AppLog.init(ApplicationProvider.getApplicationContext<Context>())
        proxy = MockWebServer()
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.shutdown()
        AppLog.resetForTests()
    }

    @Test
    fun `manual HTTP proxy receives the node request`() = runBlocking {
        proxy.enqueue(
            MockResponse().setBody(
                """{"type":"control","data":{"request":"identity","data":{}}}""",
            ),
        )
        val selector = AppProxySelector(
            settings = {
                ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, proxy.hostName, proxy.port)
            },
            systemProxy = { null },
        )
        val http = OkHttpClient.Builder().proxySelector(selector).build()
        val client = FmApiClient("http://fm.example/api", http)

        client.post("identity")

        val recorded = proxy.takeRequest()
        assertEquals("POST http://fm.example/api HTTP/1.1", recorded.requestLine)
    }
}
