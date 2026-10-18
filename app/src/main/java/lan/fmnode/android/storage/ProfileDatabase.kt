package lan.fmnode.android.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Echo conference as reported by the node (subscription state included). */
@Entity(tableName = "echoes")
data class EchoEntity(
    @PrimaryKey val name: String,
    val serverId: Int?,
    val description: String,
    val rulesJson: String,
    val subscribed: Boolean,
    val isLocal: Boolean,
    val lastReadTimestamp: Long,
)

/**
 * Received message. The content lives in [dataJson] (echomail/netmail data,
 * recovered from the JWS payload or decrypted from the JWE); netmail keeps the
 * encrypted [jwe] as delivered by the node.
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val echoName: String,
    val type: String,
    val senderAddress: String,
    val recipientAddress: String?,
    val timestamp: Long,
    val dataJson: String,
    val jws: String?,
    val jwe: String?,
    val receivedAt: Long,
    val versionsJson: String? = null,
)

/** Public key cache of other network participants. */
@Entity(tableName = "pubkey_cache")
data class PubkeyEntity(
    @PrimaryKey val address: String,
    val signPublicKeyB64: String,
    val thumbprint: String,
    val updatedAt: Long,
)

/** Queued outgoing message (raw data, signed at send time). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val echoName: String?,
    val recipientAddress: String?,
    val dataJson: String,
    val createdAt: Long,
    val attempts: Int,
    val lastError: String?,
)

/** Address -> display name directory built from message metadata. */
@Entity(tableName = "names")
data class NameEntity(
    @PrimaryKey val address: String,
    val name: String,
    val updatedAt: Long,
)

/** Unsent composer draft of one conference. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val echoName: String,
    val subject: String,
    val body: String,
    val updatedAt: Long,
)

/** Address book entry for netmail recipients. */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val address: String,
    val name: String,
    val updatedAt: Long,
)

/** Per-profile key/value state (sync cursors, protocol settings, ...). */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Echo with the sender's cached display name (for message lists). */
data class MessageRow(
    val messageId: String,
    val echoName: String,
    val type: String,
    val senderAddress: String,
    val recipientAddress: String?,
    val timestamp: Long,
    val dataJson: String,
    val jws: String?,
    val jwe: String?,
    val versionsJson: String?,
    val senderName: String?,
)

/** Unread counter of one echo. */
data class EchoUnread(
    val echoName: String,
    val count: Int,
)

@Dao
interface EchoDao {
    @Query("SELECT * FROM echoes ORDER BY name")
    fun observeAll(): Flow<List<EchoEntity>>

    @Query("SELECT * FROM echoes WHERE subscribed = 1 ORDER BY name")
    fun observeSubscribed(): Flow<List<EchoEntity>>

    @Query("SELECT * FROM echoes ORDER BY name")
    suspend fun getAll(): List<EchoEntity>

    @Query("SELECT * FROM echoes WHERE name = :name")
    suspend fun getByName(name: String): EchoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(echoes: List<EchoEntity>)

    @Query("UPDATE echoes SET subscribed = :subscribed WHERE name = :name")
    suspend fun setSubscribed(name: String, subscribed: Boolean)

