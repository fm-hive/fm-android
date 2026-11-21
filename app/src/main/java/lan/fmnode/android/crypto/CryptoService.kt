package lan.fmnode.android.crypto

import java.math.BigInteger
import java.security.SecureRandom
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519

/**
 * Cryptographic primitives compatible with fm-app (@noble) and fm-go:
 * Ed25519 signatures/JWS, X25519 ECDH (with the Ed25519 -> X25519
 * conversions), ChaCha20-Poly1305 and the custom "JWE" container.
 *
 * Implemented on BouncyCastle (pure JVM) so the same code runs on the device
 * and in unit tests without native libraries.
 */
object CryptoService {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    @Serializable
    private data class JweContainer(
        @SerialName("ephemeral_public") val ephemeralPublic: String,
        val nonce: String,
        val ciphertext: String,
    )

    // ---------------------------------------------------------------- Ed25519

    fun generateSeed(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun publicKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /** kid value: base64url(SHA-256(public key)). */
    fun thumbprint(publicKey: ByteArray): String =
        Encoding.base64UrlEncode(Encoding.sha256(publicKey))

    /**
     * JWS in the fm-app format: header {alg: EdDSA, typ: JWT, kid: thumbprint},
     * payload is the raw payload string; the signature covers
     * `ASCII(base64url(header) + "." + base64url(payload))`.
     */
    fun createJws(seed: ByteArray, payload: String, publicKey: ByteArray): String {
        val header = """{"alg":"EdDSA","typ":"JWT","kid":"${thumbprint(publicKey)}"}"""
        val headerEncoded = Encoding.base64UrlEncode(header.toByteArray(Charsets.UTF_8))
        val payloadEncoded = Encoding.base64UrlEncode(payload.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerEncoded.$payloadEncoded"
        val signature = sign(seed, signingInput.toByteArray(Charsets.US_ASCII))
        return "$signingInput.${Encoding.base64UrlEncode(signature)}"
    }

    fun verifyJws(token: String, publicKey: ByteArray): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        val signingInput = "${parts[0]}.${parts[1]}"
        val signature = Encoding.base64UrlDecode(parts[2])
        return verify(publicKey, signature, signingInput.toByteArray(Charsets.US_ASCII))
    }

    /** Decodes the payload of a JWS without verifying it. */
    fun jwsPayload(token: String): String? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            String(Encoding.base64UrlDecode(parts[1]), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- X25519

    /** Ed25519 private key (seed) -> X25519 private key: SHA-512 + clamp. */
    fun x25519PrivateKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val scalar = Encoding.sha512(seed).copyOfRange(0, 32)
        clampScalar(scalar)
        return scalar
    }

    /**
     * Ed25519 public key -> X25519 public key: u = (1 + y) / (1 - y) mod p,
     * with the y coordinate taken from the little-endian encoding (sign bit
     * cleared).
     */
    fun x25519PublicKeyFromEd25519(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
        val yBytes = publicKey.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = leToBigInteger(yBytes)
        val denominator = BigInteger.ONE.subtract(y).mod(p)
        require(denominator.signum() != 0) { "invalid Ed25519 public key" }
        val u = BigInteger.ONE.add(y).multiply(denominator.modInverse(p)).mod(p)
        return bigIntegerToLe(u, 32)
    }

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == 32 && peerPublicKey.size == 32)
        val scalar = privateKey.copyOf()
        clampScalar(scalar)
        val out = ByteArray(32)
        X25519.scalarMult(scalar, 0, peerPublicKey, 0, out, 0)
        return out
    }

    fun x25519PublicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        val scalar = privateKey.copyOf()
        clampScalar(scalar)
        val out = ByteArray(32)
        X25519.scalarMultBase(scalar, 0, out, 0)
        return out
    }

    // ------------------------------------------------------------------- JWE

    /** Encrypts with a fresh ephemeral key and nonce (fm-app's encryptJWE). */
    fun encryptJwe(plaintext: ByteArray, recipientX25519PublicKey: ByteArray): String {
        val ephemeralSeed = ByteArray(32).also { random.nextBytes(it) }
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        return encryptJweDeterministic(plaintext, recipientX25519PublicKey, ephemeralSeed, nonce)
    }

    /** Deterministic variant used by tests (the wire format is identical). */
    fun encryptJweDeterministic(
        plaintext: ByteArray,
        recipientX25519PublicKey: ByteArray,
        ephemeralSeed: ByteArray,
        nonce: ByteArray,
    ): String {
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val ephemeralPublic = x25519PublicKeyFromPrivate(ephemeralSeed)
        val shared = x25519SharedSecret(ephemeralSeed, recipientX25519PublicKey)
        val key = shared.copyOfRange(0, 32)
        val ciphertext = chachaEncrypt(key, nonce, plaintext)
        val container = JweContainer(
            ephemeralPublic = Encoding.base64UrlEncode(ephemeralPublic),
            nonce = Encoding.base64UrlEncode(nonce),
            ciphertext = Encoding.base64UrlEncode(ciphertext),
        )
        return Encoding.base64UrlEncode(json.encodeToString(JweContainer.serializer(), container).toByteArray(Charsets.UTF_8))
    }

    fun decryptJwe(token: String, recipientX25519PrivateKey: ByteArray): ByteArray {
        val raw = String(Encoding.base64UrlDecode(token), Charsets.UTF_8)
        val container = json.decodeFromString(JweContainer.serializer(), raw)
        val ephemeralPublic = Encoding.base64UrlDecode(container.ephemeralPublic)
        val nonce = Encoding.base64UrlDecode(container.nonce)
        val ciphertext = Encoding.base64UrlDecode(container.ciphertext)
        val shared = x25519SharedSecret(recipientX25519PrivateKey, ephemeralPublic)
        return chachaDecrypt(shared.copyOfRange(0, 32), nonce, ciphertext)
    }

    // --------------------------------------------------------- Envelope auth

    /**
     * Payload signed by the envelope JWS: the hash of the transmitted carrier
     * ({"jwe_sha256": …} or {"data_sha256": …}), never the request itself.
     */
    fun buildEnvelopeAuthPayload(jwe: String?, dataJson: String?): String = when {
        jwe != null -> """{"jwe_sha256":"${Encoding.base64UrlEncode(Encoding.sha256(jwe.toByteArray(Charsets.UTF_8)))}"}"""
        dataJson != null -> """{"data_sha256":"${Encoding.base64UrlEncode(Encoding.sha256(dataJson.toByteArray(Charsets.UTF_8)))}"}"""
        else -> throw IllegalArgumentException("envelope has neither jwe nor data")
    }

    // ---------------------------------------------------------- ChaCha20-Poly1305

    fun chachaEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    fun chachaDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    // ------------------------------------------------------------------ helpers

    private fun clampScalar(scalar: ByteArray) {
        require(scalar.size == 32)
        scalar[0] = (scalar[0].toInt() and 248).toByte()
        scalar[31] = ((scalar[31].toInt() and 127) or 64).toByte()
    }

    private fun leToBigInteger(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size)
        for (i in bytes.indices) be[i] = bytes[bytes.size - 1 - i]
        return BigInteger(1, be)
    }

    private fun bigIntegerToLe(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(be.size, size)) {
            out[i] = be[be.size - 1 - i]
        }
        return out
    }
}
