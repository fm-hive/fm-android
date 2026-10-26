# FM Client (Android)

Multi-network client for FM networks ([fm-go](https://forgejo.lan/lan/fm-go)),
written in Kotlin with Jetpack Compose.

Unlike fm-app (one network per browser profile), every network profile here has
its own Ed25519 key, address, policy and local message store; profiles can be
added by QR/paste and switched from the UI.

## Status

Work in progress. Implemented so far:

- project scaffold (Compose, Material 3, dark/light theme, unit tests);
- crypto compatible with fm-app/fm-go: Ed25519 + JWS (`kid` = key thumbprint),
  Ed25519 → X25519 conversions, ChaCha20-Poly1305 "JWE" container, envelope
  auth payloads (`jwe_sha256`/`data_sha256`) — BouncyCastle, pure JVM, no native
  libraries;
- control-envelope API client (JWE to the node, JWS auth, response decryption);
- two-phase policy registration (invite URLs, vouchers, node-key pinning);
- multi-network profiles (per-network key vault, switching, deletion) and
  keys import/export in the fm-app format;
- per-network Room store and send_receive sync (echomail, netmail, key
  exchange, outbox, delivered-packet acks);
- Security: all local data (profiles, messages, drafts, outbox) lives in a
  password-encrypted SQLCipher vault; logout drops the decrypted state and
  requires the password again.
- UI: networks/unlock/add-network (paste or QR scan), echo conferences with
  subscribe switches, conversation with markdown + emoji + attachments,
  reactions, netmail with custom folders/detail/composer, local blogs with
  posts/attachments/edit/delete, search, protocols screen (vote/election
  voting, refill responses), outbox, settings (invite QR, vouchers, keys and
  settings import/export, full fm-app v2 backups), admin (nodelist, merge
  initiation, excommunicate proposals);
- client_synchronize multi-device sync including local blog operations;
  automatic policy re-sign; composer drafts; netmail contacts; forwarding
  (single and multi-select); message edit history (plain updates are folded
  into the original);
- all fm-app translations generated into fm_strings.xml;
- 65 unit tests (crypto vectors, policy, sync, DAO, backups, markdown, i18n
  parity).

## Backup compatibility

- `fm-app` **keys** exports (encrypted and unencrypted) import into the
  add-network flow.
- `fm_settings` v1.0 export/import is supported.
- Full `fm-app` v2.0 backups include settings, keys, subscriptions, echoes,
  netmail folders/messages, blogs/posts and learned sender names. Import targets
  the active network profile; for a new device add the network by invite first,
  then import the full backup. The export of the active profile is accepted by
  `fm-app`.

## Build

Requirements: JDK 21 and the Android SDK platform 36.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # not committed
./gradlew :app:assembleDebug                          # build
./gradlew :app:testDebugUnitTest                      # unit tests
```

Open the folder in Android Studio and run *Sync Project with Gradle Files* to
get code navigation and Compose previews.

## Crypto vectors

`app/src/test/resources/crypto-vectors.json` is generated from fm-app's
`@noble` implementation with `tools/gen-vectors.mjs` and verified byte-for-byte
by `CryptoVectorsTest` (Ed25519 keys/thumbprint, JWS, X25519 conversions, JWE,
envelope hashes).
