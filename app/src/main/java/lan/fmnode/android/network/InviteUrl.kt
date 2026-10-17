package lan.fmnode.android.network

import lan.fmnode.android.crypto.Encoding

/**
 * Parsed QR invite: `<app-url>?pubkey=<base64url Ed25519>&voucher=<code>&address=<addr>`.
 * The API endpoint is a sibling of the app directory (`…/app` -> `…/api`),
 * matching fm-app's appUrlToApiUrl.
 */
data class Invite(
    val appUrl: String,
    val apiUrl: String,
    val nodePublicKey: ByteArray?,
    val voucher: String?,
    val address: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Invite) return false
        return appUrl == other.appUrl &&
            apiUrl == other.apiUrl &&
            voucher == other.voucher &&
            address == other.address &&
            (nodePublicKey?.contentEquals(other.nodePublicKey ?: ByteArray(0)) ?: (other.nodePublicKey == null))
    }

    override fun hashCode(): Int = appUrl.hashCode()
}

object InviteParser {
    /**
     * Parses an invite URL (or a plain node URL). Returns null when the text is
     * not a valid absolute URL.
     */
    fun parse(raw: String): Invite? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return try {
            val url = java.net.URI(text)
            if (url.scheme == null || url.host == null) return null
            val origin = "${url.scheme}://${url.host}${if (url.port != -1) ":${url.port}" else ""}"
            val path = (url.path ?: "").trimEnd('/')
            val appUrl = origin + path
            val query = url.rawQuery ?: ""
            val params = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val idx = pair.indexOf('=')
                if (idx < 0) decode(pair) to "" else decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
            }
            val pubkey = params["pubkey"]?.takeIf { it.isNotEmpty() }?.let {
                try {
                    Encoding.base64UrlDecode(it)
                } catch (_: Exception) {
                    null
                }
            }
            Invite(
                appUrl = appUrl,
                apiUrl = appUrlToApiUrl(appUrl),
                nodePublicKey = pubkey,
                voucher = params["voucher"]?.takeIf { it.isNotEmpty() },
                address = params["address"]?.takeIf { it.isNotEmpty() },
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * App URL -> node API URL: `…/app` -> `…/api`; an existing `/api` suffix is
     * kept; any other path gets `/api` appended.
     */
    fun appUrlToApiUrl(appUrl: String): String {
        val url = appUrl.trimEnd('/')
        return when {
            url.endsWith("/app") -> url.dropLast(4) + "/api"
            url.endsWith("/api") -> url
            else -> "$url/api"
        }
    }

    private fun decode(value: String): String =
        java.net.URLDecoder.decode(value, "UTF-8")
}
