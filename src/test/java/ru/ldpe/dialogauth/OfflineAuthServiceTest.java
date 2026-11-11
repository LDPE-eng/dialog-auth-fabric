package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class OfflineAuthServiceTest {
    @TempDir Path directory;
    final AtomicLong clock = new AtomicLong();
    final List<Exception> errors = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();

    AuthService offlineService(AuthBackend backend, boolean allowed, int threads) {
        return new AuthService(backend, new RateLimiter(clock::get, 100), threads, 4,
                errors::add, false, allowed, warnings::add);
    }

    @Test void offlineIsBlockedByDefaultBeforeDialog() {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        try (var service = offlineService(store, false, 1)) {
            AuthServiceTest.Loop loop = new AuthServiceTest.Loop();
            var session = service.session(offlineUuid("Alice"), "Alice", "one", loop);
            session.start();
            assertEquals(AuthService.State.CLOSED, session.state());
            assertTrue(loop.reason.contains("allow-offline-mode=true"));
            assertEquals(0, loop.scheduled);
            assertEquals(1, warnings.size());
            assertTrue(warnings.getFirst().contains("allow-offline-mode=false"));
        }
    }

    @Test void onlineModeStillUsesOnlyUuidAndDoesNotFallBackToName() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        store.register(UUID.randomUUID(), "LDP_e", "password".toCharArray(), () -> true);
        try (var service = new AuthService(store, new RateLimiter(clock::get, 100), 1, 1, errors::add)) {
            AuthServiceTest.Loop loop = new AuthServiceTest.Loop();
            var session = service.session(UUID.randomUUID(), "LDP_e", "one", loop);
            session.start();
            assertTrue(loop.registering);
        }
    }

    @Test void offlineRegistrationAndLoginUseVanillaOfflineUuid() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        UUID uuid = offlineUuid("Alice");
        try (var service = offlineService(store, true, 1)) {
            AuthServiceTest.Loop registrationLoop = new AuthServiceTest.Loop();
            var registration = service.session(uuid, "Alice", "one", registrationLoop);
            registration.start();
            assertTrue(registrationLoop.registering);
            registration.submit("password", "password");
            registrationLoop.deliver();
            assertEquals(1, registrationLoop.successes);
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            AuthServiceTest.Loop loginLoop = new AuthServiceTest.Loop();
            var login = service.session(uuid, "Alice", "two", loginLoop);
            login.start();
            assertFalse(loginLoop.registering);
            login.submit("password", "");
            loginLoop.deliver();
            assertEquals(1, loginLoop.successes);
        }
        assertTrue(store.exists(uuid));
        assertEquals(1, accountRoot().size());
        assertTrue(errors.isEmpty());
    }

    @Test void existingOnlineAccountLogsInOfflineWithoutAliasOrCredentialOverwrite() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        UUID onlineUuid = UUID.randomUUID();
        store.register(onlineUuid, "LDP_e", "password".toCharArray(), () -> true);
        JsonObject original = accountRoot().getAsJsonObject(onlineUuid.toString());
        String salt = original.get("salt").getAsString();
        String hash = original.get("hash").getAsString();
        UUID offlineUuid = offlineUuid("LDP_e");
        assertNotEquals(onlineUuid, offlineUuid);
        try (var service = offlineService(store, true, 1)) {
            AuthServiceTest.Loop loop = new AuthServiceTest.Loop();
            var login = service.session(offlineUuid, "LDP_e", "one", loop);
            login.start();
            assertFalse(loop.registering);
            login.submit("password", "");
            loop.deliver();
            assertEquals(1, loop.successes);
        }
        JsonObject accounts = accountRoot();
        assertEquals(1, accounts.size());
        assertTrue(accounts.has(onlineUuid.toString()));
        assertFalse(accounts.has(offlineUuid.toString()));
        JsonObject migrated = accounts.getAsJsonObject(onlineUuid.toString());
        assertEquals(salt, migrated.get("salt").getAsString());
        assertEquals(hash, migrated.get("hash").getAsString());
        assertEquals("LDP_e", migrated.get("username").getAsString());
    }

    @Test void wrongPasswordCreatesNoAliasAndCaseVariantCannotBypassRateLimit() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        UUID onlineUuid = UUID.randomUUID();
        store.register(onlineUuid, "LDP_e", "password".toCharArray(), () -> true);
        UUID attemptedUuid = offlineUuid("LDP_E");
        try (var service = offlineService(store, true, 1)) {
            AuthServiceTest.Loop wrongLoop = new AuthServiceTest.Loop();
            var wrong = service.session(attemptedUuid, "LDP_E", "one", wrongLoop);
            wrong.start();
            assertFalse(wrongLoop.registering);
            wrong.submit("wrongpass", "");
            wrongLoop.deliver();
            assertEquals(AuthService.State.READY, wrong.state());
            assertEquals(1, accountRoot().size());
            assertFalse(store.exists(attemptedUuid));
            AuthServiceTest.Loop blockedLoop = new AuthServiceTest.Loop();
            var blocked = service.session(offlineUuid("ldp_e"), "ldp_e", "different-ip", blockedLoop);
            blocked.start();
            blocked.submit("password", "");
            assertEquals(AuthService.State.CLOSED, blocked.state());
            assertTrue(blockedLoop.reason.contains("Слишком частые"));
            assertEquals(0, blockedLoop.successes);
        }
        JsonObject accounts = accountRoot();
        assertEquals(1, accounts.size());
        assertTrue(accounts.has(onlineUuid.toString()));
    }

    @Test void caseVariantCannotBypassOfflineSingleFlight() throws Exception {
        AuthServiceTest.BlockingBackend backend = new AuthServiceTest.BlockingBackend();
        try (var service = offlineService(backend, true, 2)) {
            AuthServiceTest.Loop firstLoop = new AuthServiceTest.Loop();
            var first = service.session(offlineUuid("Alice"), "Alice", "one", firstLoop);
            AuthServiceTest.Loop secondLoop = new AuthServiceTest.Loop();
            var second = service.session(offlineUuid("alice"), "alice", "two", secondLoop);
            first.start();
            second.start();
            first.submit("password", "");
            assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
            clock.addAndGet(Duration.ofSeconds(2).toNanos());
            second.submit("password", "");
            assertEquals(AuthService.State.CLOSED, second.state());
            assertTrue(secondLoop.reason.contains("имени"));
            assertEquals(1, backend.calls.get());
            backend.release.countDown();
            firstLoop.deliver();
            assertEquals(1, firstLoop.successes);
        } finally {
            backend.release.countDown();
        }
    }

    @Test void offlineRegistrationRejectsCaseInsensitiveDuplicateName() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts.json"));
        store.register(offlineUuid("Alice"), "Alice", "password".toCharArray(), true, () -> true);
        UUID variant = offlineUuid("alice");
        assertThrows(IllegalStateException.class,
                () -> store.register(variant, "alice", "password".toCharArray(), true, () -> true));
        assertFalse(store.exists(variant));
        assertEquals(1, accountRoot().size());
    }

    private JsonObject accountRoot() throws Exception {
        return JsonParser.parseString(Files.readString(directory.resolve("accounts.json")))
                .getAsJsonObject().getAsJsonObject("accounts");
    }

    private static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
