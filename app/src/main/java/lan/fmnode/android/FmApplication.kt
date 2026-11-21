package lan.fmnode.android

import android.app.Application
import lan.fmnode.android.log.AppLog
import lan.fmnode.android.log.LogCategory
import lan.fmnode.android.network.AppProxySelector
import lan.fmnode.android.network.FmHttp
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.network.NetworkMonitor
import lan.fmnode.android.network.NetworkPreferences
import lan.fmnode.android.network.SystemProxy
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.KeystorePasswordStore
import lan.fmnode.android.storage.PasswordStore
import lan.fmnode.android.storage.Vault

/**
 * Application services: the encrypted vault (#3), network profiles and the
 * in-memory session. No database is opened here: the vault requires the app
 * password, so databases are opened on unlock.
 */
class AppContainer(application: Application) {
    val vault: Vault = Vault(application).also {
        // Databases from older versions were plaintext; they are wiped once.
        it.wipeLegacyDatabases()
    }

    val networkManager: NetworkManager = NetworkManager(vault)

    val session: Session = Session()

    /** "Save password" storage backed by the Android Keystore (#22). */
    val passwordStore: PasswordStore = KeystorePasswordStore(application)

    /** Global proxy settings and live network changes (#50). */
    val networkPreferences: NetworkPreferences = NetworkPreferences(application)
    val networkMonitor: NetworkMonitor = NetworkMonitor(application)

    init {
        FmHttp.installProxySelector(
            AppProxySelector(
                settings = { networkPreferences.current },
                systemProxy = SystemProxy(application),
            ),
        )
        AppLog.info(
            LogCategory.NETWORK,
            "proxy mode",
            networkPreferences.current.describe() +
                " system=" + networkPreferences.systemProxyLabel().ifEmpty { "none" },
        )
        networkMonitor.start()
    }
}

class FmApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Logs are available before the vault is unlocked (policy/network).
        AppLog.init(this)
        container = AppContainer(this)
    }
}
