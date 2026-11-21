package lan.fmnode.android.network

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.AppStateEntity
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.ProfileEntity
import lan.fmnode.android.storage.Vault

/**
 * Manages network profiles (the multi-network core): each network has its own
 * key pair encrypted with the app password, its own address and node endpoint.
 *
 * All databases live in the password-protected [Vault]: profiles and their
 * metadata are only available while the vault is unlocked (#3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkManager(
    private val vault: Vault,
    private val apiFactory: (String) -> FmApiClient = {
        FmApiClient(it, FmHttp.client, FmHttp.sendReceiveClient)
    },
) {
    private val dbFlow = MutableStateFlow(vault.appDatabaseOrNull())

    /** Profiles of the open vault; empty while the vault is locked. */
    val profiles: Flow<List<ProfileEntity>> = dbFlow.flatMapLatest { db ->
        if (db == null) {
            flowOf(emptyList())
        } else {
            db.profileDao().observeAll().catch { emit(emptyList()) }
        }
    }

    fun vaultExists(): Boolean = vault.exists()

    /** Opens the encrypted profile database; false on a wrong password. */
    suspend fun openVault(password: String): Boolean {
        val opened = withContext(Dispatchers.IO) { vault.open(password) }
        if (!opened) return false
        dbFlow.value = vault.appDatabase()
        return true
    }

    /** Creates the vault (first network) with the chosen password. */
    fun createVault(password: String) {
        dbFlow.value = vault.create(password)
    }

    /** Locks the vault: databases are closed and the password is forgotten. */
    fun closeVault() {
        dbFlow.value = null
        vault.close()
    }

    /** Deletes the vault after the last profile is removed. */
    fun clearVault() {
        dbFlow.value = null
        vault.clear()
    }

    private fun db(): AppDatabase = vault.appDatabase()

    suspend fun activeProfileId(): String? = db().appStateDao().get(KEY_ACTIVE_PROFILE)

    suspend fun activeProfile(): ProfileEntity? = activeProfileId()?.let { db().profileDao().getById(it) }

    suspend fun firstProfile(): ProfileEntity? = db().profileDao().getAll().firstOrNull()

    suspend fun profileCount(): Int = db().profileDao().getAll().size

    /** Profiles of the open vault, read on demand (#15 needs all of them). */
    suspend fun profilesFirst(): List<ProfileEntity> = db().profileDao().getAll()

    suspend fun profileById(id: String): ProfileEntity? = db().profileDao().getById(id)

    /** Phase 1 of the policy flow: fetches and verifies the node policy. */
    suspend fun fetchPolicy(invite: Invite): PolicyInfo {
        val api = apiFactory(invite.apiUrl)
        invite.nodePublicKey?.let { api.setNodeEd25519PublicKey(it) }
        return PolicyClient(api).fetchPolicy(userPublicKey = null)
    }

    /**
     * Phase 2: generates the network's own key pair, signs the policy, stores
     * the profile and makes it active. Creates the vault when this is the first
     * network.
     */
    suspend fun register(
        invite: Invite,
        policy: PolicyInfo,
        password: String,
        displayName: String,
        title: String? = null,
        seed: ByteArray? = null,
    ): ProfileEntity {
        if (vault.appDatabaseOrNull() == null) {
            dbFlow.value = withContext(Dispatchers.IO) { vault.openOrCreate(password) }
        }
        val seedBytes = seed ?: CryptoService.generateSeed()
        require(seedBytes.size == 32) { "Ed25519 seed must be 32 bytes" }
        val publicKey = CryptoService.publicKeyFromSeed(seedBytes)

        val api = apiFactory(invite.apiUrl)
        api.setNodeEd25519PublicKey(Encoding.base64Decode(policy.nodeSigPkey))
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(seedBytes)
        val registration = PolicyClient(api).acceptPolicy(
            policy = policy,
            seed = seedBytes,
            publicKey = publicKey,
            name = displayName,
            voucher = invite.voucher,
        )

        val encryptedSeed = KeyVault.encrypt(seedBytes, password)
        val profile = ProfileEntity(
            id = UUID.randomUUID().toString(),
            title = title?.takeIf { it.isNotBlank() } ?: defaultTitle(invite, registration.address),
            appUrl = invite.appUrl,
            apiUrl = invite.apiUrl,
            nodePublicKeyB64 = policy.nodeSigPkey,
            address = registration.address,
            displayName = registration.name,
            isAdmin = registration.isAdmin,
            isModerator = registration.isModerator,
            publicKeyB64 = Encoding.base64Encode(publicKey),
            encryptedSeedB64 = Encoding.base64Encode(encryptedSeed.encrypted),
            seedSaltB64 = Encoding.base64Encode(encryptedSeed.salt),
            seedIvB64 = Encoding.base64Encode(encryptedSeed.iv),
            seedIterations = encryptedSeed.iterations,
            policyJson = policy.payload.toString(),
            createdAt = System.currentTimeMillis(),
            lastSyncAt = null,
        )
        db().profileDao().upsert(profile)
        setActive(profile.id)
        return profile
    }

    /**
     * Adopts an existing identity (keys export / full backup) as a profile
     * without contacting the network: the node address, the display name, the
     * node key and the encrypted seed already come from the file. Creates the
     * vault when this is the first network.
     *
     * A profile with the same public key is updated in place, so importing the
     * same export twice never duplicates the network.
     */
    suspend fun adoptProfile(
        password: String,
        seed: ByteArray,
        address: String,
        displayName: String,
        nodePublicKeyB64: String?,
        appUrl: String,
        apiUrl: String,
        title: String? = null,
    ): ProfileEntity {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        // A profile without a usable endpoint can never sync: fail here, where
        // the message names the field, instead of inside OkHttp later.
        require(InviteParser.parse(apiUrl) != null) {
            "The network URL is required and must start with http:// or https://"
        }
        if (vault.appDatabaseOrNull() == null) {
            dbFlow.value = withContext(Dispatchers.IO) { vault.openOrCreate(password) }
        }
        val publicKey = CryptoService.publicKeyFromSeed(seed)
        val publicKeyB64 = Encoding.base64Encode(publicKey)
        val encryptedSeed = KeyVault.encrypt(seed, password)

        val existing = db().profileDao().getAll().firstOrNull { it.publicKeyB64 == publicKeyB64 }
        val profile = ProfileEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            title = title?.takeIf { it.isNotBlank() }
                ?: existing?.title
                ?: defaultTitleFor(appUrl, address),
            appUrl = appUrl,
            apiUrl = apiUrl,
            nodePublicKeyB64 = nodePublicKeyB64 ?: existing?.nodePublicKeyB64.orEmpty(),
            address = address.ifBlank { existing?.address.orEmpty() },
            displayName = displayName.ifBlank { existing?.displayName.orEmpty() },
            isAdmin = existing?.isAdmin ?: false,
            isModerator = existing?.isModerator ?: false,
            publicKeyB64 = publicKeyB64,
            encryptedSeedB64 = Encoding.base64Encode(encryptedSeed.encrypted),
            seedSaltB64 = Encoding.base64Encode(encryptedSeed.salt),
            seedIvB64 = Encoding.base64Encode(encryptedSeed.iv),
            seedIterations = encryptedSeed.iterations,
            policyJson = existing?.policyJson ?: "{}",
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            lastSyncAt = existing?.lastSyncAt,
        )
        db().profileDao().upsert(profile)
        setActive(profile.id)
        return profile
    }

    /** Stores the last accepted policy payload of a profile (after re-signing). */
    suspend fun updateProfilePolicy(id: String, policyJson: String) {
        val profile = db().profileDao().getById(id) ?: return
        db().profileDao().upsert(profile.copy(policyJson = policyJson))
    }

    /**
     * Stores the address the node reports for a profile (#11): a network merge
     * renumbers users, so the address saved at registration becomes stale.
     */
    suspend fun updateProfileAddress(id: String, address: String) {
        db().profileDao().updateAddress(id, address)
    }

    /** Stores a display name the node accepted through `set_name`. */
    suspend fun updateProfileDisplayName(id: String, name: String) {
        val profile = db().profileDao().getById(id) ?: return
        db().profileDao().upsert(profile.copy(displayName = name))
    }

    /**
     * Stores the roles the node reports. Admin/moderator rights are granted and
     * revoked on the node, so a cached registration response would otherwise
     * keep them forever.
     */
    suspend fun updateProfileRoles(id: String, isAdmin: Boolean, isModerator: Boolean) {
        val profile = db().profileDao().getById(id) ?: return
        if (profile.isAdmin == isAdmin && profile.isModerator == isModerator) return
        db().profileDao().upsert(profile.copy(isAdmin = isAdmin, isModerator = isModerator))
    }

    suspend fun setActive(id: String) {
        db().appStateDao().put(AppStateEntity(KEY_ACTIVE_PROFILE, id))
    }

    /** Removes a profile row and its encrypted message database. */
    suspend fun deleteProfile(id: String) {
        db().profileDao().delete(id)
        withContext(Dispatchers.IO) { vault.deleteProfileDatabase(id) }
        if (activeProfileId() == id) {
            val next = db().profileDao().getAll().firstOrNull()?.id
            if (next != null) setActive(next) else db().appStateDao().delete(KEY_ACTIVE_PROFILE)
        }
    }

    /** Verifies the app password against the first stored profile (vault probe). */
    suspend fun isPasswordValid(password: String): Boolean {
        val probe = db().profileDao().getAll().firstOrNull() ?: return true
        return runCatching { KeyVault.decrypt(probe.encryptedSeed(), password) }.isSuccess
    }

    /** Decrypts the profile's Ed25519 seed (requires the correct password). */
    fun seedOf(profile: ProfileEntity, password: String): ByteArray =
        KeyVault.decrypt(profile.encryptedSeed(), password)


    /**
     * Registers [profile]'s existing identity on a recovery node and switches
     * the profile to that node. The recovery node creates a local recover user
     * (marked read-only outside Emergency) and the profile re-signs its policy.
     */
    suspend fun recover(invite: Invite, profile: ProfileEntity, password: String): PolicyInfo {
        val address = invite.address
        require(!address.isNullOrBlank()) { "Recovery invite has no node address" }
        val seed = seedOf(profile, password)
        val publicKey = CryptoService.publicKeyFromSeed(seed)
        val api = apiFactory(invite.apiUrl)
        invite.nodePublicKey?.let { api.setNodeEd25519PublicKey(it) }
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(seed)

        val policy = PolicyClient(api).fetchPolicy(userPublicKey = publicKey)
        val jwsPayload = buildJsonObject {
            put("address", profile.address)
            put("node_address", address)
        }.toString()
        val jws = CryptoService.createJws(seed, jwsPayload, publicKey)
        val recoverData = buildJsonObject {
            put("address", profile.address)
            put("jws", jws)
            put("pubkey", Encoding.base64Encode(publicKey))
        }.toString()
        api.post("recover", recoverData, ApiKeys(seed, publicKey))

        // Existing user on the recovery node: sign its current policy.
        val registration = PolicyClient(api).acceptPolicy(
            policy = policy,
            seed = seed,
            publicKey = publicKey,
            name = profile.displayName,
        )
        val updated = profile.copy(
            apiUrl = invite.apiUrl,
            appUrl = invite.appUrl,
            nodePublicKeyB64 = policy.nodeSigPkey,
            address = registration.address.ifBlank { profile.address },
            policyJson = policy.payload.toString(),
            lastSyncAt = null,
        )
        db().profileDao().upsert(updated)
        setActive(updated.id)
        return policy
    }

    /**
     * Opens the network session of [profile]: its encrypted database, API
     * client bound to the profile's key pair and the sync engine.
     */
    suspend fun openSession(profile: ProfileEntity, password: String): NetworkSession {
        val seed = seedOf(profile, password)
        val api = apiFactory(profile.apiUrl)
        // A profile adopted from a keys export may have no node key yet (the
        // export need not carry one): the policy response pins it instead.
        // The key must be a real 32-byte Ed25519 key, not merely decodable.
        val nodeKey = runCatching { Encoding.base64Decode(profile.nodePublicKeyB64) }.getOrNull()
        if (nodeKey != null && nodeKey.size == 32) api.setNodeEd25519PublicKey(nodeKey)
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(seed)
        val profileDb = withContext(Dispatchers.IO) { vault.profileDatabase(profile.id) }
        return NetworkSession(profile, profileDb, api, seed)
    }

    private fun defaultTitle(invite: Invite, address: String): String =
        defaultTitleFor(invite.appUrl, address)

    /**
     * Network title from the node endpoint host and the address. The port is
     * kept when it is not the scheme default, otherwise two networks on one
     * host (a test stand, a node next to a front end) look identical (#15).
     */
    private fun defaultTitleFor(appUrl: String, address: String): String {
        val host = appUrl.takeIf { it.isNotBlank() }
            ?.let { url ->
                runCatching { java.net.URI(url) }.getOrNull()?.let { uri ->
                    val name = uri.host ?: url
                    val port = uri.port
                    val isDefault = port == -1 || (port == 80 && uri.scheme == "http") || (port == 443 && uri.scheme == "https")
                    if (isDefault) name else "$name:$port"
                } ?: url
            }
            ?: "Network"
        return if (address.isNotBlank()) "$host ($address)" else host
    }

    companion object {
        const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }
}
