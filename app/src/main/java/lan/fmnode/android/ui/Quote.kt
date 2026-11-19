package lan.fmnode.android.ui

/**
 * FIDO-style quoting used by the reply composer, matching fm-app's
 * `utils/quote.js` so both clients produce the same text.
 */
internal object Quote {
    /** Two-character identifier from an FM address (`:0:1` -> `01`). */
    fun addressToShortId(address: String): String {
        val clean = address.replace(":", "")
        return if (clean.length >= 2) clean.takeLast(2) else clean.padStart(2, '0')
    }

    /** First letters of the first two words: "Павел Романов" -> "ПР". */
    fun initialsFromName(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when (words.size) {
            0 -> ""
            1 -> words[0].take(1).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    /**
     * Prefix every line of [body] with the author's initials (or the short
     * address when there is no name), e.g. `ПР> текст`.
     */
    fun formatFidoQuote(senderNameOrAddress: String, body: String): String {
        val tag = if (senderNameOrAddress.startsWith(":")) {
            addressToShortId(senderNameOrAddress)
        } else {
            initialsFromName(senderNameOrAddress).ifEmpty { addressToShortId(senderNameOrAddress) }
        }
        val lines = body.split("\n")
        return lines
            .filter { it.isNotEmpty() || lines.size > 1 }
            .joinToString("\n") { "$tag> $it" }
    }

    /** `Re:` prefix that is not added twice (normalises the existing prefix). */
    fun replySubject(subject: String): String {
        val value = subject.trim()
        if (value.isEmpty()) return ""
        return if (value.startsWith("Re:", ignoreCase = true)) {
            "Re: " + value.substring(3).trimStart()
        } else {
            "Re: $value"
        }
    }
}
