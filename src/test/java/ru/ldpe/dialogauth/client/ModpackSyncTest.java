package ru.ldpe.dialogauth.client;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Signature;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ldpe.dialogauth.AuthCrypto;

class ModpackSyncTest {
    @TempDir Path directory;
    HttpServer server;
    KeyPair signingKeys;
    byte[] modBytes;
    URI manifestUri;

    @BeforeEach void start() throws Exception {
        signingKeys = AuthCrypto.generateEd25519();
        modBytes = "test-mod-content".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        manifestUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest.json");
        server.createContext("/modpack-public.key", exchange -> respond(exchange, signingKeys.getPublic().getEncoded()));
        server.createContext("/mods/test.jar", exchange -> respond(exchange, modBytes));
        server.start();
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    @Test void signedManifestInstallsVerifiesAndBacksUp() throws Exception {
        publish("test-mod-1.0.jar");
        var publicKey = ModpackManifest.readPublicKey(signingKeys.getPublic().getEncoded());
        var manifest = ModpackManifest.fetch(manifestUri, publicKey, HttpClient.newHttpClient());
        Path mods = directory.resolve("mods");
        List<String> changed = ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient());
        assertEquals(List.of("test-mod-1.0.jar"), changed);
        assertArrayEquals(modBytes, Files.readAllBytes(mods.resolve("test-mod-1.0.jar")));
        assertTrue(ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient()).isEmpty());
        Files.writeString(mods.resolve("test-mod-1.0.jar"), "outdated");
        assertEquals(1, ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient()).size());
        assertArrayEquals(modBytes, Files.readAllBytes(mods.resolve("test-mod-1.0.jar")));
        assertTrue(Files.isDirectory(mods.resolve(".dialogauth-backup")));
    }

    @Test void rejectsBadSignatureAndUnsafeFilename() throws Exception {
        publish("test-mod-1.0.jar");
        var publicKey = ModpackManifest.readPublicKey(signingKeys.getPublic().getEncoded());
        server.removeContext("/manifest.sig");
        server.createContext("/manifest.sig", exchange -> respond(exchange, new byte[64]));
        assertThrows(IOException.class, () -> ModpackManifest.fetch(manifestUri, publicKey, HttpClient.newHttpClient()));
        server.removeContext("/manifest.sig");
        publish("../escape.jar");
        assertThrows(IOException.class, () -> ModpackManifest.fetch(manifestUri, publicKey, HttpClient.newHttpClient()));
    }

    private void publish(String filename) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(modBytes));
        String json = "{\"formatVersion\":1,\"minecraft\":\"1.21.6\",\"fabricLoader\":\"0.16.14\",\"mods\":[{"
                + "\"id\":\"test\",\"version\":\"1.0\",\"file\":\"" + filename + "\",\"url\":\"mods/test.jar\","
                + "\"sha256\":\"" + hash + "\",\"size\":" + modBytes.length + "}]}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(signingKeys.getPrivate());
        signer.update(bytes);
        byte[] signature = signer.sign();
        if (server.getAddress() != null) {
            try { server.removeContext("/manifest.json"); } catch (IllegalArgumentException ignored) {}
            try { server.removeContext("/manifest.sig"); } catch (IllegalArgumentException ignored) {}
        }
        server.createContext("/manifest.json", exchange -> respond(exchange, bytes));
        server.createContext("/manifest.sig", exchange -> respond(exchange, signature));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
