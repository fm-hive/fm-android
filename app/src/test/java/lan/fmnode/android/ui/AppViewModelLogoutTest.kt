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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Logout must drop the session/decrypted state and require the password (#3). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelLogoutTest {
    private lateinit var db: AppDatabase
    private lateinit var vault: Vault
    private lateinit var session: Session

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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun coldStartWithExistingVaultAsksForPassword() {
        val viewModel = AppViewModel(NetworkManager(vault), session)
        assertEquals(AppScreen.Unlock, viewModel.state.value.screen)
    }

    @Test
    fun logoutConfirmationClearsSessionAndShowsUnlock() {
        val viewModel = AppViewModel(NetworkManager(vault), session)

        viewModel.requestLogout()
        assertTrue(viewModel.state.value.logoutDialog)

        viewModel.confirmLogout()
        assertFalse(viewModel.state.value.logoutDialog)
        assertFalse(viewModel.state.value.unlocked)
        assertFalse(session.isUnlocked)
        assertEquals(AppScreen.Unlock, viewModel.state.value.screen)
    }
}
