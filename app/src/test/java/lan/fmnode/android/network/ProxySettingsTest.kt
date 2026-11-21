package lan.fmnode.android.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProxySettingsTest {
    @Test
    fun `manual settings require a host and a real port`() {
        assertFalse(ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "", 8080).hasValidManualEndpoint())
        assertFalse(ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "127.0.0.1", 0).hasValidManualEndpoint())
        assertFalse(ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "127.0.0.1", 70000).hasValidManualEndpoint())
        assertTrue(ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "127.0.0.1", 8080).hasValidManualEndpoint())
        assertNull(ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "", 8080).manualProxy)
    }

    @Test
    fun `manual proxy keeps the selected protocol`() {
        val http = ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "127.0.0.1", 8080).manualProxy
        val socks = ProxySettings(ProxyMode.MANUAL, ProxyKind.SOCKS, "127.0.0.1", 1080).manualProxy

        assertEquals(Proxy.Type.HTTP, http?.type())
        assertEquals(Proxy.Type.SOCKS, socks?.type())
        assertEquals(InetSocketAddress("127.0.0.1", 1080), socks?.address())
    }

    @Test
    fun `proxy selector follows the selected mode`() {
        val system = Proxy(Proxy.Type.HTTP, InetSocketAddress("10.0.0.1", 3128))
        val manual = ProxySettings(ProxyMode.MANUAL, ProxyKind.SOCKS, "127.0.0.1", 1080)
        val uri = URI("https://fm.example/api")

        val systemMode = AppProxySelector({ ProxySettings() }, { system })
        assertEquals(listOf(system), systemMode.select(uri))

        val directMode = AppProxySelector({ ProxySettings(ProxyMode.DIRECT) }, { system })
        assertEquals(listOf(Proxy.NO_PROXY), directMode.select(uri))

        val manualMode = AppProxySelector({ manual }, { system })
        val selected = manualMode.select(uri).single()
        assertEquals(Proxy.Type.SOCKS, selected.type())
        assertEquals(InetSocketAddress("127.0.0.1", 1080), selected.address())
    }

    @Test
    fun `system mode without an Android proxy falls back to direct`() {
        val selector = AppProxySelector({ ProxySettings(ProxyMode.SYSTEM) }, { null })
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://fm.example/api")))
    }

    @Test
    fun `preferences persist a valid manual proxy and reject an invalid one`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("fm_network", Context.MODE_PRIVATE).edit().clear().commit()

        val prefs = NetworkPreferences(context)
        assertEquals(ProxyMode.SYSTEM, prefs.current.mode)
        assertFalse(prefs.save(ProxySettings(ProxyMode.MANUAL, ProxyKind.HTTP, "", 8080)))
        assertTrue(prefs.save(ProxySettings(ProxyMode.MANUAL, ProxyKind.SOCKS, " 127.0.0.1 ", 1080)))

        val reopened = NetworkPreferences(context)
        assertEquals(ProxyMode.MANUAL, reopened.current.mode)
        assertEquals(ProxyKind.SOCKS, reopened.current.kind)
        assertEquals("127.0.0.1", reopened.current.host)
        assertEquals(1080, reopened.current.port)
        assertEquals("socks 127.0.0.1:1080", reopened.current.describe())
    }
}
