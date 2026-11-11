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
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModpackInstallerTest {
    @TempDir Path directory;
    HttpServer server;
    byte[] newBytes;
    URI manifestUri;

    @BeforeEach void start() throws Exception {
        newBytes = "new-mod-content".getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        manifestUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest.json");
        server.createContext("/mods/new.jar", exchange -> respond(exchange, newBytes));
        server.start();
    }

    @AfterEach void stop() {
        server.stop(0);
    }

    @Test void movesStaleManagedModToBackupAndInstallsNew() throws Exception {
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        writeModJar(mods.resolve("test-mod-1.3.0.jar"), "test", "1.3.0");
        ModpackManifest manifest = manifest("test-mod-1.4.0.jar", "mods/new.jar");

        List<String> changed = ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient());

        assertFalse(Files.exists(mods.resolve("test-mod-1.3.0.jar")));
        assertArrayEquals(newBytes, Files.readAllBytes(mods.resolve("test-mod-1.4.0.jar")));
        assertTrue(changed.contains("test-mod-1.3.0.jar"));
        assertTrue(changed.contains("test-mod-1.4.0.jar"));
        Path backupRoot = mods.resolve(".dialogauth-backup");
        Path backup = Files.list(backupRoot).findFirst().orElseThrow();
        assertTrue(Files.isRegularFile(backup.resolve("test-mod-1.3.0.jar")));
    }

    @Test void leavesForeignAndBrokenJarsAlone() throws Exception {
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        writeModJar(mods.resolve("other-mod-2.0.0.jar"), "other", "2.0.0");
        Files.writeString(mods.resolve("broken.jar"), "not a zip");
        writeModJar(mods.resolve("test-mod-1.3.0.jar"), "test", "1.3.0");
        ModpackManifest manifest = manifest("test-mod-1.4.0.jar", "mods/new.jar");

        List<String> changed = ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient());

        assertTrue(Files.exists(mods.resolve("other-mod-2.0.0.jar")));
        assertTrue(Files.exists(mods.resolve("broken.jar")));
        assertFalse(Files.exists(mods.resolve("test-mod-1.3.0.jar")));
        assertTrue(Files.exists(mods.resolve("test-mod-1.4.0.jar")));
        assertTrue(changed.contains("test-mod-1.3.0.jar"));
        assertFalse(changed.contains("other-mod-2.0.0.jar"));
    }

    @Test void repeatSyncIsEmptyAfterStaleRemoval() throws Exception {
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        writeModJar(mods.resolve("test-mod-1.3.0.jar"), "test", "1.3.0");
        ModpackManifest manifest = manifest("test-mod-1.4.0.jar", "mods/new.jar");

        List<String> first = ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient());
        assertFalse(first.isEmpty());
        List<String> second = ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient());
        assertTrue(second.isEmpty());
        assertFalse(Files.exists(mods.resolve("test-mod-1.3.0.jar")));
    }

    @Test void failedDownloadDoesNotTouchOldJar() throws Exception {
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        Path old = mods.resolve("test-mod-1.3.0.jar");
        writeModJar(old, "test", "1.3.0");
        byte[] original = Files.readAllBytes(old);
        ModpackManifest manifest = manifest("test-mod-1.4.0.jar", "mods/missing.jar");

        assertThrows(IOException.class, () ->
                ModpackInstaller.sync(manifest, manifestUri, mods, HttpClient.newHttpClient()));

        assertArrayEquals(original, Files.readAllBytes(old));
        assertFalse(Files.exists(mods.resolve("test-mod-1.4.0.jar")));
        assertFalse(Files.exists(mods.resolve(".dialogauth-backup")));
    }

    private ModpackManifest manifest(String file, String url) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(newBytes));
        return new ModpackManifest(1, "1.21.6", "0.16.14", List.of(
                new ModpackManifest.Mod("test", "1.4.0", file, url, hash, newBytes.length)));
    }

    private static void writeModJar(Path file, String id, String version) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}";
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
