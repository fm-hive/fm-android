package lan.fmnode.android.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import lan.fmnode.android.crypto.Encoding

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupServiceTest {
    private lateinit var db: ProfileDatabase
    private lateinit var profile: ProfileEntity

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        profile = ProfileEntity(
            id = "p1", title = "Net", appUrl = "http://node/app", apiUrl = "http://node/api",
            nodePublicKeyB64 = Encoding.base64Encode(ByteArray(32) { 9 }),
            address = ":0:1", displayName = "Tester", isAdmin = false, isModerator = false,
            publicKeyB64 = Encoding.base64Encode(ByteArray(32) { 1 }),
            encryptedSeedB64 = Encoding.base64Encode(ByteArray(48) { 2 }),
            seedSaltB64 = Encoding.base64Encode(ByteArray(16) { 3 }),
            seedIvB64 = Encoding.base64Encode(ByteArray(12) { 4 }), seedIterations = 100000,
            policyJson = "{}", createdAt = 1, lastSyncAt = null,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `full backup round trips blogs netmail folders and messages`() = runBlocking {
        db.blogDao().upsert(BlogEntity("blog_1", "Notes", "Local", 10, 10))
        db.blogPostDao().upsert(BlogPostEntity("post_1", "blog_1", "Title", "Body", "[]", 11, 11))
        db.netmailFolderDao().upsert(NetmailFolderEntity("saved", "Saved", 12))
        db.messageDao().upsertAll(listOf(
            MessageEntity("m1", "General", "echomail", ":0:2", null, 20, """{"subject":"S"}""", null, null, 20),
            MessageEntity("m2", "__netmail__", "netmail", ":0:2", ":0:1", 21, """{"subject":"N"}""", null, null, 21, folderSlug = "saved"),
        ))
        val exported = BackupService.exportFull(db, profile)
        assertTrue(exported.contains("blog_1"))
        assertTrue(exported.contains("netmail_saved"))

        db.messageDao().getAll().forEach { db.messageDao().deleteByFolder(it.folderSlug ?: "") }
        // Recreate a clean target DB with the same profile key.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java).allowMainThreadQueries().build()
        val result = BackupService.importFull(target, profile, exported)
        assertEquals(2, result.messages)
        assertEquals(1, result.blogs)
        assertEquals(1, result.posts)
        assertEquals(1, result.folders)
        assertEquals("Saved", target.netmailFolderDao().getBySlug("saved")?.name)
        assertEquals("saved", target.messageDao().getById("m2")?.folderSlug)
        assertEquals("Notes", target.blogDao().getById("blog_1")?.name)
        target.close()
    }

    @Test
    fun `fm settings export has the fm-app envelope`() = runBlocking {
        db.metaDao().put(MetaEntity(BackupService.SETTINGS_META, """{"application":{"language":"ru"}}"""))
        val text = BackupService.exportSettings(db, profile)
        assertTrue(text.contains("\"type\":\"fm_settings\""))
        assertTrue(text.contains("\"version\":\"1.0\""))
        assertTrue(text.contains(profile.nodePublicKeyB64))
    }

    @Test
    fun `full export is self sufficient and matches the fm-app shape`() = runBlocking {
        db.echoDao().upsertAll(listOf(EchoEntity("General", 7, "", "", true, false, 0)))
        db.messageDao().upsertAll(listOf(
            MessageEntity("m1", "General", "echomail", ":0:2", null, 20, """{"subject":"S"}""", null, null, 20),
        ))

        val text = BackupService.exportFull(db, profile)
        val root = Json.parseToJsonElement(text).jsonObject

        assertEquals("http://node/app", root["appUrl"]!!.jsonPrimitive.content)
        assertTrue("settings must be present for fmclient", root["settings"] != null)
        assertEquals(
            Encoding.toHex(Encoding.base64Decode(profile.publicKeyB64)),
            root["user"]!!.jsonObject["publicKey"]!!.jsonPrimitive.content,
        )
        val message = root["messages"]!!.jsonObject["echo_General"]!!.jsonArray[0].jsonObject
        assertEquals("General", message["echo_name"]!!.jsonPrimitive.content)
        assertEquals(7, message["echo_id"]!!.jsonPrimitive.int)
        // A fresh install derives the endpoint from appUrl.
        assertEquals("http://node/app", BackupService.parseFullBackup(text)!!.nodeUrl)
    }

    @Test
    fun `default emojis are omitted from both exports`() = runBlocking {
        val defaults = EmojiDefaults.array(EmojiDefaults.LIST)
        db.metaDao().put(
            MetaEntity(BackupService.SETTINGS_META, """{"ui":{"customEmojis":$defaults}}"""),
        )

        val full = Json.parseToJsonElement(BackupService.exportFull(db, profile)).jsonObject
        assertFalse(full["settings"]!!.jsonObject["ui"]!!.jsonObject.containsKey("customEmojis"))
        assertFalse(full.containsKey("emojis"))

        val settings = Json.parseToJsonElement(BackupService.exportSettings(db, profile)).jsonObject
        assertFalse(settings["data"]!!.jsonObject["ui"]!!.jsonObject.containsKey("customEmojis"))
    }

    @Test
    fun `a changed emoji list travels in the top level emojis array`() = runBlocking {
        db.metaDao().put(
            MetaEntity(BackupService.SETTINGS_META, """{"ui":{"customEmojis":["🦄"]}}"""),
        )

        val full = Json.parseToJsonElement(BackupService.exportFull(db, profile)).jsonObject
        assertFalse(full["settings"]!!.jsonObject["ui"]!!.jsonObject.containsKey("customEmojis"))
        assertEquals("🦄", full["emojis"]!!.jsonArray[0].jsonPrimitive.content)

        // Importing the export puts the custom list back into the settings.
        val target = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ProfileDatabase::class.java,
        ).allowMainThreadQueries().build()
        BackupService.importFull(target, profile, full)
        val stored = Json.parseToJsonElement(target.metaDao().get(BackupService.SETTINGS_META)!!).jsonObject
        assertEquals("🦄", stored["ui"]!!.jsonObject["customEmojis"]!!.jsonArray[0].jsonPrimitive.content)
        target.close()
    }
}
