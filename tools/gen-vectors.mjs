// Generates deterministic crypto golden vectors from the same @noble
// primitives fm-app uses, so the Kotlin implementation can be verified
// byte-for-byte in JVM unit tests.
//
// Usage (from this repository root):
//   ln -s ../../fm-app/node_modules tools/node_modules   # if not present
//   node tools/gen-vectors.mjs [output.json]
//
// The output defaults to app/src/test/resources/crypto-vectors.json.
import * as ed from '@noble/ed25519'
import { x25519, ed25519 as edCurve } from '@noble/curves/ed25519.js'
import { sha256, sha512 } from '@noble/hashes/sha2.js'
import { chacha20poly1305 } from '@noble/ciphers/chacha.js'
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const hex = (b) => Array.from(b).map((x) => x.toString(16).padStart(2, '0')).join('')
const b64url = (b) => Buffer.from(b).toString('base64url')

function seeded(fill) {
  return new Uint8Array(32).fill(fill)
}

async function main() {
  // ---- Ed25519 keys (noble v3: secretKey is the 32-byte seed) ----
  const seed = seeded(0x11)
  const publicKey = await ed.getPublicKeyAsync(seed)
  const thumbprint = b64url(sha256(publicKey))

  // ---- JWS (header.payload.signature, payload = raw string) ----
  const payload = JSON.stringify({ id: 'golden', policy: 'test policy', data: 'vector' })
  const header = { alg: 'EdDSA', typ: 'JWT', kid: thumbprint }
  const headerEncoded = b64url(new TextEncoder().encode(JSON.stringify(header)))
  const payloadEncoded = b64url(new TextEncoder().encode(payload))
  const signingInput = `${headerEncoded}.${payloadEncoded}`
  const signature = await ed.signAsync(new TextEncoder().encode(signingInput), seed)
  const jws = `${signingInput}.${b64url(signature)}`

  // ---- X25519 conversions (noble's toMontgomery helpers) ----
  const recipientSeed = seeded(0x22)
  const recipientEdPub = await ed.getPublicKeyAsync(recipientSeed)
  const recipientXPriv = edCurve.utils.toMontgomerySecret(recipientSeed)
  const recipientXPub = edCurve.utils.toMontgomery(recipientEdPub)

  // ---- JWE (X25519 ECDH + ChaCha20-Poly1305, fm-app encoding) ----
  const ephemeralSeed = seeded(0x33)
  const ephemeral = x25519.keygen(ephemeralSeed)
  const shared = x25519.getSharedSecret(ephemeral.secretKey, recipientXPub)
  const key = shared.slice(0, 32)
  const nonce = seeded(0x44).slice(0, 12)
  const plaintext = '{"request":"policy","data":{"pubkey":"AAECAw=="}}'
  const ciphertext = chacha20poly1305(key, nonce).encrypt(new TextEncoder().encode(plaintext))
  const jweJson = JSON.stringify({
    ephemeral_public: b64url(ephemeral.publicKey),
    nonce: b64url(nonce),
    ciphertext: b64url(ciphertext),
  })
  const jwe = b64url(new TextEncoder().encode(jweJson))

  // ---- Envelope auth payloads (envelope.js) ----
  const jweSha256 = b64url(sha256(new TextEncoder().encode(jwe)))
  const dataJson = JSON.stringify({ request: 'policy', data: { pubkey: 'AAECAw==' } })
  const dataSha256 = b64url(sha256(new TextEncoder().encode(dataJson)))

  // ---- Ed25519 private key -> X25519 private key via SHA-512 + clamp ----
  const hash = sha512(seed)
  const clamped = hash.slice(0, 32)
  clamped[0] &= 248
  clamped[31] &= 127
  clamped[31] |= 64

  const vectors = {
    seed: hex(seed),
    public_key: hex(publicKey),
    thumbprint,
    x25519_public_key: hex(edCurve.utils.toMontgomery(publicKey)),
    x25519_private_key: hex(clamped),
    jws: { payload, token: jws },
    jwe: {
      recipient_seed: hex(recipientSeed),
      recipient_ed_public_key: hex(recipientEdPub),
      recipient_x_public_key: hex(recipientXPub),
      recipient_x_private_key: hex(recipientXPriv),
      ephemeral_seed: hex(ephemeralSeed),
      ephemeral_public_key: hex(ephemeral.publicKey),
      nonce: hex(nonce),
      plaintext,
      token: jwe,
    },
    envelope: {
      jwe_sha256: jweSha256,
      data_json: dataJson,
      data_sha256: dataSha256,
    },
  }

  const out = process.argv[2] || 'app/src/test/resources/crypto-vectors.json'
  mkdirSync(dirname(out), { recursive: true })
  writeFileSync(out, JSON.stringify(vectors, null, 2) + '\n')
  console.log(`wrote ${out}`)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
