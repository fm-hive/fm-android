package lan.fmnode.android.storage

/** Loads the full-export fixtures from `src/test/resources`. */
internal object FullExportFixtures {
    /**
     * Sanitized copy of a real fm-app export (generated, no personal data):
     * 160 message rows over 15 echoes plus two netmail stores, but only 89
     * distinct echo/message pairs — ids repeat across echoes on purpose.
     */
    const val FULL_EXPORT = "fm-full-export-tester-0-1-2026-10-27.json"

    /** A standalone keys export: object `metadata`, no `appUrl`, no messages. */
    const val KEYS_EXPORT = "fm-keys-export-tester-0-1-2026-10-27.json"

    fun read(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader()
            .use { it.readText() }
}
