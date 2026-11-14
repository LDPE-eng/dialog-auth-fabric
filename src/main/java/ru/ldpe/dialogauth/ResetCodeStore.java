package ru.ldpe.dialogauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ResetCodeStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path file;
    private final Duration ttl;
    private final Clock clock;
    private Map<UUID, ResetCode> codes = Map.of();

    public ResetCodeStore(Path file, Duration ttl, Clock clock) {
        this.file = file.toAbsolutePath();
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular reset-code file");
        try {
            ResetData data = GSON.fromJson(Files.readString(file), ResetData.class);
            if (data == null || data.formatVersion != 1 || data.codes == null) {
                throw new IOException("Invalid reset-code store format");
            }
            Map<UUID, ResetCode> loaded = new HashMap<>();
            for (var entry : data.codes.entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey());
                ResetCode code = entry.getValue();
                if (code == null || code.salt == null || code.hash == null || code.expiresAt == null
                        || code.iterations < 100000 || code.iterations > 1000000 || code.attempts < 0) {
                    throw new IOException("Invalid reset-code record for " + uuid);
                }
                loaded.put(uuid, code);
            }
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            codes = Map.copyOf(loaded);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid reset-code store", e);
        }
    }

    public synchronized String issue(UUID uuid) throws IOException, GeneralSecurityException {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        var encoded = PasswordHasher.hash(code.toCharArray());
        codes = new HashMap<>(codes);
        codes.put(uuid, new ResetCode(encoded.saltBase64(), encoded.hashBase64(), encoded.iterations(),
                Instant.now(clock).plus(ttl).toString(), 0));
        commit();
        return code;
    }

    public synchronized boolean reset(AccountStore accounts, UUID uuid, String code, char[] newPassword)
            throws IOException, GeneralSecurityException {
        if (newPassword.length < 8 || newPassword.length > 128 || code == null || !code.matches("[0-9]{6}")) {
            return false;
        }
        ResetCode current = codes.get(uuid);
        if (current == null) return false;
        if (current.attempts >= 5 || Instant.parse(current.expiresAt).isBefore(Instant.now(clock))) {
            remove(uuid);
            return false;
        }
        boolean valid = PasswordHasher.verify(code.toCharArray(),
                new PasswordHasher.EncodedPassword(current.salt, current.hash, current.iterations));
        if (!valid) {
            codes = new HashMap<>(codes);
            codes.put(uuid, new ResetCode(current.salt, current.hash, current.iterations, current.expiresAt,
                    current.attempts + 1));
            commit();
            return false;
        }
        if (!accounts.resetPassword(uuid, newPassword)) return false;
        remove(uuid);
        return true;
    }

    private void remove(UUID uuid) throws IOException {
        if (!codes.containsKey(uuid)) return;
        codes = new HashMap<>(codes);
        codes.remove(uuid);
        commit();
    }

    private void commit() throws IOException {
        Map<String, ResetCode> serialized = new HashMap<>();
        codes.forEach((uuid, code) -> serialized.put(uuid.toString(), code));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "reset-codes-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, GSON.toJson(new ResetData(1, serialized)));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record ResetData(int formatVersion, Map<String, ResetCode> codes) {}
    private record ResetCode(String salt, String hash, int iterations, String expiresAt, int attempts) {}
}
