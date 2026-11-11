package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {
    @TempDir Path directory;
    final UUID uuid = UUID.randomUUID();

    @Test void registerReloadVerifyAndRenamePreserveFormatAndUuid() throws Exception {
        Path file = directory.resolve("accounts.json");
        AccountStore store = new AccountStore(file);
        store.load();
        assertFalse(store.exists(uuid));
        store.register(uuid, "Alice", "secret123".toCharArray(), () -> true);
        var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(1, root.get("formatVersion").getAsInt());
        var account = root.getAsJsonObject("accounts").getAsJsonObject(uuid.toString());
        assertEquals(210000, account.get("iterations").getAsInt());
        assertEquals("Alice", account.get("username").getAsString());
        assertNotNull(account.get("createdAt"));
        assertNotNull(account.get("lastLogin"));
        assertFalse(Files.readString(file).contains("secret123"));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        AccountStore reloaded = new AccountStore(file);
        reloaded.load();
        assertFalse(reloaded.verify(uuid, "Alice", "incorrect".toCharArray(), () -> true));
        assertTrue(reloaded.verify(uuid, "Renamed", "secret123".toCharArray(), () -> true));
        assertTrue(Files.readString(file).contains("Renamed"));
        assertThrows(IllegalStateException.class,
                () -> reloaded.register(uuid, "Alice", "secret123".toCharArray(), () -> true));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void loadOriginalJsonLayout() throws Exception {
        var encoded = PasswordHasher.hash("password".toCharArray());
        Path file = directory.resolve("accounts.json");
        Files.writeString(file, """
                {"formatVersion":1,"accounts":{"%s":{"username":"Original","salt":"%s",
                "hash":"%s","iterations":210000,"createdAt":"2025-01-01T00:00:00Z","lastLogin":"2025-01-01T00:00:00Z"}}}
                """.formatted(uuid, encoded.saltBase64(), encoded.hashBase64()));
        AccountStore store = new AccountStore(file);
        store.load();
        assertTrue(store.verify(uuid, "Original", "password".toCharArray(), () -> true));
    }

    @Test void cancelledRegistrationDoesNotPersist() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        store.register(uuid, "Alice", "secret123".toCharArray(), () -> false);
        assertFalse(store.exists(uuid));
        assertFalse(Files.exists(directory.resolve("accounts.json")));
    }

    @Test void failedAtomicMoveRollsBackMemoryAndRemovesTemporary() throws Exception {
        Path target = directory.resolve("accounts.json");
        Files.createDirectory(target);
        Files.writeString(target.resolve("block"), "nonempty");
        AccountStore store = new AccountStore(target);
        assertThrows(IOException.class, () -> store.register(uuid, "Alice", "secret123".toCharArray(), () -> true));
        assertFalse(store.exists(uuid));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test void rejectsMalformedAndUnsupportedStores() throws Exception {
        Path file = directory.resolve("accounts.json");
        for (String json : new String[]{"null", "{", "{}", "{\"formatVersion\":2,\"accounts\":{}}",
                "{\"formatVersion\":1,\"accounts\":{\"not-uuid\":{}}}"}) {
            Files.writeString(file, json);
            assertThrows(IOException.class, () -> new AccountStore(file).load(), json);
        }
    }

    @Test void hashesHaveIndependentSaltsAndRejectWrongPassword() throws Exception {
        var first = PasswordHasher.hash("password".toCharArray());
        var second = PasswordHasher.hash("password".toCharArray());
        assertNotEquals(first.saltBase64(), second.saltBase64());
        assertTrue(PasswordHasher.verify("password".toCharArray(), first));
        assertFalse(PasswordHasher.verify("passw0rd".toCharArray(), first));
    }
}
