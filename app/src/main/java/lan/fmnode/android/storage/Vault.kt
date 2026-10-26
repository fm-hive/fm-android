package lan.fmnode.android.storage

import android.content.Context
import androidx.room.Room
import java.util.concurrent.ConcurrentHashMap
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Password-protected local vault (#3).
 *
 * Both the application database (profiles, network metadata) and every
 * per-network message database are SQLCipher-encrypted with the app password.
 * The password is only held in memory while the vault is open; [close] releases
 * the databases and zeroes it, so the next entry requires the password again.
 */
class Vault(
    private val context: Context,
    private val appDbFactory: (Context, ByteArray) -> AppDatabase = ::openEncryptedAppDb,
    private val profileDbFactory: (Context, String, ByteArray) -> ProfileDatabase = ::openEncryptedProfileDb,
) {
    private var db: AppDatabase? = null
    private var passphrase: ByteArray? = null
    private val profileDbs = ConcurrentHashMap<String, ProfileDatabase>()

    /** Whether a vault has been created on this device. */
    fun exists(): Boolean = prefs().getBoolean(KEY_EXISTS, false)

    fun appDatabaseOrNull(): AppDatabase? = db

    /** Attaches an already-open database for tests (no SQLCipher involved). */
    fun attachForTests(database: AppDatabase) {
        db = database
        passphrase = ByteArray(0)
    }

    /** Marks the vault as existing for lifecycle tests. */
    fun setExistsForTests(value: Boolean) {
        prefs().edit().putBoolean(KEY_EXISTS, value).apply()
    }

    fun appDatabase(): AppDatabase = db ?: error("vault is locked")

    /** Opens the encrypted application database; false for a wrong password. */
    @Synchronized
    fun open(password: String): Boolean {
        val bytes = password.toByteArray(Charsets.UTF_8)
        db?.let { return passphrase?.contentEquals(bytes) == true }
        if (!exists()) return false
        if (!context.getDatabasePath(APP_DB_NAME).exists()) {
            bytes.fill(0)
            return false
        }
        val candidate = try {
            appDbFactory(context, bytes)
        } catch (_: Exception) {
            return false
        }
        return try {
            // Force SQLCipher to key and open the file: a wrong password throws.
            candidate.query("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            db = candidate
            passphrase = bytes
            true
        } catch (_: Exception) {
            runCatching { candidate.close() }
            bytes.fill(0)
            false
        }
    }

    /** Creates the encrypted application database with a new password. */
    @Synchronized
    fun create(password: String): AppDatabase {
        check(db == null) { "vault is already open" }
        val bytes = password.toByteArray(Charsets.UTF_8)
        val candidate = appDbFactory(context, bytes)
        candidate.query("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        db = candidate
        passphrase = bytes
        prefs().edit().putBoolean(KEY_EXISTS, true).apply()
        return candidate
    }

    /** Opens an existing vault or creates it when this is the first network. */
    @Synchronized
    fun openOrCreate(password: String): AppDatabase {
        db?.let { return it }
        if (exists()) {
            check(open(password)) { "wrong password" }
            return appDatabase()
        }
        return create(password)
    }

    @Synchronized
    fun profileDatabase(profileId: String): ProfileDatabase {
        val key = passphrase ?: error("vault is locked")
        return profileDbs.getOrPut(profileId) {
            profileDbFactory(context, profileId, key)
        }
    }

    /** Deletes the encrypted database of a removed network profile. */
    @Synchronized
    fun deleteProfileDatabase(profileId: String) {
        profileDbs.remove(profileId)?.let { runCatching { it.close() } }
        context.deleteDatabase(profileDbName(profileId))
    }

    /** Closes all databases and forgets the password (lock/logout). */
    @Synchronized
    fun close() {
        profileDbs.values.forEach { runCatching { it.close() } }
        profileDbs.clear()
        runCatching { db?.close() }
        db = null
        passphrase?.fill(0)
        passphrase = null
    }

    /** Deletes the vault entirely (the last network was removed). */
    @Synchronized
    fun clear() {
        close()
        context.deleteDatabase(APP_DB_NAME)
        prefs().edit().putBoolean(KEY_EXISTS, false).apply()
    }

    /**
     * One-time removal of databases created by older app versions: they were
     * plaintext (or encrypted with an old scheme) and are not migrated (#3).
     */
    fun wipeLegacyDatabases() {
        val prefs = prefs()
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return
        for (name in context.databaseList()) {
            if (name == APP_DB_NAME || name.startsWith("$APP_DB_NAME-") ||
                name.startsWith(PROFILE_DB_PREFIX)
            ) {
                context.deleteDatabase(name)
            }
        }
        prefs.edit()
            .putInt(KEY_SCHEMA, SCHEMA_VERSION)
            .putBoolean(KEY_EXISTS, false)
            .apply()
    }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        const val APP_DB_NAME = "fm_app.db"
        const val PROFILE_DB_PREFIX = "fm_profile_"
        private const val PREFS = "fm_vault"
        private const val KEY_EXISTS = "exists"
        private const val KEY_SCHEMA = "schema"
        private const val SCHEMA_VERSION = 2

        @Volatile
        private var sqlcipherLoaded = false

        private fun loadSqlCipher() {
            if (sqlcipherLoaded) return
            synchronized(this) {
                if (!sqlcipherLoaded) {
                    System.loadLibrary("sqlcipher")
                    sqlcipherLoaded = true
                }
            }
        }

        fun profileDbName(profileId: String) = "$PROFILE_DB_PREFIX$profileId.db"

        private fun openEncryptedAppDb(context: Context, password: ByteArray): AppDatabase {
            loadSqlCipher()
            return Room.databaseBuilder(context, AppDatabase::class.java, APP_DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(password))
                .build()
        }

        private fun openEncryptedProfileDb(
            context: Context,
            profileId: String,
            password: ByteArray,
        ): ProfileDatabase {
            loadSqlCipher()
            return Room.databaseBuilder(context, ProfileDatabase::class.java, profileDbName(profileId))
                .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
                .openHelperFactory(SupportOpenHelperFactory(password))
                .build()
        }
    }
}
