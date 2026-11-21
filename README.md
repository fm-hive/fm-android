# FM Client (Android)

Multi-network client for FM networks ([fm-node](https://github.org/fm-hive/fm-node)),
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
  initiation, excommunicate proposals), announcements, appeals of moderation
  actions, remote channel subscriptions;
- client_synchronize multi-device sync including local blog operations;
  automatic policy re-sign; composer drafts; netmail contacts; forwarding
  (single and multi-select); message edit history (plain updates are folded
  into the original);
- all fm-app translations generated into fm_strings.xml;
- unit tests (crypto vectors, policy, sync, DAO, backups, markdown, i18n
  parity).

## Backup compatibility

- `fm-app` **keys** exports (encrypted and unencrypted) import into the
  add-network flow.
- `fm_settings` v1.0 export/import is supported.
- Full `fm-app` v2.0 backups include settings, keys, subscriptions, echoes,
  netmail folders/messages, blogs/posts and learned sender names. The exported
  file carries `appUrl`, so it is self-sufficient on a new device: import it in
  the add-network flow without typing the URL. It is accepted by `fm-app` and
  by `fmclient settings import` (the Android export has the same shape as an
  fm-app full export).

## CI/CD

Forgejo Actions workflow `.forgejo/workflows/android.yml` runs on pull requests
to `main`, pushes to `main` and `v*` tags:

- runs unit tests before any APK build;
- builds debug and release APKs with `minSdk 26` (Android 8+) and checks the
  manifest with `aapt`;
- uploads `fm-android-api26-debug.apk`, `fm-android-api26-release.apk` and
  checksums as a workflow artifact;
- attaches the artifacts to the Forgejo release when a `v*` tag is pushed.

`minSdk` is API 26; `targetSdk`/`compileSdk` stay current at 36. Release builds
are signed with the debug key when release signing secrets are absent. Configure
these repository secrets for a production keystore:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD` and `FORGEJO_TOKEN`.

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
