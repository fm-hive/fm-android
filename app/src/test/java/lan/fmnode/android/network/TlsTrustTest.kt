package lan.fmnode.android.network

import java.io.IOException
import java.security.Provider
import java.security.Security
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Regression test for #4: FM nodes use self-signed certificates, so the
 * default client must not validate the chain or the hostname.
 */
class TlsTrustTest {
    private lateinit var server: MockWebServer
    private var conscrypt: Provider? = null

    @Before
    fun setUp() {
        // Robolectric installs the Android Conscrypt provider globally in the
        // test JVM; its EC support fails on this JDK. The JDK's SunJSSE is
        // enough for this JVM-only TLS test.
        conscrypt = Security.getProvider("Conscrypt")
        if (conscrypt != null) Security.removeProvider("Conscrypt")
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        server = MockWebServer()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.enqueue(
            MockResponse().setBody("""{"data":{"request":"test","data":{"ok":true}}}"""),
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        conscrypt?.let { Security.addProvider(it) }
    }

    @Test
    fun defaultClientAcceptsSelfSignedCertificate() {
        val api = FmApiClient(server.url("/api").toString())
        val result = kotlinx.coroutines.runBlocking { api.post("test") }
        check(result.toString().contains("ok"))
    }

    @Test
    fun plainOkHttpClientRejectsTheSameCertificate() {
        val request = Request.Builder()
            .url(server.url("/api"))
            .post("""{"type":"control","data":{"request":"test"}}""".toRequestBody("application/json".toMediaType()))
            .build()
        try {
            OkHttpClient().newCall(request).execute().use { }
            fail("a verifying client must reject the self-signed certificate")
        } catch (_: IOException) {
            // Expected: the test reproduces the original CertPathValidatorException.
        }
    }
}
