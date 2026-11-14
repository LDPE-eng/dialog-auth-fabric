package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordResetTest {
    @TempDir Path directory;

    @Test void resetIsSingleUseAndChangesOnlyPassword() throws Exception {
        AccountStore accounts = new AccountStore(directory.resolve("accounts.json"));
        UUID uuid = UUID.randomUUID();
        accounts.register(uuid, "Alice", "old-password".toCharArray(), () -> true);
        ResetCodeStore codes = new ResetCodeStore(directory.resolve("reset-codes.json"), Duration.ofMinutes(15),
                Clock.systemUTC());
        String code = codes.issue(uuid);
        assertTrue(code.matches("[0-9]{6}"));
        assertFalse(codes.reset(accounts, uuid, "000000".equals(code) ? "000001" : "000000",
                "new-password".toCharArray()));
        assertTrue(codes.reset(accounts, uuid, code, "new-password".toCharArray()));
        assertTrue(accounts.verify(uuid, "Alice", "new-password".toCharArray(), () -> true));
        assertFalse(accounts.verify(uuid, "Alice", "old-password".toCharArray(), () -> true));
        assertFalse(codes.reset(accounts, uuid, code, "third-password".toCharArray()));
        assertFalse(Files.readString(directory.resolve("reset-codes.json")).contains(code));
    }

    @Test void codesExpireAndAllowOnlyFiveFailures() throws Exception {
        MutableClock clock = new MutableClock();
        AccountStore accounts = new AccountStore(directory.resolve("accounts.json"));
        UUID expiredId = UUID.randomUUID();
        ResetCodeStore codes = new ResetCodeStore(directory.resolve("reset-codes.json"), Duration.ofMinutes(15), clock);
        String expired = codes.issue(expiredId);
        clock.instant = clock.instant.plus(Duration.ofMinutes(16));
        assertFalse(codes.reset(accounts, expiredId, expired, "new-password".toCharArray()));
        UUID uuid = UUID.randomUUID();
        accounts.register(uuid, "Alice", "old-password".toCharArray(), () -> true);
        String code = codes.issue(uuid);
        for (int attempt = 0; attempt < 5; attempt++) {
            String wrong = String.format("%06d", (Integer.parseInt(code) + attempt + 1) % 1_000_000);
            assertFalse(codes.reset(accounts, uuid, wrong, "new-password".toCharArray()));
        }
        assertFalse(codes.reset(accounts, uuid, code, "new-password".toCharArray()));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(directory.resolve("reset-codes.json")));
    }

    @Test void localhostApiRequiresTokenAndResetsPassword() throws Exception {
        AccountStore accounts = new AccountStore(directory.resolve("accounts.json"));
        UUID uuid = UUID.randomUUID();
        accounts.register(uuid, "Alice", "old-password".toCharArray(), () -> true);
        ResetCodeStore codes = new ResetCodeStore(directory.resolve("reset-codes.json"), Duration.ofMinutes(15),
                Clock.systemUTC());
        String code = codes.issue(uuid);
        String token = "test-token-that-is-longer-than-thirty-two-characters";
        try (ResetApiServer api = new ResetApiServer(accounts, codes, token, 0)) {
            api.start();
            HttpClient client = HttpClient.newHttpClient();
            String body = "{\"username\":\"Alice\",\"code\":\"" + code + "\",\"password\":\"new-password\"}";
            HttpResponse<String> unauthorized = post(client, api, body, "wrong-token-that-is-longer-than-thirty-two");
            assertEquals(401, unauthorized.statusCode());
            HttpResponse<String> response = post(client, api, body, token);
            assertEquals(200, response.statusCode());
            assertEquals("{\"ok\":true}", response.body());
            assertTrue(accounts.verify(uuid, "Alice", "new-password".toCharArray(), () -> true));
            HttpResponse<String> reused = post(client, api, body, token);
            assertEquals("{\"ok\":false,\"error\":\"invalid_or_expired\"}", reused.body());
        }
    }

    private static HttpResponse<String> post(HttpClient client, ResetApiServer api, String body, String token)
            throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + "/reset"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
