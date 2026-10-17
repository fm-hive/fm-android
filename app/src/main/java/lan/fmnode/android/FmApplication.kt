package lan.fmnode.android

import android.app.Application
import androidx.room.Room
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.AppDatabase

/** Application services: the app-wide database, network profiles and session. */
class AppContainer(application: Application) {
    val database: AppDatabase = Room.databaseBuilder(
        application,
        AppDatabase::class.java,
        "fm_app.db",
    ).build()

    val networkManager: NetworkManager = NetworkManager(database)
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
