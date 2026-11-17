package lan.fmnode.android.storage

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.Encoding

/** A parsed full v2.0 export: the identity of the profile plus the raw payload. */
data class ImportedFullBackup(
    val payload: JsonObject,
    val keystore: ImportedKeystore,
    val publicKeyB64: String,
    val nodePublicKeyB64: String?,
    /**
     * Endpoint of the network the export was taken from, when the export
     * carries one (fm-app writes it in the settings, not next to the keys).
     */
    val nodeUrl: String?,
    val address: String,
    val displayName: String,
    val exportedAt: String?,
    val timestamp: Long,
)

/** fm-app keys / settings / full-backup formats. */
object BackupService {
    const val SETTINGS_META = "settings_json"
    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val messages: Int = 0,
        val blogs: Int = 0,
        val posts: Int = 0,
        val folders: Int = 0,
        val warning: String? = null,
    )

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    /**
     * Parses a full v2.0 export of fm-app.
     *
     * Returns null when [text] is not a full backup, so the caller can fall back
     * to the other import formats. The profile of the export is resolved even
     * without a network: [ImportedFullBackup.keystore] carries the public key and
     * the encrypted seed, and [ImportedFullBackup.nodePublicKeyB64] the node key,
     * which is all that is needed to adopt the identity of the export on a fresh
     * installation.
     */
    fun parseFullBackup(text: String): ImportedFullBackup? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (root.str("version") != "2.0") return null
        val keys = root.obj("keys") ?: return null
        val publicKeyB64 = keys.str("publicKey")?.takeIf { it.isNotBlank() } ?: return null
        val keystore = runCatching { KeystoreExportCodec.decode(root) }.getOrNull() ?: return null
        val user = root.obj("user")
        val meta = KeystoreExportCodec.metadataOf(keys)
        return ImportedFullBackup(
            payload = root,
            keystore = keystore,
            publicKeyB64 = publicKeyB64,
            nodePublicKeyB64 = keys.str("nodePublicKey"),
            nodeUrl = nodeUrlOf(root),
            address = user?.str("address").orEmpty(),
            displayName = user?.str("name").orEmpty(),
            exportedAt = root.str("exportedAt"),
            timestamp = (meta?.get("timestamp") as? JsonPrimitive)
                ?.let { it.contentOrNull?.toLongOrNull() }
                ?: System.currentTimeMillis(),
        )
    }

    private suspend fun settingsData(db: ProfileDatabase): JsonObject? =
        db.metaDao().get(SETTINGS_META)?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

    /**
     * Endpoint of the network the export was taken from. fm-app keeps it in the
     * settings (`network`/`application`) and sometimes at the top level; a keys
     * export does not carry it at all.
     */
    private fun nodeUrlOf(root: JsonObject): String? {
        val settings = root.obj("settings")
        val candidates = listOf(
            root.str("appUrl"),
            root.str("apiUrl"),
            root.str("nodeUrl"),
            settings?.str("appUrl"),
            settings?.str("apiUrl"),
            settings?.str("nodeUrl"),
            settings?.obj("network")?.str("appUrl"),
            settings?.obj("network")?.str("apiUrl"),
            settings?.obj("application")?.str("appUrl"),
            settings?.obj("application")?.str("apiUrl"),
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }

    suspend fun exportSettings(db: ProfileDatabase, profile: ProfileEntity): String {
        val data = EmojiDefaults.settingsWithoutDefaultEmojis(settingsData(db) ?: JsonObject(emptyMap()))
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", "1.0")
                put("type", "fm_settings")
                put("data", data)
                put("nodePublicKey", profile.nodePublicKeyB64)
                put("timestamp", System.currentTimeMillis())
            },
        )
    }

    suspend fun importSettings(db: ProfileDatabase, profile: ProfileEntity, text: String): Result {
        val root = json.parseToJsonElement(text).jsonObject
        require(root.str("type") == "fm_settings") { "not an fm_settings export" }
        require(root.str("version") == "1.0") { "unsupported settings version" }
        val data = root["data"] as? JsonObject ?: error("settings export has no data")
        db.metaDao().put(MetaEntity(SETTINGS_META, data.toString()))
        applyPrivacy(db, data)
        val warning = nodeKeyWarning(root.str("nodePublicKey"), profile)
        return Result(warning = warning)
    }

    private suspend fun applyPrivacy(db: ProfileDatabase, data: JsonObject) {
        val privacy = data["privacy"] as? JsonObject ?: return
        (privacy["autoSendPubkey"] as? JsonPrimitive)?.contentOrNull?.let {
            db.metaDao().put(MetaEntity("auto_send_pubkey", if (it == "true") "1" else "0"))
        }
        (privacy["autoRequestPubkeys"] as? JsonPrimitive)?.contentOrNull?.let {
            db.metaDao().put(MetaEntity("auto_request_pubkeys", if (it == "true") "1" else "0"))
        }
    }

    private fun nodeKeyWarning(nodeKey: String?, profile: ProfileEntity): String? {
        if (nodeKey.isNullOrBlank() || nodeKey == profile.nodePublicKeyB64) return null
        return "nodePublicKey does not match this network; the profile key was kept"
    }

    /** Full fm-app v2.0 backup of the active network profile. */
    suspend fun exportFull(db: ProfileDatabase, profile: ProfileEntity): String {
        val echoes = db.echoDao().getAll()
        val folders = db.netmailFolderDao().getAll()
        val messages = db.messageDao().getAll()
        val blogs = db.blogDao().getAll()
        val posts = db.blogPostDao().getAll()
        val names = db.nameDao().getAll()
        val subscribed = echoes.filter { it.subscribed }.map { it.name }
        val echoIds = echoes.mapNotNull { echo -> echo.serverId?.let { echo.name to it } }.toMap()
        val (settings, emojis) = EmojiDefaults.settingsForFullExport(
            settingsData(db) ?: JsonObject(emptyMap()),
        )

        val messagesByEcho = messages.groupBy { it.echoName }
        val baseNetmail = messagesByEcho[NetworkSessionConstants.NETMAIL_ECHO].orEmpty()
            .filter { it.folderSlug == null }
        val customNetmail = messages.filter { it.echoName == NetworkSessionConstants.NETMAIL_ECHO && it.folderSlug != null }
            .groupBy { it.folderSlug!! }

        val keys = buildJsonObject {
            put("publicKey", profile.publicKeyB64)
            put("encryptedPrivateKey", profile.encryptedSeedB64)
            put("metadata", buildJsonObject {
                put("encrypted", true)
                put("salt", bytes(Encoding.base64Decode(profile.seedSaltB64)))
                put("iterations", profile.seedIterations)
                put("iv", bytes(Encoding.base64Decode(profile.seedIvB64)))
            }.toString())
            put("nodePublicKey", profile.nodePublicKeyB64)
        }

        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("version", "2.0")
                put("exportedAt", Instant.now().toString())
                // A full export is self-sufficient on a fresh install: the app
                // URL is where the node key and endpoint are derived from, as
                // in fm-app (fullExportAppUrl).
                put("appUrl", profile.appUrl)
                put("settings", settings)
                emojis?.let { put("emojis", it) }
                put("keys", keys)
                put("user", buildJsonObject {
                    put("address", profile.address)
                    put("name", profile.displayName)
                    // fm-app stores the public key as hex here; fmclient checks it.
                    put("publicKey", Encoding.toHex(Encoding.base64Decode(profile.publicKeyB64)))
                })
                put("subscriptions", buildJsonArray { subscribed.forEach { add(JsonPrimitive(it)) } })
                put("structure", buildJsonObject {
                    put("echoes", buildJsonArray { subscribed.forEach { add(JsonPrimitive(it)) } })
                    put("netmailFolders", buildJsonObject {
                        folders.forEach { put(it.slug, it.name) }
                    })
                    put("blogs", buildJsonArray {
                        blogs.forEach { add(blogJson(it)) }
                    })
                })
                put("messages", buildJsonObject {
                    messagesByEcho.filterKeys { it != NetworkSessionConstants.NETMAIL_ECHO }.forEach { (echo, rows) ->
                        put("echo_$echo", buildJsonArray {
                            rows.forEach { add(messageJson(it, echoName = echo, echoId = echoIds[echo])) }
                        })
                    }
                    if (baseNetmail.isNotEmpty()) {
                        put("__netmail__", buildJsonArray { baseNetmail.forEach { add(messageJson(it)) } })
                    }
                    customNetmail.forEach { (slug, rows) ->
                        put("netmail_$slug", buildJsonArray { rows.forEach { add(messageJson(it)) } })
                    }
                })
                put("blogPosts", buildJsonArray { posts.forEach { add(postJson(it)) } })
                put("names", buildJsonObject {
                    names.forEach { name ->
                        put(name.address, buildJsonObject {
                            put("seen", buildJsonArray { add(JsonPrimitive(name.name)) })
                        })
                    }
                })
            },
        )
    }

    suspend fun importFull(db: ProfileDatabase, profile: ProfileEntity, text: String): Result =
        importFull(db, profile, json.parseToJsonElement(text).jsonObject)

    suspend fun importFull(db: ProfileDatabase, profile: ProfileEntity, root: JsonObject): Result {
        require(root.str("version") == "2.0") { "unsupported full backup version" }
        var warning: String? = null
        val keys = root["keys"] as? JsonObject
        val importedPub = keys?.str("publicKey")
        if (!importedPub.isNullOrBlank() && importedPub != profile.publicKeyB64) {
            error("this backup belongs to another key; add the network with its keys first")
        }
        warning = nodeKeyWarning(keys?.str("nodePublicKey"), profile)

        (root["settings"] as? JsonObject)?.let { raw ->
            val emojis = root["emojis"] as? JsonArray
            val data = if (emojis != null) EmojiDefaults.withEmojis(raw, emojis) else raw
            db.metaDao().put(MetaEntity(SETTINGS_META, data.toString()))
            applyPrivacy(db, data)
        }

        var folderCount = 0
        val structure = root["structure"] as? JsonObject
        (structure?.get("netmailFolders") as? JsonObject)?.forEach { (slug, value) ->
            val name = (value as? JsonPrimitive)?.contentOrNull ?: slug
            db.netmailFolderDao().upsert(NetmailFolderEntity(slug, name, System.currentTimeMillis()))
            folderCount++
        }
        (root["names"] as? JsonObject)?.forEach { (address, value) ->
            val seen = (value as? JsonObject)?.get("seen") as? JsonArray
            val name = (seen?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            if (!name.isNullOrBlank()) db.nameDao().upsert(NameEntity(address, name, System.currentTimeMillis()))
        }

        var messageCount = 0
        // One netmail message can be exported twice: once in the inbox and once
        // in the custom folder it was moved to. The folder copy wins, so the
        // folder stores are applied last.
        val stores = (root["messages"] as? JsonObject)?.entries
            ?.sortedBy { (store, _) -> if (store.startsWith("netmail_")) 1 else 0 }
            ?: emptyList()
        stores.forEach { (store, rows) ->
            val array = rows as? JsonArray ?: return@forEach
            val echo = when {
                store == "__netmail__" -> NetworkSessionConstants.NETMAIL_ECHO
                store.startsWith("netmail_") -> NetworkSessionConstants.NETMAIL_ECHO
                store.startsWith("echo_") -> store.removePrefix("echo_")
                else -> return@forEach
            }
            val folder = if (store.startsWith("netmail_")) store.removePrefix("netmail_") else null
            val entities = array.mapNotNull { it as? JsonObject }.mapNotNull { m ->
                messageEntity(m, echo, folder)
            }
            if (entities.isNotEmpty()) {
                db.messageDao().upsertAll(entities)
                // The same message may appear in the inbox and in a folder store:
                // count the rows that really exist, not the ones written.
                messageCount = db.messageDao().getAll().size
            }
        }

        val subscribed = (structure?.get("echoes") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()
        if (subscribed.isNotEmpty()) {
            val existing = db.echoDao().getAll().associateBy { it.name }
            subscribed.forEach { name ->
                val echo = existing[name] ?: EchoEntity(name, null, "", "", true, false, 0)
                db.echoDao().upsertAll(listOf(echo.copy(subscribed = true)))
            }
        }

        var blogCount = 0
        var postCount = 0
        (structure?.get("blogs") as? JsonArray)?.forEach { element ->
            val blog = element as? JsonObject ?: return@forEach
            val id = blog.str("id") ?: return@forEach
            db.blogDao().upsert(BlogEntity(
                id = id,
                name = blog.str("name") ?: "Blog",
                description = blog.str("description").orEmpty(),
                createdAt = (blog["createdAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ))
            blogCount++
        }
        (root["blogPosts"] as? JsonArray)?.forEach { element ->
            val post = element as? JsonObject ?: return@forEach
            val id = post.str("id") ?: return@forEach
            db.blogPostDao().upsert(BlogPostEntity(
                id = id,
                blogId = post.str("blogId") ?: return@forEach,
                title = post.str("title").orEmpty(),
                body = post.str("body").orEmpty(),
                attachmentsJson = post["attachments"]?.toString() ?: "[]",
                timestamp = (post["timestamp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ))
            postCount++
        }
        return Result(messageCount, blogCount, postCount, folderCount, warning)
    }

    private fun messageEntity(obj: JsonObject, echo: String, folder: String?): MessageEntity? {
        val id = obj.str("message_id") ?: return null
        return MessageEntity(
            messageId = id,
            echoName = echo,
            type = obj.str("type") ?: "echomail",
            senderAddress = obj.str("sender_address").orEmpty(),
            recipientAddress = obj.str("recipient_address"),
            timestamp = (obj["timestamp"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0,
            dataJson = obj["data"]?.let { if (it is JsonPrimitive) it.content else it.toString() }.orEmpty(),
            jws = obj.str("jws"),
            jwe = obj.str("jwe"),
            receivedAt = System.currentTimeMillis(),
            folderSlug = folder,
        )
    }

    private fun messageJson(m: MessageEntity, echoName: String? = null, echoId: Int? = null): JsonObject =
        buildJsonObject {
            put("message_id", m.messageId)
            put("type", m.type)
            put("sender_address", m.senderAddress)
            m.recipientAddress?.let { put("recipient_address", it) }
            // fm-app message rows carry the conference too; the web client uses
            // it for actions and search after an import.
            echoName?.let { put("echo_name", it) }
            echoId?.let { put("echo_id", it) }
            put("timestamp", m.timestamp)
            put("data", m.dataJson)
            m.jws?.let { put("jws", it) }
            m.jwe?.let { put("jwe", it) }
        }

    private fun blogJson(b: BlogEntity): JsonObject = buildJsonObject {
        put("id", b.id)
        put("name", b.name)
        put("description", b.description)
        put("createdAt", b.createdAt)
    }

    private fun postJson(p: BlogPostEntity): JsonObject = buildJsonObject {
        put("id", p.id)
        put("blogId", p.blogId)
        put("title", p.title)
        put("body", p.body)
        put("attachments", runCatching { json.parseToJsonElement(p.attachmentsJson) }.getOrDefault(JsonArray(emptyList())))
        put("timestamp", p.timestamp)
    }

    private fun bytes(raw: ByteArray): JsonArray = buildJsonArray {
        raw.forEach { add(JsonPrimitive(it.toInt() and 0xff)) }
    }
}

private object NetworkSessionConstants {
    const val NETMAIL_ECHO = "__netmail__"
}
