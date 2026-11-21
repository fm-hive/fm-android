package lan.fmnode.android.log

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LogCategory { NETWORK, APP, ERROR }

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEntry(
    val timestamp: Long,
    val category: LogCategory,
    val level: LogLevel,
    val message: String,
    val detail: String = "",
)

/**
 * Application log: a bounded in-memory list for the UI and an append-only file
 * that survives restarts. Network requests, sync outcomes, protocol events and
 * crashes are written here so a failed refresh can be diagnosed on the device
 * (#50).
 *
 * Message bodies and JWE payloads are never logged: only request names, sizes,
 * statuses and ids.
 */
object AppLog {
    private const val MAX_ENTRIES = 500
    private const val MAX_FILE_BYTES = 1_000_000L
    private const val FILE_NAME = "fm.log"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private var file: File? = null
    private var installed = false

    @Synchronized
    fun init(context: Context) {
        if (file == null) {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            file = File(dir, FILE_NAME)
            runCatching {
                val parsed = file!!.takeIf { it.exists() }?.readLines()
                    ?.takeLast(MAX_ENTRIES)
                    ?.mapNotNull(::parseLine)
                    ?: emptyList()
                _entries.value = parsed
            }
        }
        if (!installed) {
            installed = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                logSync(
                    LogCategory.ERROR,
                    LogLevel.ERROR,
                    "Uncaught exception on ${thread.name}",
                    error.stackTraceToString(),
                )
                previous?.uncaughtException(thread, error)
            }
        }
    }

    fun debug(category: LogCategory, message: String, detail: String = "") =
        log(category, LogLevel.DEBUG, message, detail)

    fun info(category: LogCategory, message: String, detail: String = "") =
        log(category, LogLevel.INFO, message, detail)

    fun warn(category: LogCategory, message: String, detail: String = "") =
        log(category, LogLevel.WARN, message, detail)

    fun error(category: LogCategory, message: String, detail: String = "") =
        log(category, LogLevel.ERROR, message, detail)

    fun log(category: LogCategory, level: LogLevel, message: String, detail: String = "") {
        val entry = LogEntry(System.currentTimeMillis(), category, level, message, detail)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        scope.launch { append(entry) }
    }

    /** Synchronous write for crash handlers and tests. */
    @Synchronized
    fun logSync(category: LogCategory, level: LogLevel, message: String, detail: String = "") {
        val entry = LogEntry(System.currentTimeMillis(), category, level, message, detail)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        append(entry)
    }

    fun clear() {
        _entries.value = emptyList()
        scope.launch { runCatching { file?.delete() } }
    }

    fun exportText(): String = _entries.value.joinToString("\n") { format(it) }

    /** Test isolation: forget the file and in-memory entries. */
    @Synchronized
    fun resetForTests() {
        _entries.value = emptyList()
        runCatching { file?.delete() }
        file = null
        installed = false
    }

    /** UI/export line. */
    fun format(entry: LogEntry): String {
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(entry.timestamp))
        val detail = entry.detail.trim()
        return if (detail.isEmpty()) {
            "$time [${entry.level}] ${entry.category}: ${entry.message}"
        } else {
            "$time [${entry.level}] ${entry.category}: ${entry.message} — $detail"
        }
    }

    @Synchronized
    private fun append(entry: LogEntry) {
        val target = file ?: return
        runCatching {
            if (target.exists() && target.length() >= MAX_FILE_BYTES) {
                val rotated = File(target.parentFile, "$FILE_NAME.1")
                rotated.delete()
                target.renameTo(rotated)
            }
            target.appendText(encode(entry) + "\n")
        }
    }

    private fun encode(entry: LogEntry): String = listOf(
        entry.timestamp.toString(),
        entry.level.name,
        entry.category.name,
        escape(entry.message),
        escape(entry.detail),
    ).joinToString("|")

    private fun parseLine(line: String): LogEntry? {
        val parts = line.split('|', limit = 5)
        if (parts.size < 4) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val level = runCatching { LogLevel.valueOf(parts[1]) }.getOrNull() ?: return null
        val category = runCatching { LogCategory.valueOf(parts[2]) }.getOrNull() ?: return null
        return LogEntry(
            timestamp = timestamp,
            category = category,
            level = level,
            message = unescape(parts[3]),
            detail = unescape(parts.getOrElse(4) { "" }),
        )
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n' -> out.append('\n')
                    'p' -> out.append('|')
                    '\\' -> out.append('\\')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
