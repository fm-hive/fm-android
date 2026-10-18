package lan.fmnode.android

import android.app.Application
import androidx.room.Room
import java.util.concurrent.ConcurrentHashMap
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.ProfileDatabases

/** Application services: the app-wide database, network profiles and session. */
class AppContainer(application: Application) {
    val database: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "fm_app.db",
    ).build()

    private val profileDatabases = ConcurrentHashMap<String, ProfileDatabase>()

    val networkManager: NetworkManager = NetworkManager(
        db = database,
        profileDatabases = { id ->
            profileDatabases.getOrPut(id) { ProfileDatabases.open(application, id) }
        },
    )

    val session: Session = Session()
}

class FmApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
