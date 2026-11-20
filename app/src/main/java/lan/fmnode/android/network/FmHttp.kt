package lan.fmnode.android.network

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
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

    /** Shared client used by every API request. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAllManager)
        .hostnameVerifier { _, _ -> true }
        // The default 10 s call timeout is too short for a busy node (large
        // outbound queues, polling/merge). Explicit values also make timeout
        // failures visible in the log (#50).
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
}
