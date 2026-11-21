package lan.fmnode.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import lan.fmnode.android.log.AppLog
import lan.fmnode.android.log.LogCategory

/** State of the active Android network, used to detect VPN/proxy switches. */
data class NetworkSnapshot(
    val available: Boolean,
    val vpn: Boolean,
    val transports: Set<String>,
    /** HTTP proxy of the active network in "host:port" form, empty if none. */
    val proxy: String,
) {
    fun describe(): String = buildString {
        append(if (available) "available" else "lost")
        if (transports.isNotEmpty()) append(" transports=${transports.joinToString(",")}")
        if (vpn) append(" vpn=true")
        if (proxy.isNotEmpty()) append(" proxy=$proxy")
    }
}

/**
 * Watches the default network (#50). A VPN can appear/disappear while the app
 * is running; connections opened through the old network must not be reused,
 * and a refresh should start again as soon as the new network is up.
 */
class NetworkMonitor(context: Context) {
    private val manager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val _snapshots = MutableSharedFlow<NetworkSnapshot>(extraBufferCapacity = 8)
    val snapshots: SharedFlow<NetworkSnapshot> = _snapshots.asSharedFlow()

    private var lastSnapshot: NetworkSnapshot? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            publish("available", network, null, null)
        }

        override fun onLost(network: Network) {
            publish("lost", network, null, null)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            publish("capabilities", network, capabilities, null)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            publish("link", network, null, linkProperties)
        }
    }

    fun start() {
        if (registered) return
        val cm = manager ?: return
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure {
                AppLog.warn(
                    LogCategory.NETWORK,
                    "network monitor failed",
                    it.javaClass.simpleName + ": " + it.message,
                )
            }
    }

    fun stop() {
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun publish(
        event: String,
        network: Network?,
        capabilities: NetworkCapabilities?,
        linkProperties: LinkProperties?,
    ) {
        val cm = manager ?: return
        val caps = capabilities ?: network?.let { cm.getNetworkCapabilities(it) }
        val link = linkProperties ?: network?.let { cm.getLinkProperties(it) }
        val transports = buildSet {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("mobile")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
        }
        val proxyInfo = link?.httpProxy ?: cm.defaultProxy
        val proxy = proxyInfo?.takeIf { it.isValid && it.port in 1..65535 }
            ?.let { "${it.host}:${it.port}" }
            .orEmpty()
        val snapshot = NetworkSnapshot(
            available = event != "lost",
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            transports = transports,
            proxy = proxy,
        )
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        AppLog.info(LogCategory.NETWORK, "network $event", snapshot.describe())
        // Sockets bound to the previous network (or to a dead VPN tunnel) must
        // not be reused for the next send_receive.
        FmHttp.evictConnections()
        _snapshots.tryEmit(snapshot)
    }
}
