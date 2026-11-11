package ru.ldpe.dialogauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class AccountStore implements AuthBackend {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private volatile Map<UUID, AccountRecord> accounts = Map.of();

    public AccountStore(Path file) {
        this.file = file.toAbsolutePath();
    }

    public synchronized void load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular account file");
        try {
            AccountData data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AccountData.class);
            if (data == null || data.formatVersion != 1 || data.accounts == null) {
                throw new IOException("Invalid account store format");
            }
            Map<UUID, AccountRecord> loaded = new HashMap<>();
            for (var entry : data.accounts.entrySet()) {
                UUID uuid = UUID.fromString(entry.getKey());
                AccountRecord record = entry.getValue();
                if (record == null || record.username == null || record.salt == null || record.hash == null
                        || record.iterations < 100000 || record.iterations > 1000000
                        || Base64.getDecoder().decode(record.salt).length != 16
                        || Base64.getDecoder().decode(record.hash).length != 32) {
                    throw new IOException("Invalid account record for " + uuid);
                }
                loaded.put(uuid, record);
            }
            restrict(file);
            accounts = Map.copyOf(loaded);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid account store", e);
        }
    }

    @Override
    public boolean exists(UUID uuid) {
        return accounts.containsKey(uuid);
    }

    @Override
    public boolean exists(UUID uuid, String username, boolean offlineMode) {
        return exists(uuid) || offlineMode && hasUsername(username);
    }

    @Override
    public void register(UUID uuid, String username, char[] password, BooleanSupplier active)
            throws IOException, GeneralSecurityException {
        register(uuid, username, password, false, active);
    }

    @Override
    public void register(UUID uuid, String username, char[] password, boolean offlineMode,
                         BooleanSupplier active) throws IOException, GeneralSecurityException {
        if (exists(uuid, username, offlineMode)) throw new IllegalStateException("Account already registered");
        var encoded = PasswordHasher.hash(password);
        String now = Instant.now().toString();
        AccountRecord record = new AccountRecord(username, encoded.saltBase64(), encoded.hashBase64(),
                encoded.iterations(), now, now);
        synchronized (this) {
            if (!active.getAsBoolean()) return;
            if (exists(uuid, username, offlineMode)) throw new IllegalStateException("Account already registered");
            commit(uuid, record);
        }
    }

    @Override
    public boolean verify(UUID uuid, String username, char[] password, BooleanSupplier active)
            throws IOException, GeneralSecurityException {
        return verify(uuid, username, password, false, active);
    }

    @Override
    public boolean verify(UUID uuid, String username, char[] password, boolean offlineMode,
                          BooleanSupplier active) throws IOException, GeneralSecurityException {
        UUID accountId = uuid;
        AccountRecord record = accounts.get(accountId);
        boolean nameFallback = false;
        if (record == null && offlineMode) {
            var match = findByUsername(username);
            if (match == null) return false;
            accountId = match.getKey();
            record = match.getValue();
            nameFallback = true;
        }
        if (record == null || !PasswordHasher.verify(password,
                new PasswordHasher.EncodedPassword(record.salt, record.hash, record.iterations))) return false;
        synchronized (this) {
            if (!active.getAsBoolean()) return false;
            commit(accountId, new AccountRecord(nameFallback ? record.username : username,
                    record.salt, record.hash, record.iterations, record.createdAt, Instant.now().toString()));
        }
        return true;
    }

    private boolean hasUsername(String username) {
        String canonical = canonical(username);
        return accounts.values().stream().anyMatch(record -> canonical(record.username).equals(canonical));
    }

    private Map.Entry<UUID, AccountRecord> findByUsername(String username) throws IOException {
        String canonical = canonical(username);
        Map.Entry<UUID, AccountRecord> match = null;
        for (var entry : accounts.entrySet()) {
            if (!canonical(entry.getValue().username).equals(canonical)) continue;
            if (match != null) throw new IOException("Ambiguous duplicate account name: " + username);
            match = entry;
        }
        return match;
    }

    private static String canonical(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void commit(UUID uuid, AccountRecord record) throws IOException {
        Map<UUID, AccountRecord> updated = new HashMap<>(accounts);
        updated.put(uuid, record);
        Map<String, AccountRecord> serialized = new HashMap<>();
        updated.forEach((id, value) -> serialized.put(id.toString(), value));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "accounts-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            byte[] json = GSON.toJson(new AccountData(1, serialized)).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            accounts = Map.copyOf(updated);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void restrict(Path path) throws IOException {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private record AccountData(int formatVersion, Map<String, AccountRecord> accounts) {}
    private record AccountRecord(String username, String salt, String hash, int iterations,
                                 String createdAt, String lastLogin) {}
}
