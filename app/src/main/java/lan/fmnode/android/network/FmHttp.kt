package lan.fmnode.android.network

import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * HTTP client for FM nodes.
 *
 * Nodes commonly use self-signed certificates (and sometimes plain HTTP), so
 * certificate and hostname verification are disabled everywhere (#4). The
 * traffic is authenticated by the node/user signatures on the envelopes, not
 * by TLS.
 */
object FmHttp {
    /** Trust manager that accepts every certificate. */
    val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager), SecureRandom())
    }

    /** Selector installed by the application; delegates to live settings. */
    private val installedSelector = AtomicReference<ProxySelector>(defaultSelector())

    private val delegatingSelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            installedSelector.get().select(uri)

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
            installedSelector.get().connectFailed(uri, sa, ioe)
        }
    }

    /** Shared client used by every regular API request. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        .proxySelector(delegatingSelector)
        // The default 10 s call timeout is too short for a busy node (large
        // outbound queues, polling/merge). Explicit values also make timeout
        // failures visible in the log (#50).
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Client for the first send_receive request. A node that does not support
     * the polling protocol can hold this request while it processes messages,
     * so it gets a much longer read/call timeout than ordinary API calls.
     */
    val sendReceiveClient: OkHttpClient = client.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    /** Installs the application proxy selector (system/manual/direct). */
    fun installProxySelector(selector: ProxySelector) {
        installedSelector.set(selector)
        evictConnections()
    }

    /** Drops pooled sockets after a network, VPN or proxy change. */
    fun evictConnections() {
        client.connectionPool.evictAll()
        sendReceiveClient.connectionPool.evictAll()
    }

    private fun defaultSelector(): ProxySelector =
        ProxySelector.getDefault() ?: object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
        }
}
