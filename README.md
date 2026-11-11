# Dialog Auth

Server-side registration and login for Fabric Minecraft 1.21.6, with an optional client companion for encrypted offline authentication and signed modpack synchronization.

## Features

- Vanilla registration/login dialogs during the configuration phase; players do not enter the world before authentication.
- UUID-based accounts, PBKDF2-HMAC-SHA256 with 210,000 iterations and per-account salts.
- Atomic `accounts.json` writes with owner-only permissions.
- Shared per-player/IP rate limits, bounded background workers, timeouts and replay-safe session state.
- Offline mode support with canonical usernames and password-gated login for existing online UUID accounts.
- Companion-only encrypted password responses in offline mode: signed ephemeral X25519 handshake, Ed25519 server identity, HKDF-SHA256, AES-GCM and response sequence numbers.
- Signed modpack manifest and SHA-256 verification for every downloaded JAR, staging, backups and restart-on-change.
- Separate server and client companion artifacts.

## Requirements

- Minecraft Java Edition 1.21.6
- Fabric Loader 0.16.14 or newer
- Fabric API 0.128.2+1.21.6 or newer
- Java 21

## Artifacts

- `dialog-auth-1.3.0.jar` — server mod and shared protocol code. Also required on the client when the companion is used.
- `dialog-auth-companion-1.3.0.jar` — client-only encrypted authentication and modpack synchronization.

## Build

```bash
./gradlew clean build generateModpack
```

Generated files:

- `build/libs/` — remapped server and companion JARs.
- `build/modpack/` — copied artifacts and `manifest.json`.

Generate a signing key pair:

```bash
java -cp build/classes/java/companion ru.ldpe.dialogauth.client.ModpackSigner generate private.key public.key
```

Sign the generated manifest:

```bash
./gradlew signModpack -PmodpackSigningKey=/absolute/path/private.key
```

Keep private keys outside the repository. The modpack HTTP directory should contain only `manifest.json`, `manifest.sig`, `modpack-public.key` and `mods/`.

## Configuration

Server `config/dialogauth/config.properties`:

```properties
allow-offline-mode=true
require-encrypted-auth=true
```

For offline clients, set `online-mode=false` in `server.properties`. Keep `require-encrypted-auth=true` unless you explicitly accept plaintext password responses. With `online-mode=true`, the vanilla encrypted login path is used and the companion is optional.

Client `config/dialogauth/modpack.properties`:

```properties
url=http://217.144.184.4:25577/manifest.json
servers=217.144.184.4:25565
```

The first server identity and modpack signing key are pinned on first use. Share the server fingerprint with players through a separate trusted channel so they can verify it.

## Security notes

Offline mode does not verify Microsoft usernames. Any unregistered name can be claimed by the first player who registers it. Use unique passwords and do not reuse credentials from other services. Modpack installation executes Java code on the next restart, so publish only signed manifests and review mod updates.

## License

CC0-1.0. See `LICENSE`.
