package lan.fmnode.android.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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
 *
 * A `message_id` is only unique inside its echo: the same id is reused by
 * different conferences (an import of a real export lost 101 of 907 messages to
 * a message_id-only key), so the key is the echo/message pair.
 */
@Entity(tableName = "messages", primaryKeys = ["echoName", "messageId"])
data class MessageEntity(
    val messageId: String,
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
    val folderSlug: String? = null,
)

/** A local blog: stored only in this network profile, never sent to the node. */
@Entity(tableName = "blogs")
data class BlogEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A local blog post. [timestamp] is milliseconds, matching fm-app. */
@Entity(tableName = "blog_posts", indices = [Index("blogId")])
data class BlogPostEntity(
    @PrimaryKey val id: String,
    val blogId: String,
    val title: String,
    val body: String,
    val attachmentsJson: String,
    val timestamp: Long,
    val updatedAt: Long,
)

/** A user-created netmail folder. Messages point at it by [MessageEntity.folderSlug]. */
@Entity(tableName = "netmail_folders")
data class NetmailFolderEntity(
    @PrimaryKey val slug: String,
    val name: String,
    val createdAt: Long,
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
    val folderSlug: String?,
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
               m.versionsJson AS versionsJson, m.folderSlug AS folderSlug, n.name AS senderName
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
               m.versionsJson AS versionsJson, m.folderSlug AS folderSlug, n.name AS senderName
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
               m.versionsJson AS versionsJson, m.folderSlug AS folderSlug, n.name AS senderName
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

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): MessageEntity?

    /** Exact lookup: a message id is only unique inside its echo. */
    @Query("SELECT * FROM messages WHERE echoName = :echo AND messageId = :messageId")
    suspend fun getByEchoAndId(echo: String, messageId: String): MessageEntity?

    @Query(
        "SELECT COUNT(*) FROM messages WHERE echoName = :echo AND timestamp > :since " +
            "AND senderAddress != :me",
    )
    fun observeUnread(echo: String, since: Long, me: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE echoName = '__netmail__' AND timestamp > :since AND senderAddress != :me")
    fun observeUnreadNetmail(since: Long, me: String): Flow<Int>

    @Query(
        "UPDATE messages SET dataJson = :dataJson, versionsJson = :versionsJson, " +
            "receivedAt = :receivedAt WHERE echoName = :echo AND messageId = :messageId",
    )
    suspend fun updateContent(
        echo: String,
        messageId: String,
        dataJson: String,
        versionsJson: String?,
        receivedAt: Long,
    )

    @Query("SELECT * FROM messages ORDER BY timestamp ASC, messageId ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("UPDATE messages SET folderSlug = :folderSlug WHERE echoName = :echo AND messageId = :messageId")
    suspend fun setFolder(echo: String, messageId: String, folderSlug: String?)

    /** Removes one row of a given echo (used when a message is deleted upstream). */
    @Query("DELETE FROM messages WHERE echoName = :echo AND messageId = :messageId")
    suspend fun delete(echo: String, messageId: String)

    @Query("DELETE FROM messages WHERE folderSlug = :folderSlug")
    suspend fun deleteByFolder(folderSlug: String)
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
interface BlogDao {
    @Query("SELECT * FROM blogs ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<BlogEntity>>

    @Query("SELECT * FROM blogs ORDER BY createdAt ASC")
    suspend fun getAll(): List<BlogEntity>

    @Query("SELECT * FROM blogs WHERE id = :id")
    suspend fun getById(id: String): BlogEntity?

    @Query("SELECT * FROM blogs WHERE LOWER(name) = LOWER(:name)")
    suspend fun getByName(name: String): BlogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blog: BlogEntity)

    @Query("DELETE FROM blogs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BlogPostDao {
    @Query("SELECT * FROM blog_posts WHERE blogId = :blogId ORDER BY timestamp DESC, id DESC")
    fun observeByBlog(blogId: String): Flow<List<BlogPostEntity>>

    @Query("SELECT * FROM blog_posts WHERE blogId = :blogId ORDER BY timestamp DESC, id DESC")
    suspend fun getByBlog(blogId: String): List<BlogPostEntity>

    @Query("SELECT * FROM blog_posts ORDER BY timestamp ASC")
    suspend fun getAll(): List<BlogPostEntity>

    @Query("SELECT * FROM blog_posts WHERE id = :id")
    suspend fun getById(id: String): BlogPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(post: BlogPostEntity)

    @Query("DELETE FROM blog_posts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM blog_posts WHERE blogId = :blogId")
    suspend fun deleteByBlog(blogId: String)
}

@Dao
interface NetmailFolderDao {
    @Query("SELECT * FROM netmail_folders ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<NetmailFolderEntity>>

    @Query("SELECT * FROM netmail_folders ORDER BY createdAt ASC")
    suspend fun getAll(): List<NetmailFolderEntity>

    @Query("SELECT * FROM netmail_folders WHERE slug = :slug")
    suspend fun getBySlug(slug: String): NetmailFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: NetmailFolderEntity)

    @Query("DELETE FROM netmail_folders WHERE slug = :slug")
    suspend fun delete(slug: String)
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
        BlogEntity::class,
        BlogPostEntity::class,
        NetmailFolderEntity::class,
    ],
    version = 4,
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
    abstract fun blogDao(): BlogDao
    abstract fun blogPostDao(): BlogPostDao
    abstract fun netmailFolderDao(): NetmailFolderDao

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

        /** Local blogs and custom netmail folders (#actualization). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN folderSlug TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS netmail_folders (" +
                        "slug TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS blogs (" +
                        "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS blog_posts (" +
                        "id TEXT NOT NULL PRIMARY KEY, blogId TEXT NOT NULL, title TEXT NOT NULL, " +
                        "body TEXT NOT NULL, attachmentsJson TEXT NOT NULL, timestamp INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_blog_posts_blogId ON blog_posts(blogId)")
            }
        }

        /**
         * Messages are keyed by echo + id (v3 -> v4): a `message_id` is only
         * unique inside its conference, and a single-key table silently dropped
         * every colliding message of an import.
         *
         * SQLite cannot change a primary key in place, so the table is rebuilt.
         * Existing rows keep the one id they had; the old key made collisions
         * impossible anyway.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS messages_new (" +
                        "messageId TEXT NOT NULL, echoName TEXT NOT NULL, type TEXT NOT NULL, " +
                        "senderAddress TEXT NOT NULL, recipientAddress TEXT, timestamp INTEGER NOT NULL, " +
                        "dataJson TEXT NOT NULL, jws TEXT, jwe TEXT, receivedAt INTEGER NOT NULL, " +
                        "versionsJson TEXT, folderSlug TEXT, PRIMARY KEY(echoName, messageId))",
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO messages_new (messageId, echoName, type, senderAddress, " +
                        "recipientAddress, timestamp, dataJson, jws, jwe, receivedAt, versionsJson, folderSlug) " +
                        "SELECT messageId, echoName, type, senderAddress, recipientAddress, timestamp, " +
                        "dataJson, jws, jwe, receivedAt, versionsJson, folderSlug FROM messages",
                )
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
            }
        }
    }
}


