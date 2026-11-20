package lan.fmnode.android.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLogTest {
    @Test
    fun `writes exports and clears log entries`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppLog.resetForTests()
        AppLog.init(context)

        AppLog.logSync(LogCategory.NETWORK, LogLevel.INFO, "→ identity", "request=10B")
        AppLog.logSync(LogCategory.ERROR, LogLevel.ERROR, "boom", "stack trace")

        assertEquals(2, AppLog.entries.value.size)
        val text = AppLog.exportText()
        assertTrue(text.contains("identity"))
        assertTrue(text.contains("boom"))
        assertTrue(text.contains("NETWORK"))
        assertTrue(text.contains("ERROR"))
        assertTrue(File(context.filesDir, "logs/fm.log").exists())

        AppLog.resetForTests()
        assertTrue(AppLog.entries.value.isEmpty())
        assertTrue(!File(context.filesDir, "logs/fm.log").exists())
    }

    @Test
    fun `rotates a log larger than one megabyte`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppLog.resetForTests()
        AppLog.init(context)

        repeat(5) {
            AppLog.logSync(LogCategory.APP, LogLevel.DEBUG, "large", "x".repeat(300_000))
        }

        assertTrue(File(context.filesDir, "logs/fm.log.1").exists())
    }

    @Test
    fun `keeps the newest entries in the in-memory ring`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppLog.resetForTests()
        AppLog.init(context)
        repeat(520) { index ->
            AppLog.logSync(LogCategory.APP, LogLevel.DEBUG, "entry-$index")
        }
        assertEquals(500, AppLog.entries.value.size)
        assertTrue(AppLog.entries.value.first().message == "entry-20")
        assertTrue(AppLog.entries.value.last().message == "entry-519")
    }
}
