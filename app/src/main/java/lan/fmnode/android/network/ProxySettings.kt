package lan.fmnode.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lan.fmnode.android.log.AppLog
import lan.fmnode.android.log.LogCategory

/** How the app chooses the proxy for node requests. */
enum class ProxyMode { SYSTEM, DIRECT, MANUAL }

/** Proxy protocol supported by the manual proxy setting. */
enum class ProxyKind { HTTP, SOCKS }

/**
 * Global network settings of the application (#50).
 *
 * A VPN in TUN mode routes every socket transparently and needs no app-side
 * configuration. Some VPN/proxy clients (for example HAPP in proxy mode)
 * instead expose a local HTTP/SOCKS port; [ProxyMode.MANUAL] lets the user
 * point the app at it, while [ProxyMode.SYSTEM] follows the Android proxy of
 * the active network.
 */
data class ProxySettings(
    val mode: ProxyMode = ProxyMode.SYSTEM,
    val kind: ProxyKind = ProxyKind.HTTP,
    val host: String = "",
    val port: Int = 0,
) {
    val manualProxy: Proxy?
        get() {
            if (host.isBlank() || port !in 1..65535) return null
            val type = if (kind == ProxyKind.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return Proxy(type, InetSocketAddress(host.trim(), port))
        }

    /** A manual proxy is only valid with a host and a real TCP port. */
    fun hasValidManualEndpoint(): Boolean =
        host.isNotBlank() && port in 1..65535

    /** Short human-readable form for logs and the settings screen. */
    fun describe(): String = when (mode) {
        ProxyMode.SYSTEM -> "system"
        ProxyMode.DIRECT -> "direct"
        ProxyMode.MANUAL -> {
            val type = if (kind == ProxyKind.SOCKS) "socks" else "http"
            "$type ${host.trim()}:$port"
        }
    }
}

/** Resolves the proxy of the active Android network (including proxy-mode VPNs). */
fun interface ProxyResolver {
    fun resolve(): Proxy?
}

/** Reads the HTTP proxy of the active network from ConnectivityManager. */
class SystemProxy(private val context: Context) : ProxyResolver {
    private fun proxyInfo(): ProxyInfo? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            manager.activeNetwork?.let { manager.getLinkProperties(it)?.httpProxy }
                ?: manager.defaultProxy
        }.getOrNull()?.takeIf { it.isValid && !it.host.isNullOrBlank() && it.port in 1..65535 }
    }

    override fun resolve(): Proxy? {
        val info = proxyInfo() ?: return null
        // Android's system proxy is an HTTP proxy; SOCKS is only exposed by
        // the manual setting above.
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(info.host, info.port))
    }

    /**
     * Text for the settings screen, e.g. "127.0.0.1:8080" or "not detected".
     * Reads ProxyInfo directly: constructing InetSocketAddress here would
     * resolve the proxy host and can throw NetworkOnMainThreadException while
     * Settings is being composed (#50).
     */
    fun describe(): String {
        val info = proxyInfo() ?: return ""
        return "${info.host}:${info.port}"
    }
}

/**
 * Persistent global proxy settings. Stored in plain SharedPreferences: the
 * values are not secret and must be available before the vault unlocks.
 */
class NetworkPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ProxySettings> = _settings.asStateFlow()

    val current: ProxySettings
        get() = _settings.value

    private val systemProxy = SystemProxy(appContext)

    /** Saves [value]. Returns false when a manual proxy is incomplete. */
    fun save(value: ProxySettings): Boolean {
        if (value.mode == ProxyMode.MANUAL && !value.hasValidManualEndpoint()) return false
        val normalized = value.copy(
            host = value.host.trim(),
            port = if (value.port in 1..65535) value.port else 0,
        )
        prefs.edit()
            .putString(KEY_MODE, normalized.mode.name)
            .putString(KEY_KIND, normalized.kind.name)
            .putString(KEY_HOST, normalized.host)
            .putInt(KEY_PORT, normalized.port)
            .apply()
        _settings.value = normalized
        AppLog.info(LogCategory.NETWORK, "proxy configured", normalized.describe())
        return true
    }

    /** Proxy of the active network, empty when Android has none. */
    fun systemProxyLabel(): String = systemProxy.describe()

    private fun load(): ProxySettings {
        val mode = runCatching {
            ProxyMode.valueOf(prefs.getString(KEY_MODE, null) ?: ProxyMode.SYSTEM.name)
        }.getOrDefault(ProxyMode.SYSTEM)
        val kind = runCatching {
            ProxyKind.valueOf(prefs.getString(KEY_KIND, null) ?: ProxyKind.HTTP.name)
        }.getOrDefault(ProxyKind.HTTP)
        return ProxySettings(
            mode = mode,
            kind = kind,
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, 0),
        )
    }

    companion object {
        private const val PREFS = "fm_network"
        private const val KEY_MODE = "proxy_mode"
        private const val KEY_KIND = "proxy_kind"
        private const val KEY_HOST = "proxy_host"
        private const val KEY_PORT = "proxy_port"
    }
}

/**
 * OkHttp proxy selector backed by the current global settings. It reads the
 * settings for every request, so changing the proxy applies immediately.
 */
class AppProxySelector(
    private val settings: () -> ProxySettings,
    private val systemProxy: ProxyResolver,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        val value = settings()
        val proxy = when (value.mode) {
            ProxyMode.SYSTEM -> systemProxy.resolve()
            ProxyMode.DIRECT -> null
            ProxyMode.MANUAL -> value.manualProxy
        }
        return listOf(proxy ?: Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
        AppLog.warn(
            LogCategory.NETWORK,
            "proxy connection failed",
            "uri=$uri address=$sa error=${ioe?.javaClass?.simpleName}: ${ioe?.message}",
        )
    }
}