    @Query("UPDATE echoes SET lastReadTimestamp = :timestamp WHERE name = :name")
    suspend fun setLastRead(name: String, timestamp: Long)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE echoName = :echo ORDER BY timestamp ASC, messageId ASC")
    fun observeEcho(echo: String): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.messageId AS messageId, m.echoName AS echoName, m.type AS type,
               m.senderAddress AS senderAddress, m.recipientAddress AS recipientAddress,
               m.timestamp AS timestamp, m.dataJson AS dataJson, m.jws AS jws, m.jwe AS jwe,
               m.versionsJson AS versionsJson, n.name AS senderName
        FROM messages m
        LEFT JOIN names n ON n.address = m.senderAddress
        WHERE m.echoName = :echo
        ORDER BY m.timestamp ASC, m.messageId ASC
        """,
    )
    fun observeEchoRows(echo: String): Flow<List<MessageRow>>

    @Query(
        """
        SELECT m.messageId AS messageId, m.echoName AS echoName, m.type AS type,
               m.senderAddress AS senderAddress, m.recipientAddress AS recipientAddress,
               m.timestamp AS timestamp, m.dataJson AS dataJson, m.jws AS jws, m.jwe AS jwe,
               m.versionsJson AS versionsJson, n.name AS senderName
        FROM messages m
        LEFT JOIN names n ON n.address = m.senderAddress
        WHERE LOWER(m.dataJson) LIKE '%' || LOWER(:query) || '%'
           OR LOWER(COALESCE(n.name, '')) LIKE '%' || LOWER(:query) || '%'
           OR LOWER(m.senderAddress) LIKE '%' || LOWER(:query) || '%'
        ORDER BY m.timestamp DESC
        LIMIT 300
        """,
    )
    suspend fun search(query: String): List<MessageRow>

    @Query(
        """
        SELECT m.messageId AS messageId, m.echoName AS echoName, m.type AS type,
               m.senderAddress AS senderAddress, m.recipientAddress AS recipientAddress,
               m.timestamp AS timestamp, m.dataJson AS dataJson, m.jws AS jws, m.jwe AS jwe,
               m.versionsJson AS versionsJson, n.name AS senderName
        FROM messages m
        LEFT JOIN names n ON n.address = m.senderAddress
        WHERE m.type IN ('vote', 'election', 'excommunicate', 'change_policy',
                         'remove_echo', 'refill', 'merge', 'conflict', 'recover', 'appeal')
        ORDER BY m.timestamp DESC
        LIMIT 300
        """,
    )
    fun observeProtocols(): Flow<List<MessageRow>>

    @Query(
        """
        SELECT m.echoName AS echoName, COUNT(*) AS count
        FROM messages m
        JOIN echoes e ON e.name = m.echoName
        WHERE e.subscribed = 1 AND m.timestamp > e.lastReadTimestamp AND m.senderAddress != :me
        GROUP BY m.echoName
        """,
    )
    fun observeUnreadSummary(me: String): Flow<List<EchoUnread>>

    @Query("SELECT * FROM messages WHERE echoName = :echo ORDER BY timestamp ASC, messageId ASC")
    suspend fun getEcho(echo: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun getById(messageId: String): MessageEntity?

    @Query(
        "SELECT COUNT(*) FROM messages WHERE echoName = :echo AND timestamp > :since " +
            "AND senderAddress != :me",
    )
    fun observeUnread(echo: String, since: Long, me: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE echoName = '__netmail__' AND timestamp > :since AND senderAddress != :me")
    fun observeUnreadNetmail(since: Long, me: String): Flow<Int>

    @Query("UPDATE messages SET dataJson = :dataJson, versionsJson = :versionsJson, receivedAt = :receivedAt WHERE messageId = :messageId")
    suspend fun updateContent(messageId: String, dataJson: String, versionsJson: String?, receivedAt: Long)
}

@Dao
interface PubkeyDao {
    @Query("SELECT * FROM pubkey_cache WHERE address = :address")
    suspend fun get(address: String): PubkeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pubkey: PubkeyEntity)
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntity): Long

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY id")
    fun observeAll(): Flow<List<OutboxEntity>>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE outbox SET attempts = :attempts, lastError = :error WHERE id = :id")
    suspend fun updateAttempt(id: Long, attempts: Int, error: String?)
}

@Dao
interface NameDao {
    @Query("SELECT * FROM names WHERE address = :address")
    suspend fun get(address: String): NameEntity?

    @Query("SELECT * FROM names")
    suspend fun getAll(): List<NameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(name: NameEntity)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE echoName = :echoName")
    suspend fun get(echoName: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE echoName = :echoName")
    suspend fun delete(echoName: String)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name, address")
    fun observeAll(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE address = :address")
    suspend fun delete(address: String)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: MetaEntity)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [
        EchoEntity::class,
        MessageEntity::class,
        PubkeyEntity::class,
        OutboxEntity::class,
        NameEntity::class,
        MetaEntity::class,
        DraftEntity::class,
        ContactEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ProfileDatabase : RoomDatabase() {
    abstract fun echoDao(): EchoDao
    abstract fun messageDao(): MessageDao
    abstract fun pubkeyDao(): PubkeyDao
    abstract fun outboxDao(): OutboxDao
    abstract fun nameDao(): NameDao
    abstract fun metaDao(): MetaDao
    abstract fun draftDao(): DraftDao
    abstract fun contactDao(): ContactDao

    companion object {
        /** Adds drafts, contacts and the message edit history (v1 -> v2). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS drafts (" +
                        "echoName TEXT NOT NULL PRIMARY KEY, subject TEXT NOT NULL, " +
                        "body TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS contacts (" +
                        "address TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)",
                )
                db.execSQL("ALTER TABLE messages ADD COLUMN versionsJson TEXT")
            }
        }
    }
}

object ProfileDatabases {
    /** Opens (or creates) the message database of one network profile. */
    fun open(context: Context, profileId: String): ProfileDatabase =
        Room.databaseBuilder(context, ProfileDatabase::class.java, "fm_profile_$profileId.db")
            .addMigrations(ProfileDatabase.MIGRATION_1_2)
            .build()
}
