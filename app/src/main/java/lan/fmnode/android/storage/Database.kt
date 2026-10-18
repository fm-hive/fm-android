package lan.fmnode.android.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import lan.fmnode.android.crypto.Encoding

/**
 * One network profile: its own key pair (encrypted with the app password),
 * address, node endpoint and the last accepted policy.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val title: String,
    val appUrl: String,
    val apiUrl: String,
    val nodePublicKeyB64: String,
    val address: String,
    val displayName: String,
    val isAdmin: Boolean,
    val isModerator: Boolean,
    val publicKeyB64: String,
    val encryptedSeedB64: String,
    val seedSaltB64: String,
    val seedIvB64: String,
    val seedIterations: Int,
    val policyJson: String,
    val createdAt: Long,
    val lastSyncAt: Long?,
) {
    fun encryptedSeed(): EncryptedSeed = EncryptedSeed(
        encrypted = Encoding.base64Decode(encryptedSeedB64),
        salt = Encoding.base64Decode(seedSaltB64),
        iv = Encoding.base64Decode(seedIvB64),
        iterations = seedIterations,
    )
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAt")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)
}

/** Application-level key/value state (e.g. the active profile). */
@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface AppStateDao {
    @Query("SELECT value FROM app_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: AppStateEntity)

    @Query("DELETE FROM app_state WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(entities = [ProfileEntity::class, AppStateEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun appStateDao(): AppStateDao
}
