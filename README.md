# Dialog Auth

Server-side registration and login for Fabric Minecraft 1.21.6, with an optional standalone client companion for encrypted offline authentication and signed modpack synchronization.

Version 1.5.2 includes an early companion gate before Fabric registry synchronization and a standalone universal companion. The feature/next-auth work adds explicit client-mod allowlists, signed ready/manual distributions, confirmation before any mods/ writes, per-server trust profiles and an optional custom companion authentication screen. Earlier releases added hybrid Microsoft/offline authentication, one-time-code password resets and the HTTPS website.

## Features

- Vanilla registration/login dialogs during the configuration phase; players do not enter the world before authentication.
- Optional hybrid mode: verified Microsoft profiles use vanilla encrypted login and skip the server password, while offline profiles use the companion flow.
- UUID-based accounts, PBKDF2-HMAC-SHA256 with 210,000 iterations and per-account salts.
- Atomic `accounts.json` writes with owner-only permissions.
- One-time password-reset codes stored as PBKDF2 hashes, 15-minute expiry, five failed attempts, and an operator `/dialogauth resetcode <username>` command.
- Localhost-only reset API with a bearer token; the included Python website serves downloads and a reset form without JavaScript.
- Shared per-player/IP rate limits, bounded background workers, timeouts and replay-safe session state.
- Offline mode support with canonical usernames and password-gated login for existing online UUID accounts.
- Companion-only encrypted password responses in offline mode: signed ephemeral X25519 handshake, Ed25519 server identity, HKDF-SHA256, AES-GCM and response sequence numbers.
- Signed modpack manifest and SHA-256 verification for every downloaded JAR, staging, backups and restart-on-change.
- Versioned-filename cleanup on sync: the companion moves superseded versions of managed mod ids into `.dialogauth-backup`, preventing duplicate mods.
- Configurable ready client distribution built only from explicit required/optional mod ids and an external universal companion JAR.
- Manifest format 2 records server id, versions, required/optional status, size, SHA-256, homepage and sources for every mod.
- Confirmation screen shows install/update/removal plans, old→new versions and signing-key fingerprints before any `mods/` write.
- Per-server profiles under `config/dialogauth/servers/<server-id>/` keep addresses, auth identity and modpack keys separate; legacy global keys migrate without silently accepting replacements.
- Optional `auth-ui=companion` provides a versioned custom Screen with encrypted responses; `auth-ui=vanilla` preserves the NoticeDialog.
- Missing-companion and plaintext-response messaging with a configurable download link.
- Early companion detection during `BEFORE_CONFIGURE`, before Fabric queues registry synchronization, so required-companion clients get the download message instead of a misleading registry error.

## Requirements

- Minecraft Java Edition 1.21.6
- Fabric Loader 0.17.3 or newer
- Fabric API 0.128.2+1.21.6 or newer
- Java 21

## Artifacts

- `dialog-auth-1.5.2.jar` — server mod; it does not embed or require a companion resource at runtime.
- `dialog-auth-companion-1.5.2.jar` — standalone client-only companion built from `src/companion` plus the shared protocol/crypto/accessor classes it needs. It has no `dialogauth` dependency.
- `dialog-auth-client.zip` — optional ready kit generated from the configured allowlist. It contains ready mod JARs, `INSTALL.txt`, `MANUAL-INSTALL.txt` and only the public per-server client profile. No server JAR, private keys, accounts, server config or worlds are included.
- `manifest.json`, `manifest.sig`, `modpack-public.key` and `mods/` — signed distribution files for later synchronization.

## Build

```bash
./gradlew clean build
```

Gradle compiles both source sets and packages the independent server and companion JARs into `build/libs/`. `verifyCompanionStandalone` checks that shared classes are present and server-only `AuthTask` is absent. Runtime distribution uses an external ready companion selected by `client-companion-jar`; the server never compiles mods at runtime.

To also produce a signed manifest for subsequent (update) synchronization:

```bash
./gradlew clean build generateModpack
```

Generated files:

- `build/modpack/` — the companion artifact and `manifest.json`; the server mod is not distributed to clients.

Generate a signing key pair:

```bash
java -cp build/classes/java/companion ru.ldpe.dialogauth.client.ModpackSigner generate private.key public.key
```

Sign the generated manifest:

```bash
./gradlew signModpack -PmodpackSigningKey=/absolute/path/private.key
```

Keep private keys outside the repository. The modpack HTTP directory should contain only `manifest.json`, `manifest.sig`, `modpack-public.key`, `mods/` and, for the initial kit, `client/`.

## Initial client installation (Prism)

