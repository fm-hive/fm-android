package lan.fmnode.android.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.storage.EchoEntity
import lan.fmnode.android.storage.MessageEntity
import lan.fmnode.android.storage.MetaEntity
import lan.fmnode.android.storage.NameEntity
import lan.fmnode.android.storage.OutboxEntity
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.storage.PubkeyEntity

/**
 * One network profile in action: the node API bound to the profile's key pair,
 * its per-network database and the send_receive sync engine.
 */
class NetworkSession(
    val profile: ProfileEntity,
    val database: ProfileDatabase,
    private val api: FmApiClient,
    val seed: ByteArray,
) {
    val publicKey: ByteArray = CryptoService.publicKeyFromSeed(seed)
    private val x25519Private: ByteArray = CryptoService.x25519PrivateKeyFromSeed(seed)
    private val auth = ApiKeys(seed, publicKey)
    private val json = Json { ignoreUnknownKeys = true }

    data class SyncOutcome(val received: Int, val acknowledged: Int, val errors: List<String>)

    data class SendResult(val queued: Boolean, val needsKey: Boolean = false)

    // ------------------------------------------------------------------ echoes

    /** Loads the echo list (with subscription flags) from the node. */
    suspend fun refreshEchoes(): List<EchoEntity> {
        val response = api.post("echoes", null, auth)
        val array = response as? JsonArray
            ?: throw FmApiException(0, "invalid echoes response")
        val existing = database.echoDao().getAll().associateBy { it.name }
        val echoes = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            EchoEntity(
                name = name,
                serverId = (obj["id"] as? JsonPrimitive)?.intOrNull,
                description = obj.string("description").orEmpty(),
                rulesJson = obj["rules"]?.let { rules ->
                    if (rules is JsonPrimitive) rules.content else rules.toString()
                }.orEmpty(),
                subscribed = obj.boolean("subscribed"),
                isLocal = obj.boolean("is_local"),
                lastReadTimestamp = existing[name]?.lastReadTimestamp ?: 0L,
            )
        }
        database.echoDao().upsertAll(echoes)
        return echoes
    }

    suspend fun setSubscribed(echoName: String, subscribed: Boolean) {
        val body = buildJsonObject { put("echo_name", echoName) }
        api.post(if (subscribed) "subscribe_echo" else "unsubscribe_echo", body.toString(), auth)
        database.echoDao().setSubscribed(echoName, subscribed)
    }

    suspend fun markRead(echoName: String, timestamp: Long) {
        database.echoDao().setLastRead(echoName, timestamp)
    }

    // -------------------------------------------------------------------- sync

    /** Pulls new messages, sends queued outbox entries, confirms delivered packets. */
    suspend fun sync(): SyncOutcome {
        flushOutbox()

        val request = buildJsonObject {
            put("messages", buildJsonArray {})
            val pending = pendingDeliveredIds()
            if (pending.isNotEmpty()) {
                put("delivered_local_ids", buildJsonArray { pending.forEach { add(JsonPrimitive(it)) } })
            }
        }
        val response = api.post("send_receive", request.toString(), auth)
        val obj = response as? JsonObject ?: return SyncOutcome(0, 0, emptyList())

        var received = 0
        val errors = mutableListOf<String>()
        for (element in obj["messages"] as? JsonArray ?: JsonArray(emptyList())) {
            when (val result = processIncoming(element as? JsonObject ?: continue)) {
                is Incoming.Stored -> received++
                is Incoming.Error -> errors += result.text
                else -> {}
            }
        }
        val acknowledged = storeDeliveredIds(obj["local_packet_ids"] as? JsonArray)
        return SyncOutcome(received, acknowledged, errors)
    }

    // ------------------------------------------------------------------ sending

    /** Posts a signed protocol message of any type to an echo. */
    suspend fun postProtocol(echoName: String, type: String, dataJson: String): SendResult {
        return try {
            sendMessages(listOf(signMessage(type, dataJson, echoName = echoName)))
            SendResult(queued = false)
        } catch (e: Exception) {
            queueOutbox(type, echoName, null, dataJson, e)
            SendResult(queued = true)
        }
    }

    /**
     * Casts (or retracts with an empty [variant]) a vote on a poster (vote
     * protocol, matching fm-app's vote message shape).
     */
    suspend fun postVote(echoName: String, posterMessage: String, variant: String): SendResult {
        val data = buildJsonObject {
            put("echo", echoName)
            put("proto", "vote")
            put("type", "vote")
            put("from", profile.address)
            put("to", "all")
            put("vote", variant)
            put("poster_message", posterMessage)
            put("message", if (variant.isEmpty()) "Retract vote" else "Vote: $variant")
            put("date", java.time.Instant.now().toString())
        }.toString()
        return postProtocol(echoName, "vote", data)
    }

    /** Posts an echomail message; network failures queue it in the outbox. */
    suspend fun postEcho(
        echoName: String,
        subject: String,
        body: String,
        replyToMessageId: String? = null,
        attachments: JsonArray? = null,
    ): SendResult {
        val data = buildJsonObject {
            put("subject", subject)
            put("body", body)
            put("timestamp", nowSeconds())
            put("sender_name", senderName())
            if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
            if (attachments != null && attachments.isNotEmpty()) put("attachments", attachments)
        }.toString()
        return try {
            sendMessages(listOf(signMessage("echomail", data, echoName = echoName)))
            SendResult(queued = false)
        } catch (e: Exception) {
            queueOutbox("echomail", echoName, null, data, e)
            SendResult(queued = true)
        }
    }

    /**
     * Sends a private message. The recipient's key is required: without it the
     * message is not sent and the caller may request the key.
     */
    suspend fun sendNetmail(
        recipient: String,
        subject: String,
        body: String,
    ): SendResult {
        val data = buildJsonObject {
            put("subject", subject)
            put("body", body)
            put("timestamp", nowSeconds())
            put("sender_name", senderName())
        }.toString()

        val message = buildNetmailMessage(recipient, data)
        if (message == null) {
            if (settingsFlag(META_AUTO_REQUEST_PUBKEYS, default = false)) {
                runCatching { requestPublicKey(recipient) }
            }
            return SendResult(queued = false, needsKey = true)
        }
        return try {
            sendMessages(listOf(message))
            SendResult(queued = false)
        } catch (e: Exception) {
            queueOutbox("netmail", null, recipient, data, e)
            SendResult(queued = true)
        }
    }

    /** Asks the node to deliver the public key of [address] to us. */
    suspend fun requestPublicKey(address: String): Boolean {
        val request = buildJsonObject {
            put("type", "pubkey")
            put("address", address)
            put("requestor_pubkey", Encoding.base64Encode(publicKey))
        }
        val body = buildJsonObject { put("messages", buildJsonArray { add(request) }) }
        val response = api.post("service", body.toString(), auth)
        return (response as? JsonArray)?.any { item ->
            (item as? JsonObject)?.string("status") == "request_accepted"
        } ?: false
    }

    /** Sends our public key to a user who requested it. */
    suspend fun respondToPubkeyRequest(requester: String, requestorPubkeyB64: String) {
        val requestorEd = Encoding.base64Decode(requestorPubkeyB64)
        val requestorX25519 = CryptoService.x25519PublicKeyFromEd25519(requestorEd)
        val responseData = buildJsonObject {
            put("type", "pubkey_response")
            put("address", profile.address)
            put("sign_public_key", Encoding.base64Encode(publicKey))
            put("key_thumbprint", CryptoService.thumbprint(publicKey))
            put("requestor_pubkey", requestorPubkeyB64)
        }.toString()
        val message = buildJsonObject {
            put("type", "netmail")
            put("data", "")
            put("jws", CryptoService.createJws(seed, "", publicKey))
            put(
                "jwe",
                CryptoService.encryptJwe(responseData.toByteArray(Charsets.UTF_8), requestorX25519),
            )
            put("sender_address", profile.address)
            put("recipient_address", requester)
            put("timestamp", nowSeconds())
        }
        sendMessages(listOf(message))
    }

    suspend fun key(address: String): PubkeyEntity? = database.pubkeyDao().get(address)

    // --------------------------------------------------------------- admin

    /** Creates a signed invite voucher and returns its code. */
    suspend fun createVoucher(): String {
        val nonce = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
        val payload = buildJsonObject {
            put("type", "invite")
            put("nonce", nonce)
        }.toString()
        val data = buildJsonObject {
            put("voucher", CryptoService.createJws(seed, payload, publicKey))
            put("type", "invite")
        }.toString()
        val response = api.post("voucher", data, auth)
        return when (response) {
            is JsonPrimitive -> response.content
            is JsonObject -> response.string("voucher")
                ?: throw FmApiException(0, "invalid voucher response")
            else -> throw FmApiException(0, "invalid voucher response")
        }
    }

    /** Nodelist as reported by the node. */
    suspend fun nodeList(): List<JsonObject> {
        val response = api.post("nodes", null, auth = null)
        return (response as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    /**
     * Proposes a merge to the peer from its invite: sends the merge request to
     * our own node (phase 0 starts inside our network).
     */
    suspend fun startMerge(peer: Invite): String {
        val peerKey = peer.nodePublicKey ?: throw FmApiException(0, "the invite has no peer public key")
        val data = buildJsonObject {
            put("url", peer.apiUrl)
            if (!peer.address.isNullOrBlank()) put("address", peer.address)
            put("pubkey", Encoding.base64Encode(peerKey))
        }.toString()
        val response = api.post("merge", data, auth)
        return (response as? JsonObject)?.string("state_key")
            ?: (response as? JsonObject)?.string("status")
            ?: "ok"
    }

    /** Requesters waiting for our key (the UI offers to send it, stage 4). */
    suspend fun pendingPubkeyRequests(): List<Pair<String, String>> {
        val raw = database.metaDao().get(META_PENDING_PUBKEY_REQUESTS) ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val address = obj.string("address") ?: return@mapNotNull null
            val pubkey = obj.string("requestor_pubkey") ?: return@mapNotNull null
            address to pubkey
        }
    }

    suspend fun setAutoSendPubkey(enabled: Boolean) {
        database.metaDao().put(MetaEntity(META_AUTO_SEND_PUBKEY, if (enabled) "1" else "0"))
    }

    suspend fun setAutoRequestPubkeys(enabled: Boolean) {
        database.metaDao().put(MetaEntity(META_AUTO_REQUEST_PUBKEYS, if (enabled) "1" else "0"))
    }

    // ---------------------------------------------------------------- internals

    private sealed interface Incoming {
        data object Ignored : Incoming
        data object Internal : Incoming
        data object Stored : Incoming
        data class Error(val text: String) : Incoming
    }

    private suspend fun processIncoming(msg: JsonObject): Incoming {
        val messageId = msg.string("message_id") ?: return Incoming.Ignored
        val type = msg.string("type") ?: return Incoming.Ignored
        val sender = msg.string("sender_address").orEmpty()
        val timestamp = (msg["timestamp"] as? JsonPrimitive)?.longOrNull ?: 0L
        val jws = msg.string("jws")
        val jwe = msg.string("jwe")
        var dataJson = msg["data"]?.let { if (it is JsonPrimitive) it.content else it.toString() }

        if (dataJson.isNullOrEmpty() && !jws.isNullOrEmpty()) {
            dataJson = CryptoService.jwsPayload(jws)
        }

        if (!jwe.isNullOrEmpty()) {
            val plaintext = runCatching { CryptoService.decryptJwe(jwe, x25519Private) }.getOrNull()
                ?: return Incoming.Ignored
            val decrypted = String(plaintext, Charsets.UTF_8)
            val inner = runCatching { json.parseToJsonElement(decrypted).jsonObject }.getOrNull()
            when (inner?.string("type")) {
                "pubkey_response" -> {
                    val address = inner.string("address")
                    val signKey = inner.string("sign_public_key")
                    if (address != null && signKey != null) {
                        storePubkey(address, signKey, inner.string("key_thumbprint").orEmpty())
                    }
                    return Incoming.Internal
                }
                "pubkey_request" -> {
                    val requester = inner.string("address")
                    val requestorPubkey = inner.string("requestor_pubkey")
                    if (requester != null && requestorPubkey != null) {
                        handlePubkeyRequest(requester, requestorPubkey)
                    }
                    return Incoming.Internal
                }
                else -> dataJson = decrypted
            }
        }

        val parsedData = dataJson?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        if (parsedData?.string("type") == "error") {
            return Incoming.Error(parsedData.string("error").orEmpty())
        }
        parsedData?.string("sender_name")?.takeIf { it.isNotBlank() }?.let { name ->
            if (sender.isNotEmpty()) {
                database.nameDao().upsert(NameEntity(sender, name, System.currentTimeMillis()))
            }
        }

        val echoName = when (type) {
            "netmail" -> NETMAIL_ECHO
            else -> msg.string("echo_name") ?: msg.string("echo") ?: return Incoming.Ignored
        }
        database.messageDao().upsert(
            MessageEntity(
                messageId = messageId,
                echoName = echoName,
                type = type,
                senderAddress = sender,
                recipientAddress = msg.string("recipient_address"),
                timestamp = timestamp,
                dataJson = dataJson.orEmpty(),
                jws = jws,
                jwe = jwe,
                receivedAt = System.currentTimeMillis(),
            ),
        )
        return Incoming.Stored
    }

    private suspend fun handlePubkeyRequest(requester: String, requestorPubkeyB64: String) {
        // The request is delivered by the node on behalf of the requester, so
        // the key belongs to that address; cache it for message verification.
        runCatching {
            val key = Encoding.base64Decode(requestorPubkeyB64)
            storePubkey(requester, requestorPubkeyB64, CryptoService.thumbprint(key))
        }
        if (settingsFlag(META_AUTO_SEND_PUBKEY, default = false)) {
            runCatching { respondToPubkeyRequest(requester, requestorPubkeyB64) }
            return
        }
        val pending = pendingPubkeyRequests().toMutableList()
        pending.removeAll { it.first == requester }
        pending += requester to requestorPubkeyB64
        val array = buildJsonArray {
            pending.forEach { (address, pubkey) ->
                add(buildJsonObject {
                    put("address", address)
                    put("requestor_pubkey", pubkey)
                })
            }
        }
        database.metaDao().put(MetaEntity(META_PENDING_PUBKEY_REQUESTS, array.toString()))
    }

    private suspend fun sendMessages(messages: List<JsonObject>): JsonElement {
        val body = buildJsonObject {
            put("messages", buildJsonArray { messages.forEach { add(it) } })
        }
        val response = api.post("send_receive", body.toString(), auth)
        if (response is JsonObject) {
            storeDeliveredIds(response["local_packet_ids"] as? JsonArray)
            (response["messages"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.let { processIncoming(it) }
            }
        }
        return response
    }

    private fun signMessage(
        type: String,
        dataJson: String,
        echoName: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("timestamp", nowSeconds())
        put("sender_address", profile.address)
        if (echoName != null) put("echo_name", echoName)
        put("jws", CryptoService.createJws(seed, dataJson, publicKey))
    }

    private suspend fun buildNetmailMessage(recipient: String, dataJson: String): JsonObject? {
        val key = database.pubkeyDao().get(recipient) ?: return null
        val recipientEd = runCatching { Encoding.base64Decode(key.signPublicKeyB64) }.getOrNull()
            ?: return null
        val recipientX25519 = CryptoService.x25519PublicKeyFromEd25519(recipientEd)
        return buildJsonObject {
            put("type", "netmail")
            put("data", "")
            put("jws", CryptoService.createJws(seed, "", publicKey))
            put(
                "jwe",
                CryptoService.encryptJwe(dataJson.toByteArray(Charsets.UTF_8), recipientX25519),
            )
            put("sender_address", profile.address)
            put("recipient_address", recipient)
            put("timestamp", nowSeconds())
        }
    }

    private suspend fun flushOutbox() {
        for (entry in database.outboxDao().getAll()) {
            val message = try {
                when (entry.type) {
                    "netmail" -> entry.recipientAddress?.let { buildNetmailMessage(it, entry.dataJson) }
                    else -> entry.echoName?.let { signMessage("echomail", entry.dataJson, echoName = it) }
                }
            } catch (_: Exception) {
                null
            } ?: continue // still no recipient key: keep it queued
            try {
                sendMessages(listOf(message))
                database.outboxDao().delete(entry.id)
            } catch (e: Exception) {
                database.outboxDao().updateAttempt(entry.id, entry.attempts + 1, e.message)
            }
        }
    }

    private suspend fun queueOutbox(
        type: String,
        echoName: String?,
        recipient: String?,
        dataJson: String,
        error: Exception,
    ) {
        database.outboxDao().insert(
            OutboxEntity(
                type = type,
                echoName = echoName,
                recipientAddress = recipient,
                dataJson = dataJson,
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error.message,
            ),
        )
    }

    private suspend fun storePubkey(address: String, signPublicKeyB64: String, thumbprint: String) {
        val key = runCatching { Encoding.base64Decode(signPublicKeyB64) }.getOrNull() ?: return
        database.pubkeyDao().upsert(
            PubkeyEntity(
                address = address,
                signPublicKeyB64 = signPublicKeyB64,
                thumbprint = thumbprint.ifEmpty { CryptoService.thumbprint(key) },
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun pendingDeliveredIds(): List<Long> {
        val raw = database.metaDao().get(META_DELIVERED_IDS) ?: return emptyList()
        return runCatching {
            (json.parseToJsonElement(raw) as JsonArray)
                .mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        }.getOrDefault(emptyList())
    }

    private suspend fun storeDeliveredIds(ids: JsonArray?): Int {
        if (ids == null) return 0
        val fresh = ids.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        if (fresh.isEmpty()) return 0
        val merged = (pendingDeliveredIds() + fresh).distinct()
        database.metaDao().put(
            MetaEntity(META_DELIVERED_IDS, buildJsonArray { merged.forEach { add(JsonPrimitive(it)) } }.toString()),
        )
        return fresh.size
    }

    private suspend fun settingsFlag(key: String, default: Boolean): Boolean {
        val value = database.metaDao().get(key) ?: return default
        return value == "1"
    }

    private suspend fun senderName(): String =
        database.nameDao().get(profile.address)?.name?.takeIf { it.isNotBlank() }
            ?: profile.displayName

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

    companion object {
        const val NETMAIL_ECHO = "__netmail__"
        private const val META_DELIVERED_IDS = "pending_delivered_ids"
        private const val META_PENDING_PUBKEY_REQUESTS = "pending_pubkey_requests"
        private const val META_AUTO_SEND_PUBKEY = "auto_send_pubkey"
        private const val META_AUTO_REQUEST_PUBKEYS = "auto_request_pubkeys"
    }
}
