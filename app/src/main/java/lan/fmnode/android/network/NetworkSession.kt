package lan.fmnode.android.network

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.storage.BlogEntity
import lan.fmnode.android.storage.BlogPostEntity
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
    profile: ProfileEntity,
    val database: ProfileDatabase,
    private val api: FmApiClient,
    val seed: ByteArray,
) {
    /** Profile of this session; the address changes when networks merge (#11). */
    var profile: ProfileEntity = profile
        private set

    val publicKey: ByteArray = CryptoService.publicKeyFromSeed(seed)
    private val x25519Private: ByteArray = CryptoService.x25519PrivateKeyFromSeed(seed)
    private val x25519Public: ByteArray = CryptoService.x25519PublicKeyFromEd25519(publicKey)
    private var registeredThisSession = false
    private var clientSyncEnabled = false
    private var knownClients: Set<String> = emptySet()

    /** Daily rate-limit usage of this address, returned by the node. */
    var rateLimitUsage: Map<String, RateLimitUsage> = emptyMap()
        private set

    /** Rejection reasons collected while sending, drained by the UI (#14). */
    private val pendingErrors = mutableListOf<String>()
    private val auth = ApiKeys(seed, publicKey)
    private val json = Json { ignoreUnknownKeys = true }

    data class SyncOutcome(val received: Int, val acknowledged: Int, val errors: List<String>)

    data class SendResult(val queued: Boolean, val needsKey: Boolean = false)

    /** What the node says about this profile: the address follows merges (#11). */
    data class Identity(
        val address: String,
        val name: String,
        val isAdmin: Boolean,
        val isModerator: Boolean,
    )

    /** A voucher as returned after creation. */
    data class VoucherIssued(
        val code: String,
        val type: String,
        val reusable: Boolean = false,
        val expiresAt: String? = null,
    )

    /** A voucher row from `action: "list"`. */
    data class VoucherInfo(
        val code: String,
        val type: String,
        val status: String,
        val createdBy: String,
        val expiresAt: String,
        val reusable: Boolean,
    )

    /** User-defined group of conferences inside this network. */
    data class EchoGroup(
        val id: String,
        val name: String,
        val echoes: List<String>,
    )

    // ------------------------------------------------------------------ echoes

    /** Loads the echo list (with subscription flags) from the node. */
    suspend fun refreshEchoes(): List<EchoEntity> {
        val response = api.post("echoes", null, auth)
        val array = response as? JsonArray
            ?: throw FmApiException(0, "invalid echoes response")
        val existing = database.echoDao().getAll()
        val existingByName = existing.associateBy { it.name }
        val existingByServerId = existing.mapNotNull { echo ->
            echo.serverId?.let { it to echo }
        }.toMap()
        val renames = mutableListOf<Pair<EchoEntity, String>>()

        val echoes = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val serverId = (obj["id"] as? JsonPrimitive)?.intOrNull
            val old = serverId?.let { existingByServerId[it] } ?: existingByName[name]
            if (old != null && old.name != name) renames += old to name
            EchoEntity(
                name = name,
                serverId = serverId,
                description = obj.string("description").orEmpty(),
                rulesJson = obj["rules"]?.let { rules ->
                    if (rules is JsonPrimitive) rules.content else rules.toString()
                }.orEmpty(),
                subscribed = obj.boolean("subscribed"),
                isLocal = obj.boolean("is_local"),
                lastReadTimestamp = old?.lastReadTimestamp ?: 0L,
            )
        }

        if (renames.isNotEmpty()) {
            // A conference rename is identified by its stable server id. Move
            // all local child rows before replacing the echo row so history,
            // drafts and queued posts stay attached to the new name.
            database.withTransaction {
                for ((old, newName) in renames) {
                    database.messageDao().renameEcho(old.name, newName)
                    database.draftDao().renameEcho(old.name, newName)
                    database.outboxDao().renameEcho(old.name, newName)
                    database.echoDao().delete(old.name)
                }
            }
            // User groups follow a server-side rename.
            runCatching {
                val groups = echoGroups()
                if (groups.any { group -> renames.any { (old, name) -> old.name in group.echoes && old.name != name } }) {
                    saveEchoGroups(
                        groups.map { group ->
                            group.copy(
                                echoes = renames.fold(group.echoes) { acc, (old, name) ->
                                    acc.map { if (it == old.name) name else it }.distinct()
                                },
                            )
                        },
                    )
                }
            }
        }
        database.echoDao().upsertAll(echoes)
        return echoes
    }

    /**
     * Subscribes or unsubscribes an echo. An echo with rules needs the user's
     * signature over the current rules first, so the node's `next: accept_rules`
     * answer is answered transparently and the subscribe is retried.
     */
    suspend fun setSubscribed(echoName: String, subscribed: Boolean) {
        val body = buildJsonObject {
            put("echo_name", echoName)
            put("user_address", profile.address)
        }
        if (!subscribed) {
            api.post("unsubscribe_echo", body.toString(), auth)
            database.echoDao().setSubscribed(echoName, false)
            return
        }
        try {
            api.post("subscribe_echo", body.toString(), auth)
        } catch (e: FmApiException) {
            if (e.next != "accept_rules") throw e
            acceptRules(echoName)
            api.post("subscribe_echo", body.toString(), auth)
        }
        database.echoDao().setSubscribed(echoName, true)
    }

    /** Rules of an echo as the node reported them (empty when the echo has none). */
    suspend fun rulesText(echoName: String): String =
        database.echoDao().getByName(echoName)?.rulesJson.orEmpty()

    /** User-defined conference groups of this network (local only). */
    suspend fun echoGroups(): List<EchoGroup> {
        val raw = database.metaDao().get(META_ECHO_GROUPS) ?: return emptyList()
        val array = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id") ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val echoes = (obj["echoes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
            EchoGroup(id, name, echoes)
        }
    }

    /** Stores the user-defined conference groups of this network. */
    suspend fun saveEchoGroups(groups: List<EchoGroup>) {
        val array = buildJsonArray {
            groups.forEach { group ->
                add(
                    buildJsonObject {
                        put("id", group.id)
                        put("name", group.name)
                        put("echoes", buildJsonArray { group.echoes.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        }
        database.metaDao().put(MetaEntity(META_ECHO_GROUPS, array.toString()))
    }

    /**
     * Signs the current rules of an echo and stores the acceptance on the node.
     * The payload is `{id, rules}` and the id must match what the node computes.
     */
    suspend fun acceptRules(echoName: String) {
        val rulesJson = rulesText(echoName)
        val rules = RulesId.rulesMap(rulesJson)
            ?: throw FmApiException(0, "echo $echoName has no rules")
        val id = RulesId.compute(rulesJson)
            ?: throw FmApiException(0, "echo $echoName has no rules")
        val payload = buildJsonObject {
            put("id", id)
            put("rules", rules)
        }.toString()
        val data = buildJsonObject {
            put("echo_name", echoName)
            put("rules_id", id)
            put("rules_jws", CryptoService.createJws(seed, payload, publicKey))
            put("sig_pkey", Encoding.base64Encode(publicKey))
        }.toString()
        api.post("accept_rules", data, auth)
    }

    /** Fetches the current network policy (phase 1, encrypted for us). */
    suspend fun fetchPolicy(): PolicyInfo =
        PolicyClient(api).fetchPolicy(userPublicKey = publicKey)

    /** Re-signs the current policy with the profile key (policy changed). */
    suspend fun resignPolicy(policy: PolicyInfo): Registration =
        PolicyClient(api).acceptPolicy(
            policy = policy,
            seed = seed,
            publicKey = publicKey,
            name = profile.displayName,
        )

    suspend fun markRead(echoName: String, timestamp: Long) {
        database.echoDao().setLastRead(echoName, timestamp)
    }

    /** Identity of this profile as the node knows it right now. */
    suspend fun identity(): Identity {
        val response = api.post("identity", null, auth) as? JsonObject
            ?: throw FmApiException(0, "invalid identity response")
        return Identity(
            address = response.string("address").orEmpty(),
            name = response.string("name").orEmpty(),
            isAdmin = response.boolean("is_admin"),
            isModerator = response.boolean("is_moderator"),
        )
    }

    /**
     * Adopts the address the node reports (#11). A merge renumbers users, so the
     * address stored at registration goes stale and every sender check fails
     * until it is refreshed; queued payloads keep the old one and are rewritten
     * here so they still go out after the renumbering.
     */
    suspend fun applyAddressChange(newAddress: String) {
        val old = profile.address
        if (newAddress.isEmpty() || newAddress == old) return
        profile = profile.copy(address = newAddress)
        for (entry in database.outboxDao().getAll()) {
            val rewritten = OutboxAddress.rewrite(entry.dataJson, old, newAddress) ?: continue
            database.outboxDao().updateData(entry.id, rewritten)
        }
    }

    /** Adopts a display name the node reports (changed by another client). */
    fun applyNameChange(name: String) {
        if (name.isBlank() || name == profile.displayName) return
        profile = profile.copy(displayName = name)
    }

    /** Adopts the rights the node reports (granted or revoked on the node). */
    fun applyRoleChange(isAdmin: Boolean, isModerator: Boolean) {
        if (isAdmin == profile.isAdmin && isModerator == profile.isModerator) return
        profile = profile.copy(isAdmin = isAdmin, isModerator = isModerator)
    }

    // -------------------------------------------------------------------- sync

    /** Pulls new messages, sends queued outbox entries, confirms delivered packets. */
    suspend fun sync(): SyncOutcome {
        flushOutbox()

        val ownContainer = CLIENT_CONTAINER_PREFIX + clientId()
        // These ids are ACKed by this request. They must be forgotten only after
        // the node accepted the request; otherwise the same ids are sent on
        // every poll and the node logs the same "send_receive ACK" forever.
        val pendingAcks = pendingDeliveredIds()
        val request = buildJsonObject {
            put("messages", buildJsonArray {})
            if (pendingAcks.isNotEmpty()) {
                put("delivered_local_ids", buildJsonArray { pendingAcks.forEach { add(JsonPrimitive(it)) } })
            }
            put(
                "client_sync_read",
                buildJsonArray {
                    add(JsonPrimitive(CLIENT_LIST_CONTAINER))
                    add(JsonPrimitive(ownContainer))
                },
            )
        }
        val response = api.post("send_receive", request.toString(), auth)
        clearDeliveredIds(pendingAcks)
        val obj = response as? JsonObject ?: return SyncOutcome(0, 0, emptyList())
        rateLimitUsage = parseRateLimits(obj["rate_limit"])

        var received = 0
        val errors = mutableListOf<String>()
        val incoming = mutableListOf<JsonObject>()
        var echoRefreshNeeded = false
        for (element in obj["messages"] as? JsonArray ?: JsonArray(emptyList())) {
            val message = element as? JsonObject ?: continue
            if (message.string("type") == "change_rules") echoRefreshNeeded = true
            when (val result = processIncoming(message)) {
                is Incoming.Stored -> {
                    received++
                    incoming += message
                }
                // An edit replaces an existing message instead of creating a
                // new one. It is not a new "received" message, but other
                // clients of this address still need the update through
                // client_synchronize.
                is Incoming.Updated -> incoming += message
                is Incoming.Error -> errors += result.text
                else -> {}
            }
        }
        // A successful change_rules result may have renamed a conference.
        // Refreshing the echo list lets refreshEchoes migrate local rows by id.
        if (echoRefreshNeeded) {
            runCatching { refreshEchoes() }
        }
        val acknowledged = storeDeliveredIds(obj["local_packet_ids"] as? JsonArray)
        clientSyncEnabled = (obj["client_synchronize"] as? JsonPrimitive)?.content == "true"
        if (clientSyncEnabled) {
            processClientContainers(obj["client_container_entries"] as? JsonArray, incoming)
        }
        // A key delivered in this very response may unblock queued netmail, so
        // the outbox is flushed again after the incoming messages are stored.
        flushOutbox()
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
     * Creates a vote poster in [echoName] (vote protocol): the question and the
     * variants other users choose from.
     */
    suspend fun postVotePoster(
        echoName: String,
        question: String,
        variants: List<String>,
        rateLimitOverride: Boolean = false,
    ): SendResult {
        val data = buildJsonObject {
            put("echo", echoName)
            put("proto", "vote")
            put("type", "poster")
            put("from", profile.address)
            put("to", "all")
            put("message", question)
            put("variants", buildJsonArray { variants.forEach { add(JsonPrimitive(it)) } })
            put("date", java.time.Instant.now().toString())
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()
        return postProtocol(echoName, "vote", data)
    }

    /**
     * Casts (or retracts with an empty [variant]) a vote on a poster (vote
     * protocol, matching fm-app's vote message shape).
     */
    suspend fun postVote(
        echoName: String,
        posterMessage: String,
        variant: String,
        rateLimitOverride: Boolean = false,
    ): SendResult {
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
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()
        return postProtocol(echoName, "vote", data)
    }

    /**
     * Posts an echomail message (or an announcement); network failures queue
     * it in the outbox with the same message type.
     */
    suspend fun postEcho(
        echoName: String,
        subject: String,
        body: String,
        replyToMessageId: String? = null,
        attachments: JsonArray? = null,
        announcement: Boolean = false,
        tagline: String = "",
        tearline: String? = null,
        rateLimitOverride: Boolean = false,
    ): SendResult {
        val messageType = if (announcement) "announcement" else "echomail"
        val data = buildJsonObject {
            put("subject", subject)
            put("body", body)
            put("timestamp", nowSeconds())
            put("sender_name", senderName())
            if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
            if (attachments != null && attachments.isNotEmpty()) put("attachments", attachments)
            // Always sent: an empty value removes the default tagline for this
            // one message, matching fm-app (#309).
            put("tagline", tagline)
            if (tearline != null) put("tearline", tearline)
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()
        return try {
            sendMessages(listOf(signMessage(messageType, data, echoName = echoName)))
            SendResult(queued = false)
        } catch (e: Exception) {
            queueOutbox(messageType, echoName, null, data, e)
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
        attachments: JsonArray? = null,
        replyToMessageId: String? = null,
        tagline: String = "",
        tearline: String? = null,
        rateLimitOverride: Boolean = false,
    ): SendResult {
        val data = buildJsonObject {
            put("subject", subject)
            put("body", body)
            put("timestamp", nowSeconds())
            put("sender_name", senderName())
            if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
            if (attachments != null && attachments.isNotEmpty()) put("attachments", attachments)
            put("tagline", tagline)
            if (tearline != null) put("tearline", tearline)
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()

        val message = buildNetmailMessage(recipient, data)
        if (message == null) {
            if (settingsFlag(META_AUTO_REQUEST_PUBKEYS, default = false)) {
                runCatching { requestPublicKey(recipient) }
            }
            // Keep the message until the key arrives; sync() retries the outbox
            // and sends it as soon as a pubkey_response is stored (#27).
            queueOutbox("netmail", null, recipient, data, FmApiException(0, "recipient key is unknown"))
            return SendResult(queued = true, needsKey = true)
        }
        return try {
            sendMessages(listOf(message))
            recordSentNetmail(recipient, data, message.string("jws"))
            SendResult(queued = false)
        } catch (e: Exception) {
            queueOutbox("netmail", null, recipient, data, e)
            SendResult(queued = true)
        }
    }

    /** Sends a voucher request netmail to an open node admin. */
    suspend fun requestVoucher(adminAddress: String, openNodeAddress: String): SendResult {
        if (key(adminAddress) == null) {
            runCatching { requestPublicKey(adminAddress) }
            runCatching { sync() }
        }
        return sendNetmail(
            adminAddress,
            "Voucher request",
            "Please send a voucher for the open node $openNodeAddress",
        )
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

    /**
     * Sends a `remote_subscribe` request for a channel hosted on another node
     * (docs/proto/remote_subscribe.md). The node routes it hop-by-hop.
     */
    suspend fun postRemoteSubscribe(echoName: String, hostNode: String): SendResult {
        val data = buildJsonObject {
            put("proto", "remote_subscribe")
            put("type", "request")
            put("from", profile.address)
            put("to", "all")
            put("message", "Remote subscribe to $echoName")
            put("date", java.time.Instant.now().toString())
            put("echo", echoName)
            put("host_node", hostNode)
            put("requester", profile.address)
        }.toString()
        // The channel is hosted elsewhere, so it may not exist locally: the
        // request carries it in the signed payload (`echo`), not in the
        // envelope (an unknown echo_name would be rejected with "echo not found").
        return postProtocol("", "remote_subscribe", data)
    }

    /** Adds or retracts ([emoji] empty) a reaction to a message. */
    suspend fun postReaction(
        targetMessageId: String,
        emoji: String,
        echoName: String,
        rateLimitOverride: Boolean = false,
    ): SendResult {
        val data = buildJsonObject {
            put("reaction", emoji)
            put("reactionMessageId", targetMessageId)
            put("echo", echoName)
            put("message", "")
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()
        return postProtocol(echoName, "echomail", data)
    }

    /** Starts a moderator election in [echoName] (docs/proto/election.md). */
    suspend fun postElectionPoster(echoName: String, rateLimitOverride: Boolean = false): SendResult {
        val data = buildJsonObject {
            put("echo", echoName)
            put("proto", "election")
            put("type", "poster")
            put("from", profile.address)
            put("to", "all")
            put("message", "Moderator election")
            put("date", java.time.Instant.now().toString())
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()
        return postProtocol(echoName, "election", data)
    }

    /** Votes for a candidate ([candidate] empty retracts) in an election. */
    suspend fun postElectionVote(
        echoName: String,
        candidate: String,
        rateLimitOverride: Boolean = false,
    ): SendResult {
        val data = buildJsonObject {
            put("echo", echoName)
            put("proto", "election")
            put("type", "vote")
            put("from", profile.address)
            put("to", candidate)
            put("message", if (candidate.isEmpty()) "Retract vote" else "Vote for $candidate")
            put("date", java.time.Instant.now().toString())
            if (rateLimitOverride) put("rate_limit_override", true)
        }.toString()
        return postProtocol(echoName, "election", data)
    }

    /**
     * Appeals a moderator warning/punishment in the Admin council
     * (docs/proto/appeal.md). The node validates the referenced punishment and
     * opens the network vote.
     */
    suspend fun postAppeal(
        echoName: String,
        moderator: String,
        punishmentMessageId: String,
        punishmentEcho: String,
        severity: String,
        targetAddress: String,
        reason: String,
        attachments: JsonArray? = null,
    ): SendResult {
        val data = buildJsonObject {
            put("proto", "appeal")
            put("type", "poster")
            put("from", profile.address)
            put("to", "all")
            put("message", reason)
            put("date", java.time.Instant.now().toString())
            put("echo", echoName)
            put("protoAddress", moderator)
            put("punishmentMessageId", punishmentMessageId)
            put("punishmentEcho", punishmentEcho)
            put("severity", severity)
            put("targetAddress", targetAddress)
            if (attachments != null && attachments.isNotEmpty()) put("attachments", attachments)
        }.toString()
        return postProtocol(echoName, "appeal", data)
    }

    /** Requests old messages of an echo before [beforeIso] (refill protocol). */
    suspend fun postRefillRequest(echoName: String, beforeIso: String): SendResult {
        val messageId = "refill_${echoName}_${profile.address}_${System.currentTimeMillis()}_" +
            java.util.UUID.randomUUID().toString().take(6)
        val data = buildJsonObject {
            put("proto", "refill")
            put("type", "request")
            put("from", profile.address)
            put("to", "all")
            put("message", "Refill request for $echoName")
            put("date", java.time.Instant.now().toString())
            put("echo", echoName)
            put(
                "refill",
                buildJsonObject {
                    put("from", profile.address)
                    put("echo", echoName)
                    put("before", beforeIso)
                    put("messageId", messageId)
                },
            )
        }.toString()
        val message = signMessage("refill", data, echoName = echoName)
        val result = try {
            sendMessages(listOf(message))
            SendResult(queued = false)
        } catch (e: Exception) {
            queueOutbox("refill", echoName, null, data, e)
            SendResult(queued = true)
        }
        // The node does not deliver a refill request back to its author
        // (distributeRefillRequest skips the requester), so keep a local copy:
        // otherwise neither Protocols nor the feed can show that a refill was
        // requested (#46). Other subscribers still get the node's own copy.
        database.messageDao().upsert(
            MessageEntity(
                messageId = messageId,
                echoName = echoName,
                type = "refill",
                senderAddress = profile.address,
                recipientAddress = null,
                timestamp = message.long("timestamp") ?: nowSeconds(),
                dataJson = data,
                jws = message.string("jws"),
                jwe = null,
                receivedAt = System.currentTimeMillis(),
            ),
        )
        return result
    }

    /**
     * Answers a refill request by relaying our stored messages of [echoName]
     * older than [beforeIso] (refill protocol, `type:"response"`).
     *
     * The node does not treat a thumbs-up reaction as an answer: only a
     * `refill` response carrying the messages (`refill.messages`, each with its
     * original JWS) makes it deliver history to the requester.
     *
     * Returns the number of relayed messages, or 0 when we have none.
     */
    suspend fun respondRefill(echoName: String, requester: String, beforeIso: String): Int {
        val before = parseRefillDate(beforeIso)
            ?: throw IllegalArgumentException("invalid refill date: $beforeIso")
        val stored = database.messageDao().getSignedBefore(echoName, before)
        val wire = stored.mapNotNull { row ->
            val jws = row.jws?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            buildJsonObject {
                put("type", row.type)
                put("sender_address", row.senderAddress)
                put("timestamp", row.timestamp)
                put("message_id", row.messageId)
                put("jws", jws)
                row.recipientAddress?.let { put("recipient_address", it) }
            }
        }
        if (wire.isEmpty()) return 0
        val data = buildJsonObject {
            put("proto", "refill")
            put("type", "response")
            put("echo", echoName)
            put("date", java.time.Instant.now().toString())
            put(
                "refill",
                buildJsonObject {
                    put("echo", echoName)
                    put("to", requester)
                    put("messages", buildJsonArray { wire.forEach { add(it) } })
                },
            )
        }.toString()
        postProtocol(echoName, "refill", data)
        return wire.size
    }

    /**
     * Epoch seconds of a refill `before` value. Accepts the `YYYY-MM-DD` the
     * dialog sends and a full ISO timestamp, like the console client does.
     */
    private fun parseRefillDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { return java.time.LocalDate.parse(text).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond() }
        runCatching { return java.time.Instant.parse(text).epochSecond }
        runCatching {
            return java.time.OffsetDateTime.parse(text).toEpochSecond()
        }
        return null
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

    /**
     * Creates a signed voucher.
     *
     * [type] is `invite` (single use) or `invite_time` (reusable until [ttl]
     * expires, admin only). [ttl] accepts Go durations (`30m`, `24h`) and days
     * (`7d`); empty means the node default of 24 hours.
     */
    suspend fun createVoucher(type: String = VOUCHER_INVITE, ttl: String? = null): VoucherIssued {
        val nonce = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
        val payload = buildJsonObject {
            put("type", type)
            put("nonce", nonce)
            if (!ttl.isNullOrBlank()) put("ttl", ttl)
        }.toString()
        val data = buildJsonObject {
            put("voucher", CryptoService.createJws(seed, payload, publicKey))
            put("type", type)
        }.toString()
        val response = api.post("voucher", data, auth)
        return when (response) {
            is JsonPrimitive -> VoucherIssued(code = response.content, type = type)
            is JsonObject -> VoucherIssued(
                code = response.string("voucher") ?: throw FmApiException(0, "invalid voucher response"),
                type = response.string("type") ?: type,
                reusable = response.boolean("reusable"),
                expiresAt = response.string("expires_at"),
            )
            else -> throw FmApiException(0, "invalid voucher response")
        }
    }

    /** Vouchers of this user (an admin sees every voucher of the node). */
    suspend fun vouchers(): List<VoucherInfo> {
        val data = buildJsonObject { put("action", "list") }.toString()
        val response = api.post("voucher", data, auth) as? JsonObject ?: return emptyList()
        val array = response["vouchers"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val code = obj.string("code") ?: return@mapNotNull null
            VoucherInfo(
                code = code,
                type = obj.string("type").orEmpty(),
                status = obj.string("status").orEmpty(),
                createdBy = obj.string("created_by").orEmpty(),
                expiresAt = obj.string("expires_at").orEmpty(),
                reusable = obj.boolean("reusable"),
            )
        }
    }

    /** Revokes a voucher; the author and a node admin may do it. */
    suspend fun revokeVoucher(code: String) {
        val data = buildJsonObject {
            put("action", "revoke")
            put("code", code)
        }.toString()
        api.post("voucher", data, auth)
    }

    /**
     * Creates a local conference (admin only).
     *
     * [writers] and [electWritersFrom] become the conference rules on the node:
     * an echo created with writers is a channel where only the listed addresses
     * (or `all`) may post. [retractVote] enables vote retraction with
     * [retractVoteDays] as its window.
     */
    suspend fun createLocalEcho(
        name: String,
        description: String,
        writers: List<String> = emptyList(),
        electWritersFrom: List<String> = emptyList(),
        retractVote: Boolean = false,
        retractVoteDays: Int? = null,
    ) {
        val data = buildJsonObject {
            put("name", name)
            put("description", description)
            put("user_address", profile.address)
            putWriters(writers, electWritersFrom, retractVote, retractVoteDays)
        }
        api.post("create_local_echo", data.toString(), auth)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putWriters(
        writers: List<String>,
        electWritersFrom: List<String>,
        retractVote: Boolean,
        retractVoteDays: Int?,
    ) {
        if (writers.isNotEmpty()) {
            put("writers", buildJsonArray { writers.forEach { add(JsonPrimitive(it)) } })
        }
        if (electWritersFrom.isNotEmpty()) {
            put("elect_writers_from", buildJsonArray { electWritersFrom.forEach { add(JsonPrimitive(it)) } })
        }
        if (retractVote) {
            put("retract_vote", "enable")
            put("retract_vote_days", (retractVoteDays ?: 1).toString())
        }
    }

    /**
     * Creates a conference with rules ([rulesJson] is `{"text", "parameters"}`).
     * [networkChannel] keeps the conference local to this node instead of
     * announcing it to the network with a NEW_ECHO broadcast.
     */
    suspend fun createEchoWithRules(
        name: String,
        description: String,
        rulesJson: String,
        networkChannel: Boolean,
    ) {
        val rules = runCatching { json.parseToJsonElement(rulesJson) }.getOrNull()
            ?: throw FmApiException(0, "rules must be valid JSON")
        val data = buildJsonObject {
            put("name", name)
            put("description", description)
            put("rules", rules)
            put("network_channel", networkChannel)
            put("user_address", profile.address)
        }
        api.post("create_echo_with_rules", data.toString(), auth)
    }

    /**
     * Proposes new rules for a conference (moderator only). [newName] renames the
     * conference through the same protocol: the node votes on the name as its own
     * diff chunk.
     */
    suspend fun proposeChangeRules(
        echoName: String,
        rulesJson: String,
        allChunksOnly: Boolean,
        newName: String?,
    ): SendResult {
        val rules = runCatching { json.parseToJsonElement(rulesJson) }.getOrNull()
            ?: throw FmApiException(0, "rules must be valid JSON")
        val data = buildJsonObject {
            put("proto", "change_rules")
            put("from", profile.address)
            put("to", "all")
            put("subject", "Change rules")
            put("message", "Rules change proposal")
            put("date", java.time.Instant.now().toString())
            put("node1", profile.address)
            put("echo", echoName)
            put("all_chunks_only", allChunksOnly)
            put("proposed_rules", rules)
            if (!newName.isNullOrBlank() && newName != echoName) put("new_name", newName)
        }.toString()
        return postProtocol(echoName, "change_rules", data)
    }

    /** Updates the display name of this profile on the node. */
    suspend fun setName(name: String) {
        val data = buildJsonObject { put("name", name) }.toString()
        api.post("set_name", data, auth)
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

    /**
     * Whether a netmail to an unknown recipient asks the node for its key.
     *
     * With this off (the default, same as fm-app) a netmail to somebody we have
     * never exchanged keys with stays in the outbox forever: keys only arrive in
     * `pubkey_response`, and nothing requests them. The Settings screen exposes
     * this switch so the queue is not a dead end.
     */
    suspend fun autoRequestPubkeys(): Boolean = settingsFlag(META_AUTO_REQUEST_PUBKEYS, default = false)

    /** Whether a `pubkey_request` from another user is answered automatically. */
    suspend fun autoSendPubkey(): Boolean = settingsFlag(META_AUTO_SEND_PUBKEY, default = false)

    /** Default user tagline appended to outgoing echomail and netmail (#309). */
    suspend fun tagline(): String = database.metaDao().get(META_TAGLINE).orEmpty()

    suspend fun setTagline(value: String) {
        database.metaDao().put(MetaEntity(META_TAGLINE, value.trim()))
    }

    /** Whether the client tearline is appended and shown (#311). */
    suspend fun showTearline(): Boolean = settingsFlag(META_SHOW_TEARLINE, default = false)

    suspend fun setShowTearline(value: Boolean) {
        database.metaDao().put(MetaEntity(META_SHOW_TEARLINE, if (value) "1" else "0"))
    }

    /**
     * Whether protocol/service messages (refill, elections, change_policy, ...)
     * are listed in the echo feed. Off by default, like fm-app.
     */
    suspend fun showServiceMessages(): Boolean =
        settingsFlag(META_SHOW_SERVICE_MESSAGES, default = false)

    suspend fun setShowServiceMessages(value: Boolean) {
        database.metaDao().put(MetaEntity(META_SHOW_SERVICE_MESSAGES, if (value) "1" else "0"))
    }

    /** Max width/height used when resizing picked images (0 = original). */
    suspend fun imageMaxDimension(): Int =
        database.metaDao().get(META_IMAGE_MAX_DIMENSION)?.toIntOrNull() ?: 1280

    suspend fun setImageMaxDimension(value: Int) {
        database.metaDao().put(MetaEntity(META_IMAGE_MAX_DIMENSION, value.coerceAtLeast(0).toString()))
    }

    /** Whether the composer may send over the daily rate limit (fm-app parity). */
    suspend fun allowSendingWithoutRateLimit(): Boolean =
        settingsFlag(META_ALLOW_WITHOUT_RATE_LIMIT, default = false)

    suspend fun setAllowSendingWithoutRateLimit(value: Boolean) {
        database.metaDao().put(MetaEntity(META_ALLOW_WITHOUT_RATE_LIMIT, if (value) "1" else "0"))
    }

    /** The client line written under the tagline, matching fm-app's format. */
    fun clientTearline(): String {
        val version = lan.fmnode.android.BuildConfig.VERSION_NAME
        return "fm-android v$version ${profile.address}"
    }

    // -------------------------------------------------------- client sync

    private suspend fun clientId(): String {
        database.metaDao().get(META_CLIENT_ID)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        database.metaDao().put(MetaEntity(META_CLIENT_ID, id))
        android.util.Log.i("NetworkSession", "client sync: registered local client id $id")
        return id
    }

    private fun encryptClientPayload(op: JsonObject): String =
        CryptoService.encryptJwe(op.toString().toByteArray(Charsets.UTF_8), x25519Public)

    private fun decryptClientPayload(payload: String): JsonObject? {
        // The node wraps every payload with the user's key; the client may wrap
        // it once more (fm-app's client-side encryption, on by default).
        val nodeLayer = runCatching {
            String(CryptoService.decryptJwe(payload, x25519Private), Charsets.UTF_8)
        }.getOrElse { payload }
        val clientLayer = runCatching {
            String(CryptoService.decryptJwe(nodeLayer, x25519Private), Charsets.UTF_8)
        }.getOrNull() ?: nodeLayer
        return runCatching { json.parseToJsonElement(clientLayer).jsonObject }.getOrNull()
    }

    private suspend fun appendContainer(name: String, op: JsonObject) {
        val data = buildJsonObject {
            put("action", "append")
            put("name", name)
            put("payload", encryptClientPayload(op))
        }
        api.post("client_synchronize", data.toString(), auth)
    }

    private suspend fun deleteContainerEntries(name: String, ids: List<JsonElement>) {
        if (ids.isEmpty()) return
        val data = buildJsonObject {
            put("action", "delete")
            put("name", name)
            put("ids", buildJsonArray { ids.forEach { add(it) } })
        }
        api.post("client_synchronize", data.toString(), auth)
    }

    /** Shares a local blog operation with the other devices of this identity. */
    suspend fun broadcastBlogOp(op: JsonObject) {
        appendToClients(knownClients, op)
    }

    /** Removes this client from the list on logout (best effort). */
    suspend fun unregisterClient() {
        if (!clientSyncEnabled) return
        runCatching {
            appendContainer(
                CLIENT_LIST_CONTAINER,
                buildJsonObject {
                    put("op", "remove")
                    put("client", clientId())
                },
            )
        }
    }

    /**
     * Keeps a local copy of a sent netmail in the Sent folder and shares it
     * with the other clients of this address. A failed copy must never requeue
     * the already delivered message.
     */
    private suspend fun recordSentNetmail(recipient: String, dataJson: String, jws: String?) {
        runCatching {
            val timestamp = runCatching {
                val data = json.parseToJsonElement(dataJson).jsonObject
                (data["timestamp"] as? JsonPrimitive)?.longOrNull
            }.getOrNull() ?: nowSeconds()
            val sent = MessageEntity(
                messageId = "sent_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}",
                echoName = NETMAIL_ECHO,
                type = "netmail",
                senderAddress = profile.address,
                recipientAddress = recipient,
                timestamp = timestamp,
                dataJson = dataJson,
                jws = jws,
                jwe = null,
                receivedAt = System.currentTimeMillis(),
                versionsJson = null,
                folderSlug = null,
            )
            database.messageDao().upsert(sent)
            appendToClients(
                knownClients,
                buildJsonObject {
                    put("op", "sent")
                    put("message", clientMessage(sent, sent = true))
                },
            )
        }
    }

    /**
     * Applies the client-sync containers returned by send_receive: rebuilds the
     * client list, consumes this client's own queue, shares history with a new
     * client and forwards freshly received messages to the other devices.
     */
    private suspend fun processClientContainers(entries: JsonArray?, incoming: List<JsonObject>) {
        val me = clientId()
        val ownContainer = CLIENT_CONTAINER_PREFIX + me
        val listEntries = mutableListOf<JsonObject>()
        val ownEntries = mutableListOf<JsonObject>()
        entries?.forEach { element ->
            val container = element as? JsonObject ?: return@forEach
            when (container.string("name")) {
                CLIENT_LIST_CONTAINER -> (container["entries"] as? JsonArray)?.forEach {
                    (it as? JsonObject)?.let(listEntries::add)
                }
                ownContainer -> (container["entries"] as? JsonArray)?.forEach {
                    (it as? JsonObject)?.let(ownEntries::add)
                }
            }
        }

        val clients = mutableSetOf<String>()
        for (entry in listEntries) {
            val op = entry.string("payload")?.let { decryptClientPayload(it) } ?: continue
            val client = op.string("client") ?: continue
            if (op.string("op") == "remove") clients.remove(client) else clients.add(client)
        }
        android.util.Log.d(
            "NetworkSession",
            "client sync: me=$me clients=$clients ownEntries=${ownEntries.size}",
        )

        // Register before sharing history so the other side can address us.
        var justRegistered = false
        if (me !in clients && !registeredThisSession) {
            appendContainer(
                CLIENT_LIST_CONTAINER,
                buildJsonObject {
                    put("op", "add")
                    put("client", me)
                },
            )
            registeredThisSession = true
            justRegistered = true
            clients.add(me)
        }

        // A new client missed all previous netmail and blogs. Share local
        // history with it (and with everybody after our own registration).
        // `justRegistered` is local: history must be uploaded once, not on
        // every poll for the rest of the session.
        val knownBefore = loadKnownClients()
        val fresh = clients.filter { it != me && it !in knownBefore }.toSet()
        if (justRegistered || fresh.isNotEmpty()) {
            val targets = if (justRegistered) clients - me else fresh
            uploadLocalHistory(targets)
        }
        knownClients = clients - me
        persistKnownClients(knownClients)

        val ackIds = mutableListOf<JsonElement>()
        for (entry in ownEntries) {
            val op = entry.string("payload")?.let { decryptClientPayload(it) }
            if (op == null) {
                // An entry written by another key or an old client must not
                // block this queue forever: ACK it even though it is skipped.
                android.util.Log.w("NetworkSession", "client sync: undecryptable entry ${entry["id"]}")
            } else {
                when (op.string("op")) {
                    "message", "sent" -> (op["message"] as? JsonObject)?.let { message ->
                        when (processIncoming(message)) {
                            is Incoming.Stored, is Incoming.Updated, is Incoming.Error -> {}
                            else -> {}
                        }
                    }
                    "blog" -> (op["blog"] as? JsonObject)?.let { blog ->
                        val id = blog.string("id") ?: return@let
                        database.blogDao().upsert(
                            BlogEntity(
                                id = id,
                                name = blog.string("name").orEmpty(),
                                description = blog.string("description").orEmpty(),
                                createdAt = blog.long("createdAt") ?: System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    "blogPost" -> (op["post"] as? JsonObject)?.let { post ->
                        val id = post.string("id") ?: return@let
                        val blogId = post.string("blogId") ?: return@let
                        database.blogPostDao().upsert(
                            BlogPostEntity(
                                id = id,
                                blogId = blogId,
                                title = post.string("title").orEmpty(),
                                body = post.string("body").orEmpty(),
                                attachmentsJson = post["attachments"]?.toString() ?: "[]",
                                timestamp = post.long("timestamp") ?: System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    "blogDelete" -> op.string("id")?.let { database.blogPostDao().deleteByBlog(it); database.blogDao().delete(it) }
                    "blogPostDelete" -> op.string("id")?.let { database.blogPostDao().delete(it) }
                }
            }
            (entry["id"] as? JsonPrimitive)?.let(ackIds::add)
        }
        if (ackIds.isNotEmpty()) deleteContainerEntries(ownContainer, ackIds)

        if (incoming.isNotEmpty()) {
            val others = clients - me
            for (message in incoming) {
                appendToClients(
                    others,
                    buildJsonObject {
                        put("op", "message")
                        put("message", message)
                    },
                )
            }
        }
    }

    /** Sends one op to every client in [targets], best effort. */
    private suspend fun appendToClients(targets: Set<String>, op: JsonObject) {
        if (!clientSyncEnabled || targets.isEmpty()) return
        val me = clientId()
        for (target in targets) {
            if (target.isBlank() || target == me) continue
            runCatching { appendContainer(CLIENT_CONTAINER_PREFIX + target, op) }
        }
    }

    /**
     * Shares local netmail and blogs with [targets] (a newly joined client or
     * every other client right after this client registered). Mirrors fm-app's
     * `uploadLocalHistory`, so a device that was offline for a while catches up.
     */
    private suspend fun uploadLocalHistory(targets: Set<String>) {
        if (!clientSyncEnabled || targets.isEmpty()) return
        val netmail = database.messageDao().getRecent(NETMAIL_ECHO, 100).reversed()
        for (message in netmail) {
            val sent = message.senderAddress == profile.address && !message.recipientAddress.isNullOrBlank()
            appendToClients(
                targets,
                buildJsonObject {
                    put("op", if (sent) "sent" else "message")
                    put("message", clientMessage(message, sent))
                },
            )
        }
        for (blog in database.blogDao().getAll()) {
            appendToClients(
                targets,
                buildJsonObject {
                    put("op", "blog")
                    put(
                        "blog",
                        buildJsonObject {
                            put("id", blog.id)
                            put("name", blog.name)
                            put("description", blog.description)
                            put("createdAt", blog.createdAt)
                            put("updatedAt", blog.updatedAt)
                        },
                    )
                },
            )
        }
        for (post in database.blogPostDao().getAll().takeLast(100)) {
            val attachments = runCatching { json.parseToJsonElement(post.attachmentsJson) }
                .getOrElse { buildJsonArray {} }
            appendToClients(
                targets,
                buildJsonObject {
                    put("op", "blogPost")
                    put(
                        "post",
                        buildJsonObject {
                            put("id", post.id)
                            put("blogId", post.blogId)
                            put("title", post.title)
                            put("body", post.body)
                            put("attachments", attachments)
                            put("timestamp", post.timestamp)
                        },
                    )
                },
            )
        }
    }

    /** Wire shape of one locally stored message for the client-sync queue. */
    private fun clientMessage(message: MessageEntity, sent: Boolean): JsonObject = buildJsonObject {
        put("message_id", message.messageId)
        put("type", message.type)
        put("sender_address", message.senderAddress)
        message.recipientAddress?.let { put("recipient_address", it) }
        put("timestamp", message.timestamp)
        put("data", message.dataJson)
        message.jws?.let { put("jws", it) }
        if (sent) put("_internal", true)
    }

    private suspend fun loadKnownClients(): Set<String> {
        val raw = database.metaDao().get(META_KNOWN_CLIENTS) ?: return emptySet()
        return runCatching {
            (json.parseToJsonElement(raw) as JsonArray)
                .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun persistKnownClients(clients: Set<String>) {
        database.metaDao().put(
            MetaEntity(
                META_KNOWN_CLIENTS,
                buildJsonArray { clients.forEach { add(JsonPrimitive(it)) } }.toString(),
            ),
        )
    }

    // ---------------------------------------------------------------- internals

    private sealed interface Incoming {
        data object Ignored : Incoming
        data object Internal : Incoming
        data object Stored : Incoming
        /** A plain message update that replaced an existing message. */
        data object Updated : Incoming
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
        // A plain message update (the author re-sent the text with
        // updateMessageId) replaces the original; the old copy is kept in the
        // edit history. Updates from other authors are ignored.
        val updateTarget = parsedData?.string("updateMessageId")
        if (!updateTarget.isNullOrEmpty() &&
            parsedData.string("severity").isNullOrEmpty() &&
            parsedData.string("reaction").isNullOrEmpty() &&
            parsedData.string("proto").isNullOrEmpty()
        ) {
            val updateEcho = when (type) {
                "netmail" -> NETMAIL_ECHO
                else -> msg.string("echo_name") ?: msg.string("echo") ?: ""
            }
            val original = database.messageDao().getByEchoAndId(updateEcho, updateTarget)
            if (original != null && original.senderAddress == sender) {
                val versions = runCatching {
                    (json.parseToJsonElement(original.versionsJson ?: "[]") as JsonArray).toMutableList()
                }.getOrDefault(mutableListOf())
                versions += buildJsonObject {
                    put("data", original.dataJson)
                    put("timestamp", original.timestamp)
                }
                database.messageDao().updateContent(
                    original.echoName,
                    original.messageId,
                    dataJson.orEmpty(),
                    JsonArray(versions).toString(),
                    System.currentTimeMillis(),
                )
                return Incoming.Updated
            }
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
        // A message moved to a custom netmail folder must stay there when the
        // node delivers the same id again (for example after a re-sync). ids are
        // only unique inside an echo, so the row is looked up by both.
        val existing = database.messageDao().getByEchoAndId(echoName, messageId)
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
                versionsJson = existing?.versionsJson,
                folderSlug = if (type == "netmail") existing?.folderSlug else null,
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
            rateLimitUsage = parseRateLimits(response["rate_limit"])
            // Persist protocol/message data before acknowledging packet ids (#350):
            // an ACK must never outrun the local database write.
            (response["messages"] as? JsonArray)?.forEach { element ->
                val message = element as? JsonObject ?: return@forEach
                // A rejected message comes back in the response to this very
                // request, so its reason must not be dropped here (#14).
                when (val result = processIncoming(message)) {
                    is Incoming.Error -> pendingErrors += result.text
                    else -> {}
                }
            }
            storeDeliveredIds(response["local_packet_ids"] as? JsonArray)
        }
        return response
    }

    /** Reasons the node rejected messages, once per call (#14). */
    fun drainErrors(): List<String> {
        synchronized(pendingErrors) {
            val drained = pendingErrors.toList()
            pendingErrors.clear()
            return drained
        }
    }

    private fun signMessage(
        type: String,
        dataJson: String,
        echoName: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("timestamp", nowSeconds())
        put("sender_address", profile.address)
        if (!echoName.isNullOrBlank()) put("echo_name", echoName)
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
        val allowRateOverride = allowSendingWithoutRateLimit()
        val counts = rateLimitUsage.mapValues { it.value.used }.toMutableMap()
        for (entry in database.outboxDao().getAll()) {
            val kind = rateLimitKindOfEntry(entry)
            val usage = kind?.let { rateLimitUsage[it] }
            val overLimit = usage != null && usage.limit > 0 &&
                (counts[kind] ?: 0) + 1 > usage.limit
            if (overLimit && !allowRateOverride) {
                database.outboxDao().updateAttempt(
                    entry.id,
                    entry.attempts + 1,
                    "Daily rate limit exceeded (${usage?.used ?: 0}/${usage?.limit ?: 0})",
                )
                continue
            }
            val dataJson = if (overLimit) withRateLimitOverride(entry.dataJson) else entry.dataJson
            val message = try {
                when (entry.type) {
                    "netmail" -> entry.recipientAddress?.let { buildNetmailMessage(it, dataJson) }
                    else -> entry.echoName?.let { signMessage(entry.type, dataJson, echoName = it) }
                }
            } catch (_: Exception) {
                null
            } ?: continue // still no recipient key: keep it queued
            try {
                sendMessages(listOf(message))
                if (entry.type == "netmail") {
                    entry.recipientAddress?.let { recipient ->
                        recordSentNetmail(recipient, dataJson, message.string("jws"))
                    }
                }
                database.outboxDao().delete(entry.id)
                if (kind != null) counts[kind] = (counts[kind] ?: 0) + 1
            } catch (e: Exception) {
                database.outboxDao().updateAttempt(entry.id, entry.attempts + 1, e.message)
            }
        }
    }

    /** Rate-limit kind of a queued entry, matching fm-app's classification. */
    private fun rateLimitKindOfEntry(entry: OutboxEntity): String? {
        val data = runCatching {
            json.parseToJsonElement(entry.dataJson).jsonObject
        }.getOrNull()
        return when (entry.type) {
            "netmail" -> "netmail"
            "echomail" -> if (data?.string("reaction") != null) "reaction" else "echomail"
            "vote", "election" -> when (data?.string("type")) {
                "poster" -> "poster"
                "result" -> null
                else -> "vote"
            }
            else -> null
        }
    }

    private fun withRateLimitOverride(dataJson: String): String {
        val obj = runCatching { json.parseToJsonElement(dataJson).jsonObject }.getOrNull()
            ?: return dataJson
        if (obj["rate_limit_override"] != null) return dataJson
        return JsonObject(
            obj.toMutableMap().apply { put("rate_limit_override", JsonPrimitive(true)) },
        ).toString()
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

    /** Removes ids the node has already accepted in the last request. */
    private suspend fun clearDeliveredIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val done = ids.toSet()
        val left = pendingDeliveredIds().filterNot { it in done }
        if (left.isEmpty()) {
            database.metaDao().delete(META_DELIVERED_IDS)
        } else {
            database.metaDao().put(
                MetaEntity(
                    META_DELIVERED_IDS,
                    buildJsonArray { left.forEach { add(JsonPrimitive(it)) } }.toString(),
                ),
            )
        }
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

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    companion object {
        const val NETMAIL_ECHO = "__netmail__"
        private const val META_DELIVERED_IDS = "pending_delivered_ids"
        private const val META_CLIENT_ID = "client_id"
        private const val META_KNOWN_CLIENTS = "client_sync_known"
        const val CLIENT_LIST_CONTAINER = "client_synchronize"
        const val CLIENT_CONTAINER_PREFIX = "outbound_client_"
        private const val META_ECHO_GROUPS = "echo_groups"
        /** Single-use invite voucher. */
        const val VOUCHER_INVITE = "invite"
        /** Reusable time voucher (admin only, up to 30 days). */
        const val VOUCHER_INVITE_TIME = "invite_time"
        private const val META_PENDING_PUBKEY_REQUESTS = "pending_pubkey_requests"
        private const val META_AUTO_SEND_PUBKEY = "auto_send_pubkey"
        private const val META_TAGLINE = "tagline"
        private const val META_SHOW_TEARLINE = "show_tearline"
        private const val META_SHOW_SERVICE_MESSAGES = "show_service_messages"
        private const val META_ALLOW_WITHOUT_RATE_LIMIT = "allow_sending_without_rate_limit"
        private const val META_IMAGE_MAX_DIMENSION = "image_max_dimension"
        private const val META_AUTO_REQUEST_PUBKEYS = "auto_request_pubkeys"
    }
}
