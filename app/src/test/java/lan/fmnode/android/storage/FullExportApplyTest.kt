package lan.fmnode.android.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import lan.fmnode.android.crypto.Encoding

/**
 * Applying a full export to an empty profile database, the way the import flow
 * does it after the keys of the export have been adopted.
 *
 * The fixture keeps the traps of the real file: 160 rows but only 46 distinct
 * message ids (the same id is reused across echoes), one netmail exported twice
 * (inbox + custom folder) and one blog post without a `blogId`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullExportApplyTest {
    private lateinit var db: ProfileDatabase
    private lateinit var profile: ProfileEntity

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val full = requireNotNull(
            BackupService.parseFullBackup(FullExportFixtures.read(FullExportFixtures.FULL_EXPORT)),
        )
        profile = ProfileEntity(
            id = "p1", title = "Net", appUrl = "http://node/app", apiUrl = "http://node/api",
            nodePublicKeyB64 = full.nodePublicKeyB64.orEmpty(),
            address = full.address, displayName = full.displayName, isAdmin = false, isModerator = false,
            publicKeyB64 = full.publicKeyB64,
            encryptedSeedB64 = Encoding.base64Encode(ByteArray(48) { 2 }),
            seedSaltB64 = Encoding.base64Encode(ByteArray(16) { 3 }),
            seedIvB64 = Encoding.base64Encode(ByteArray(12) { 4 }), seedIterations = 100000,
            policyJson = "{}", createdAt = 1, lastSyncAt = null,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun full() = requireNotNull(
        BackupService.parseFullBackup(FullExportFixtures.read(FullExportFixtures.FULL_EXPORT)),
    )

    @Test
    fun `every distinct echo and message pair is stored`() = runBlocking {
        val result = BackupService.importFull(db, profile, full().payload)

        // 160 rows in the file, minus the netmail exported twice (the folder
        // copy wins) = 159 distinct rows.
        assertEquals(159, result.messages)
        assertEquals(159, db.messageDao().getAll().size)
        // The ids alone are far fewer: the table must key on echo + id.
        assertEquals(46, db.messageDao().getAll().map { it.messageId }.distinct().size)
        assertNull(result.warning)
    }

    @Test
    fun `message ids that repeat across echoes are all kept`() = runBlocking {
        BackupService.importFull(db, profile, full().payload)

        // The fixture reuses a small id space on purpose: a table keyed by
        // message_id alone loses most of these rows.
        val ids = db.messageDao().getAll().groupBy { it.messageId }
        assertTrue("expected repeated ids in the fixture", ids.any { it.value.size > 1 })
        assertEquals(159, ids.values.sumOf { it.size })

        // Both rows of a colliding id survive, each in its own echo.
        val repeated = ids.entries.first { it.value.size > 1 }
        val echoes = repeated.value.map { it.echoName }.toSet()
        assertEquals(repeated.value.size, echoes.size)
    }

    @Test
    fun `netmail lands in the inbox and in the custom folder`() = runBlocking {
        val result = BackupService.importFull(db, profile, full().payload)

        assertEquals(1, result.folders)
        assertEquals("Saved", db.netmailFolderDao().getBySlug("saved")?.name)
        val netmail = db.messageDao().getAll().filter { it.echoName == "__netmail__" }
        // 6 in the inbox, one of them exported again inside the folder.
        assertEquals(6, netmail.size)
        assertEquals(1, netmail.count { it.folderSlug == "saved" })
        assertEquals(5, netmail.count { it.folderSlug == null })
    }

    @Test
    fun `subscriptions, blogs and names are restored`() = runBlocking {
        val result = BackupService.importFull(db, profile, full().payload)

        assertEquals(12, db.echoDao().getAll().count { it.subscribed })
        assertTrue(db.echoDao().getAll().any { it.name == "admin's channel" })

        assertEquals(1, result.blogs)
        // Five posts are exported; one has no blogId and belongs to no blog.
        assertEquals(4, result.posts)
        assertEquals("мойблогр", db.blogDao().getById("blog_1788118128222_4stqgyjdv0n")?.name)

        assertEquals("Tester", db.nameDao().get(":0:1")?.name)
        assertNotNull(db.metaDao().get(BackupService.SETTINGS_META))
    }

    @Test
    fun `non-ASCII echo names survive the store prefix`() = runBlocking {
        BackupService.importFull(db, profile, full().payload)

        val names = db.messageDao().getAll().map { it.echoName }.toSet()
        assertTrue("третья группа с правилами" in names)
        assertTrue("FM доработки" in names)
        assertTrue("Домовая группа 1" in names)
        // No store prefix leaked into an echo name.
        assertTrue(names.none { it.startsWith("echo_") || it.startsWith("netmail_") })
    }

    @Test
    fun `a backup of another key is rejected`() = runBlocking {
        val other = profile.copy(publicKeyB64 = Encoding.base64Encode(ByteArray(32) { 7 }))

        val error: Throwable? = runCatching { BackupService.importFull(db, other, full().payload) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("another key"))
        assertTrue(db.messageDao().getAll().isEmpty())
    }
}
