package lan.fmnode.android.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.Vault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Requirement #8: navigation entries must walk the in-app screen history
 * instead of leaving the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppBackNavigationTest {
    private lateinit var db: AppDatabase
    private lateinit var vault: Vault
    private lateinit var session: Session
    private lateinit var viewModel: AppViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = Vault(
            context,
            appDbFactory = { _, _ -> db },
            profileDbFactory = { _, _, _ -> error("profile database is not used in this test") },
        ).apply {
            attachForTests(db)
            setExistsForTests(true)
        }
        session = Session().apply { setPassword("secret") }
        viewModel = AppViewModel(NetworkManager(vault), session)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun openingMessageShowsItsOwnScreenAndBackReturns() {
        val message = sampleMessage()
        viewModel.openMessage(message)

        assertEquals(AppScreen.MessageDetail, viewModel.state.value.screen)
        assertEquals(message.messageId, viewModel.state.value.selectedMessage?.messageId)

        viewModel.backFromMessage()

        assertEquals(AppScreen.Echoes, viewModel.state.value.screen)
        assertNull(viewModel.state.value.selectedMessage)
    }

    @Test
    fun messageViewToggleSwitchesBetweenAllAndAttachments() {
        assertEquals(EchoMessageView.All, viewModel.state.value.messageView)

        viewModel.setMessageView(EchoMessageView.Attachments)
        assertEquals(EchoMessageView.Attachments, viewModel.state.value.messageView)

        viewModel.setMessageView(EchoMessageView.All)
        assertEquals(EchoMessageView.All, viewModel.state.value.messageView)
    }

    @Test
    fun backFromEchoClearsConversationState() {
        viewModel.backFromEcho()

        assertEquals(AppScreen.Echoes, viewModel.state.value.screen)
        assertNull(viewModel.state.value.selectedEcho)
        assertNull(viewModel.state.value.selectedMessage)
    }

    private fun sampleMessage() = MessageUi(
        messageId = "m1",
        senderName = "Alice",
        senderAddress = ":0:1",
        recipientAddress = null,
        timestamp = 1700000000L,
        subject = "Subject",
        body = "Body",
        own = false,
        folderSlug = null,
        attachments = listOf(AttachmentUi("cat.png", "image/png", 1024, "data:image/png;base64,AA==")),
    )
}