1. Enable `client-distribution-enabled` only after configuring an external companion, allowlists, stable `server-id` and signing-key paths.
2. Download the ready ZIP or use the website's manual report with exact versions, project links and SHA-256. Unpack into the Prism/Pojav instance root.
3. Restart the game after every mod change. In-game updates show a confirmation plan before writing to `mods/`.

The initial kit bootstraps a fresh instance. Later updates verify the manifest signature and every JAR hash, use staging, move superseded managed versions into `.dialogauth-backup`, and require a restart.

### Upgrading from 1.3.0

Delete `dialog-auth-1.3.0.jar` and `dialog-auth-companion-1.3.0.jar` once before installing a newer kit; that companion cannot clean up versioned files itself. From 1.4.0 onward, sync handles managed stale versions automatically.

### Upgrading from 1.5.0

Delete the server-side `dialog-auth-1.5.0.jar` from the client instance's `mods/` directory. Version 1.5.0 incorrectly required it through companion metadata; 1.5.2 is self-contained and the client kit never includes the server JAR.

## Configuration

Server `config/dialogauth/config.properties`:

```properties
allow-offline-mode=true
require-encrypted-auth=true
auth-ui=vanilla
client-distribution-enabled=false
client-companion-jar=/srv/shared/dialog-auth-companion-1.5.2.jar
client-required-mods=fabric-api,trmt,cloth-config,dialogauth-companion
client-optional-mods=voicechat
server-id=gang
client-kit-path=/opt/modpack/client
client-download-url=https://gang.ldp-e.ru/
client-modpack-manifest-url=https://gang.ldp-e.ru/downloads/manifest.json
client-modpack-servers=gang.ldp-e.ru:25565
modpack-private-key=/srv/private/modpack-private.key
modpack-public-key=/srv/private/modpack-public.key
# Hybrid mode requires online-mode=true in server.properties.
microsoft-auth=true
microsoft-profile-url=https://api.mojang.com/users/profiles/minecraft/
reset-api-enabled=true
reset-api-port=25578
reset-api-token=replace-with-a-long-random-token
reset-code-ttl-minutes=15
```

- `client-distribution-enabled=false` disables ZIP/manifest generation without disabling `client-download-url`.
- `auth-ui=vanilla` preserves the current dialog. `auth-ui=companion` requires the early companion gate and uses the encrypted custom Screen even when encrypted offline auth is not otherwise required.
- Only explicitly listed mod ids are packaged. Missing required JARs, unknown ids, unsafe names, server-only `dialogauth`, invalid UI/server-id values and missing key/companion configuration fail startup with a clear error.
- `client-download-url` remains active independently of distribution and should lead to the instruction page.
- `microsoft-auth` works only with `online-mode=true`. Mojang profile/UUID matches enter vanilla Microsoft encryption; other profiles use the offline companion flow. Lookup failures fail closed.
- `reset-api-enabled` starts the reset API only on `127.0.0.1`; never expose that port publicly.

For an offline-only server, set `online-mode=false` and `microsoft-auth=false`. In hybrid mode, keep `online-mode=true`; offline profiles still require the companion.

Legacy client `modpack.properties` (`url`/`servers`) is migrated into per-server profiles. New kits use `config/dialogauth/servers/<server-id>/server.properties` with public `addresses` and `manifestUrl`. Auth identity and modpack signing keys remain separate. First modpack trust and key changes require confirmation with fingerprints; two servers no longer share a global pinned key.

## Download and reset website

The `website/` directory contains a dependency-free Python backend, accessible HTML forms, tests and deployment examples. The download page includes ready ZIP/manual choices, manifest metadata, hashes, project links, the signing-key fingerprint and Prism/Pojav instructions. A missing manual manifest does not disable downloads or reset. The production site is `https://gang.ldp-e.ru/`; the reset form is `https://gang.ldp-e.ru/reset`. Caddy proxies downloads and the local backend; the reset bearer token stays in a restricted configuration and the backend applies a five-attempt/15-minute IP limit.

An operator issues a reset code in game or from the console:

```text
/dialogauth resetcode PlayerName
```

The player enters that six-digit code and a new password on the reset page. Codes are PBKDF2-hashed at rest, expire after 15 minutes, allow five failures, and are deleted after a successful reset. Run website tests with `cd website && python3 -m unittest -v test_server.py`.

## Security notes

Offline profiles do not receive Microsoft verification. Any unregistered offline name can be claimed by its first registrant. Use unique passwords and do not reuse credentials from other services. Modpack installation executes Java code on the next restart, so publish only signed manifests and review updates.

Serve the initial kit and manifest over HTTPS. Subsequent sync also verifies the manifest signature and every JAR's SHA-256. Never commit reset tokens, reset-code files, account stores, signing keys or server identity keys.

## License

CC0-1.0. See `LICENSE`.
