package ru.ldpe.dialogauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class MicrosoftProfileVerifier {
    public enum Decision { ONLINE, OFFLINE }
    private record CachedProfile(UUID uuid, long expiresAt) {}
    private static final Map<String, CachedProfile> CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "dialogauth-mojang-" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    private static volatile String profileUrl = "https://api.mojang.com/users/profiles/minecraft/";
    private static volatile HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private MicrosoftProfileVerifier() {}

    public static void configure(String baseUrl) {
        String scheme = URI.create(baseUrl).getScheme();
        if (scheme == null || !scheme.equals("http") && !scheme.equals("https") || !baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("microsoft-profile-url must be an HTTP(S) URL ending with /");
        }
        profileUrl = baseUrl;
        CACHE.clear();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public static CompletableFuture<Decision> lookup(String username, UUID claimedUuid) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return lookupNow(username, claimedUuid);
                } catch (IOException e) {
                    throw new LookupFailure(e);
                }
            }, WORKERS);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    static Decision lookupNow(String username, UUID claimedUuid) throws IOException {
        String key = username.toLowerCase(Locale.ROOT);
        CachedProfile cached = CACHE.get(key);
        UUID mojangUuid;
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            mojangUuid = cached.uuid();
        } else {
            mojangUuid = fetch(username);
            CACHE.put(key, new CachedProfile(mojangUuid, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)));
        }
        return mojangUuid != null && mojangUuid.equals(claimedUuid) ? Decision.ONLINE : Decision.OFFLINE;
    }

    private static UUID fetch(String username) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(profileUrl + username))
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Mojang profile lookup interrupted", e);
        }
        if (response.statusCode() == 204 || response.statusCode() == 404) return null;
        if (response.statusCode() != 200) throw new IOException("Mojang profile lookup HTTP " + response.statusCode());
        try {
            JsonObject profile = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!username.equalsIgnoreCase(profile.get("name").getAsString())) {
                throw new IOException("Mojang profile name mismatch");
            }
            String id = profile.get("id").getAsString();
            if (!id.matches("[0-9a-fA-F]{32}")) throw new IOException("Invalid Mojang UUID");
            return UUID.fromString(id.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5"));
        } catch (RuntimeException e) {
            throw new IOException("Invalid Mojang profile response", e);
        }
    }

    private static final class LookupFailure extends RuntimeException {
        private LookupFailure(IOException cause) { super(cause); }
    }
}
