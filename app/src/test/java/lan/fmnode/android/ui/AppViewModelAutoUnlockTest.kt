package lan.fmnode.android.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import lan.fmnode.android.network.NetworkManager
import lan.fmnode.android.session.Session
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.PasswordStore
import lan.fmnode.android.storage.Vault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Auto-unlock with the saved password (#45). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppViewModelAutoUnlockTest {
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
        session = Session()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun settled(
        viewModel: AppViewModel,
        condition: (UiState) -> Boolean = { true },
    ): UiState = withTimeout(5_000) {
        viewModel.state.first { !it.autoUnlocking && condition(it) }
    }

    @Test
    fun `a valid saved password unlocks and keeps remember checked`() = runBlocking {
        val store = MemoryPasswordStore(saved = "")
        val viewModel = AppViewModel(NetworkManager(vault), session, store)

        val state = settled(viewModel) { it.unlocked }
        assertTrue(state.unlocked)
        assertTrue(state.rememberPassword)
        assertNull(state.unlockError)
        assertEquals("", store.saved)
    }

    @Test
    fun `a stale saved password is cleared and the checkbox is unchecked`() = runBlocking {
        val store = MemoryPasswordStore(saved = "stale")
        val viewModel = AppViewModel(NetworkManager(vault), session, store)

        val state = settled(viewModel) { it.unlockError != null }
        assertFalse(state.unlocked)
        assertFalse(state.rememberPassword)
        assertEquals(UiError.WrongPassword, state.unlockError)
        assertEquals(AppScreen.Unlock, state.screen)
        // The wrong password must not be tried again on the next cold start.
        assertNull(store.saved)
    }

    @Test
    fun `without a saved password the form is shown without an error`() = runBlocking {
        val viewModel = AppViewModel(NetworkManager(vault), session, MemoryPasswordStore())

        val state = settled(viewModel)
        assertFalse(state.autoUnlocking)
        assertFalse(state.rememberPassword)
        assertNull(state.unlockError)
        assertEquals(AppScreen.Unlock, state.screen)
    }

    private class MemoryPasswordStore(var saved: String? = null) : PasswordStore {
        override fun load(): String? = saved
        override fun save(password: String) {
            saved = password
        }

        override fun clear() {
            saved = null
        }
    }
}
