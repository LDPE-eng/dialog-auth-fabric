package ru.ldpe.dialogauth.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ldpe.dialogauth.AuthCrypto;

class ServerProfileStoreTest {
    @TempDir Path directory;

    @Test void separatesServersAndPreservesIdentityWhenAddressChanges() throws Exception {
        ServerProfileStore profiles = new ServerProfileStore(directory);
        var gang = profiles.ensure("gang", "gang.example:25565", "https://gang.example/manifest.json");
        var other = profiles.ensure("other", "other.example:25565", "https://other.example/manifest.json");
        Files.writeString(gang.authKey(), "gang-key");
        Files.writeString(other.modpackKey(), "other-key");
        var moved = profiles.ensure("gang", "new.gang.example:25565", "https://gang.example/manifest.json");
        assertEquals("gang-key", Files.readString(moved.authKey()));
        assertEquals(List.of("gang.example:25565", "new.gang.example:25565"), moved.addresses());
        assertEquals("other", profiles.findByAddress("other.example:25565").orElseThrow().serverId());
        assertEquals("gang", profiles.findByAddress("new.gang.example:25565").orElseThrow().serverId());
    }

    @Test void migratesLegacyKeysOnceWithoutOverwritingProfile() throws Exception {
        Files.writeString(directory.resolve("auth-server-public.key"), "legacy-auth");
        Files.writeString(directory.resolve("modpack-public.key"), "legacy-modpack");
        ServerProfileStore profiles = new ServerProfileStore(directory);
        var profile = profiles.ensure("gang", "gang.example:25565", "");
        assertEquals("legacy-auth", Files.readString(profile.authKey()));
        assertEquals("legacy-modpack", Files.readString(profile.modpackKey()));
        Files.writeString(profile.authKey(), "new-auth");
        profiles.ensure("gang", "gang.example:25565", "");
        assertEquals("new-auth", Files.readString(profile.authKey()));
    }

    @Test void modpackKeyChangeRequiresConfirmationAndDoesNotOverwrite() throws Exception {
        var oldKey = AuthCrypto.generateEd25519();
        var newKey = AuthCrypto.generateEd25519();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/modpack-public.key", exchange -> {
            exchange.sendResponseHeaders(200, newKey.getPublic().getEncoded().length);
            exchange.getResponseBody().write(newKey.getPublic().getEncoded());
            exchange.close();
        });
        server.start();
        try {
            ModpackTrustStore trust = new ModpackTrustStore(directory.resolve("servers/gang/modpack-public.key"));
            ModpackTrustStore.TrustState first = trust.inspect(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/manifest.json"), HttpClient.newHttpClient());
            assertTrue(first.firstUse());
            assertFalse(Files.exists(directory.resolve("servers/gang/modpack-public.key")));
            trust.confirm(first);
            server.removeContext("/modpack-public.key");
            server.createContext("/modpack-public.key", exchange -> {
                exchange.sendResponseHeaders(200, oldKey.getPublic().getEncoded().length);
                exchange.getResponseBody().write(oldKey.getPublic().getEncoded());
                exchange.close();
            });
            ModpackTrustStore.TrustState changed = trust.inspect(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/manifest.json"), HttpClient.newHttpClient());
            assertTrue(changed.changed());
            assertEquals(newKey.getPublic(), trust.trusted());
            trust.confirm(changed);
            assertEquals(oldKey.getPublic(), trust.trusted());
        } finally {
            server.stop(0);
        }
    }

    @Test void authKeyChangeKeepsOldKeyUntilExplicitConfirmation() throws Exception {
        var oldKey = AuthCrypto.generateEd25519();
        var newKey = AuthCrypto.generateEd25519();
        AuthServerTrustStore trust = new AuthServerTrustStore(directory.resolve("gang/auth.key"));
        trust.verifyOrPin(oldKey.getPublic());
        AuthServerTrustStore.Change change = trust.inspectChange(newKey.getPublic());
        assertNotNull(change);
        assertThrows(Exception.class, () -> trust.verifyOrPin(newKey.getPublic()));
        assertEquals(oldKey.getPublic(), trust.trusted());
        trust.confirmChange(change);
        assertEquals(newKey.getPublic(), trust.trusted());
    }
}
