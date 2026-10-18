package lan.fmnode.android.network

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import lan.fmnode.android.crypto.CryptoService
import lan.fmnode.android.crypto.Encoding
import lan.fmnode.android.storage.AppStateEntity
import lan.fmnode.android.storage.AppDatabase
import lan.fmnode.android.storage.KeyVault
import lan.fmnode.android.storage.ProfileDatabase
import lan.fmnode.android.storage.ProfileEntity

/**
 * Manages network profiles (the multi-network core): each network has its own
 * key pair encrypted with the app password, its own address and node endpoint.
 */
class NetworkManager(
    private val db: AppDatabase,
    private val profileDatabases: (String) -> ProfileDatabase = {
        throw IllegalStateException("profile databases are not configured")
    },
    private val apiFactory: (String) -> FmApiClient = { FmApiClient(it) },
) {
    val profiles: Flow<List<ProfileEntity>> = db.profileDao().observeAll()

    suspend fun activeProfileId(): String? = db.appStateDao().get(KEY_ACTIVE_PROFILE)

    suspend fun activeProfile(): ProfileEntity? = activeProfileId()?.let { db.profileDao().getById(it) }

    /** Phase 1 of the policy flow: fetches and verifies the node policy. */
    suspend fun fetchPolicy(invite: Invite): PolicyInfo {
        val api = apiFactory(invite.apiUrl)
        invite.nodePublicKey?.let { api.setNodeEd25519PublicKey(it) }
        return PolicyClient(api).fetchPolicy(userPublicKey = null)
    }

    /**
     * Phase 2: generates the network's own key pair, signs the policy, stores
     * the profile and makes it active.
     */
    suspend fun register(
        invite: Invite,
        policy: PolicyInfo,
        password: String,
        displayName: String,
        title: String? = null,
        seed: ByteArray? = null,
    ): ProfileEntity {
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
        db.profileDao().upsert(profile)
        setActive(profile.id)
        return profile
    }

    suspend fun setActive(id: String) {
        db.appStateDao().put(AppStateEntity(KEY_ACTIVE_PROFILE, id))
    }

    suspend fun deleteProfile(id: String) {
        db.profileDao().delete(id)
        if (activeProfileId() == id) {
            val next = db.profileDao().getAll().firstOrNull()?.id
            if (next != null) setActive(next) else db.appStateDao().delete(KEY_ACTIVE_PROFILE)
        }
    }

    /** Verifies the app password against the first stored profile (vault probe). */
    suspend fun isPasswordValid(password: String): Boolean {
        val probe = db.profileDao().getAll().firstOrNull() ?: return true
        return runCatching { KeyVault.decrypt(probe.encryptedSeed(), password) }.isSuccess
    }

    /** Decrypts the profile's Ed25519 seed (requires the correct password). */
    fun seedOf(profile: ProfileEntity, password: String): ByteArray =
        KeyVault.decrypt(profile.encryptedSeed(), password)

    /**
     * Opens the network session of [profile]: its own database, API client
     * bound to the profile's key pair and the sync engine.
     */
    suspend fun openSession(profile: ProfileEntity, password: String): NetworkSession {
        val seed = seedOf(profile, password)
        val api = apiFactory(profile.apiUrl)
        api.setNodeEd25519PublicKey(Encoding.base64Decode(profile.nodePublicKeyB64))
        api.responseDecryptionKey = CryptoService.x25519PrivateKeyFromSeed(seed)
        return NetworkSession(profile, profileDatabases(profile.id), api, seed)
    }

    private fun defaultTitle(invite: Invite, address: String): String {
        val host = runCatching { java.net.URI(invite.appUrl).host }.getOrNull() ?: invite.appUrl
        return if (address.isNotBlank()) "$host ($address)" else host
    }

    companion object {
        const val KEY_ACTIVE_PROFILE = "active_profile_id"
    }
}
