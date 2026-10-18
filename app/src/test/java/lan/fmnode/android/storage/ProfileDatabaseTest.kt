package lan.fmnode.android.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileDatabaseTest {
    private lateinit var db: ProfileDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProfileDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun echo(name: String, subscribed: Boolean, lastRead: Long = 0) = EchoEntity(
        name = name,
        serverId = 0,
        description = "",
        rulesJson = "",
        subscribed = subscribed,
        isLocal = false,
        lastReadTimestamp = lastRead,
    )

    private fun message(id: String, echo: String, sender: String, timestamp: Long, data: String = "{}") =
        MessageEntity(
            messageId = id,
            echoName = echo,
            type = "echomail",
            senderAddress = sender,
            recipientAddress = null,
            timestamp = timestamp,
            dataJson = data,
            jws = null,
            jwe = null,
            receivedAt = 0,
        )

    @Test
    fun `unread summary counts new messages of subscribed echoes only`() = runBlocking {
        db.echoDao().upsertAll(
            listOf(
                echo("General", subscribed = true, lastRead = 100),
                echo("Tech", subscribed = true),
                echo("Off", subscribed = false),
            ),
        )
        db.messageDao().upsertAll(
            listOf(
                message("m1", "General", ":0:2", 200), // unread
                message("m2", "General", ":0:1", 300), // own message
                message("m3", "General", ":0:2", 50), // older than last read
                message("m4", "Tech", ":0:2", 1000), // unread
                message("m5", "Off", ":0:2", 1000), // not subscribed
            ),
        )

        val summary = db.messageDao().observeUnreadSummary(":0:1").first()
        assertEquals(
            mapOf("General" to 1, "Tech" to 1),
            summary.associate { it.echoName to it.count },
        )
    }

    @Test
    fun `message rows are ordered and join the sender name`() = runBlocking {
        db.nameDao().upsert(NameEntity(":0:2", "Petya", 0))
        db.messageDao().upsertAll(
            listOf(
                message("m2", "General", ":0:2", 200, """{"subject":"Later"}"""),
                message("m1", "General", ":0:2", 100, """{"subject":"Earlier"}"""),
                message("x1", "Tech", ":0:2", 50),
            ),
        )

        val rows = db.messageDao().observeEchoRows("General").first()
        assertEquals(listOf("m1", "m2"), rows.map { it.messageId })
        assertEquals(listOf("Petya", "Petya"), rows.map { it.senderName })
        assertNull(db.messageDao().getById("missing"))
    }

    @Test
    fun `search finds messages by body, sender name and address`() = runBlocking {
        db.nameDao().upsert(NameEntity(":0:2", "Petya", 0))
        db.messageDao().upsertAll(
            listOf(
                message("m1", "General", ":0:2", 100, """{"subject":"Hello","body":"world"}"""),
                message("m2", "Tech", ":0:3", 200, """{"subject":"Other","body":"nothing"}"""),
            ),
        )
        assertEquals(listOf("m1"), db.messageDao().search("world").map { it.messageId })
        assertEquals(listOf("m1"), db.messageDao().search("petya").map { it.messageId })
        assertEquals(listOf("m1"), db.messageDao().search(":0:2").map { it.messageId })
    }

    @Test
    fun `protocols query keeps protocol messages only`() = runBlocking {
        val vote = message("p1", "General", ":0:2", 100).copy(type = "vote")
        val election = message("p2", "General", ":0:2", 200).copy(type = "election")
        val echomail = message("p3", "General", ":0:2", 300)
        db.messageDao().upsertAll(listOf(vote, election, echomail))

        val protocols = db.messageDao().observeProtocols().first()
        assertEquals(listOf("p2", "p1"), protocols.map { it.messageId })
    }

    @Test
    fun `drafts round trip per echo`() = runBlocking {
        db.draftDao().upsert(DraftEntity("General", "Subj", "Body", 1))
        val draft = db.draftDao().get("General")!!
        assertEquals("Subj", draft.subject)
        assertEquals("Body", draft.body)
        db.draftDao().delete("General")
        org.junit.Assert.assertNull(db.draftDao().get("General"))
    }

    @Test
    fun `contacts are listed and removed`() = runBlocking {
        db.contactDao().upsert(ContactEntity(":0:2", "Petya", 1))
        db.contactDao().upsert(ContactEntity(":0:3", "Anna", 2))
        val contacts = db.contactDao().observeAll().first()
        assertEquals(2, contacts.size)
        db.contactDao().delete(":0:2")
        assertEquals(listOf(":0:3"), db.contactDao().observeAll().first().map { it.address })
    }

    @Test
    fun `marking an echo read resets its unread counter`() = runBlocking {
        db.echoDao().upsertAll(listOf(echo("General", subscribed = true)))
        db.messageDao().upsert(message("m1", "General", ":0:2", 200))

        assertEquals(1, db.messageDao().observeUnreadSummary(":0:1").first().first().count)
        db.echoDao().setLastRead("General", 300)
        assertEquals(0, db.messageDao().observeUnreadSummary(":0:1").first().size)
    }
}
