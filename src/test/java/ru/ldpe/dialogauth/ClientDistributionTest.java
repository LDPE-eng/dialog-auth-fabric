package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientDistributionTest {
    @TempDir Path directory;

    @Test void generatesSignedAllowlistedKitAndManualReport() throws Exception {
        Path companion = jar("dialogauth-companion", "1.5.2", "dialog-auth-companion-1.5.2.jar");
        Path trmt = jar("trmt", "0.5.1", "trmt-0.5.1.jar");
        Path output = directory.resolve("public");
        Files.createDirectories(output.resolve("mods"));
        Files.writeString(output.resolve("mods/old.jar"), "stale");
        ClientDistribution.Request request = request(output, List.of(
                new ClientDistribution.ModSource(companion, true), new ClientDistribution.ModSource(trmt, false)));

        List<ClientDistribution.ModEntry> entries = ClientDistribution.generate(request);

        assertEquals(2, entries.size());
        assertTrue(entries.get(0).required());
        assertFalse(entries.get(1).required());
        assertEquals("https://example.com/trmt", entries.get(1).homepage());
        assertEquals("https://example.com/trmt/source", entries.get(1).sources());
        assertFalse(Files.exists(output.resolve("mods/old.jar")));
        JsonObject manifest = JsonParser.parseString(Files.readString(output.resolve("manifest.json"))).getAsJsonObject();
        assertEquals(2, manifest.get("formatVersion").getAsInt());
        assertEquals("gang", manifest.get("serverId").getAsString());
        assertEquals(2, manifest.getAsJsonArray("mods").size());
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(directory.resolve("public.key")))));
        verifier.update(Files.readAllBytes(output.resolve("manifest.json")));
        assertTrue(verifier.verify(Files.readAllBytes(output.resolve("manifest.sig"))));
        try (ZipFile zip = new ZipFile(output.resolve("dialog-auth-client.zip").toFile())) {
            assertNotNull(zip.getEntry("mods/dialog-auth-companion-1.5.2.jar"));
            assertNotNull(zip.getEntry("mods/trmt-0.5.1.jar"));
            assertNull(zip.getEntry("mods/dialog-auth-1.5.2.jar"));
            assertNull(zip.getEntry("config/dialogauth/config.properties"));
            assertNotNull(zip.getEntry("config/dialogauth/servers/gang/server.properties"));
            String report = new String(zip.getInputStream(zip.getEntry("MANUAL-INSTALL.txt")).readAllBytes());
            assertTrue(report.contains(entries.get(0).sha256()));
            assertTrue(report.contains("https://example.com/trmt/source"));
        }
        byte[] privateBytes = Files.readAllBytes(directory.resolve("private.key"));
        try (ZipFile zip = new ZipFile(output.resolve("dialog-auth-client.zip").toFile())) {
            for (var it = zip.entries(); it.hasMoreElements();) {
                ZipEntry entry = it.nextElement();
                assertFalse(entry.getName().contains("private"));
                if (entry.getName().endsWith("server.properties")) {
                    assertFalse(new String(zip.getInputStream(entry).readAllBytes()).contains("reset-api-token"));
                }
            }
        }
        assertNotNull(privateBytes);
    }

    @Test void rejectsMissingServerModAndUnsafeValues() throws Exception {
        assertThrows(Exception.class, () -> ClientDistribution.readMod(directory.resolve("missing.jar"), true));
        Path badName = jar("voicechat", "1.0", "bad name.jar");
        assertThrows(Exception.class, () -> ClientDistribution.readMod(badName, false));
        Path server = jar("dialogauth", "1.5.2", "dialog-auth-1.5.2.jar");
        assertThrows(Exception.class, () -> ClientDistribution.generate(request(directory.resolve("server-mod"),
                List.of(new ClientDistribution.ModSource(server, true)))));
        Path companion = jar("dialogauth-companion", "1.5.2", "dialog-auth-companion-1.5.2.jar");
        ClientDistribution.Request badId = new ClientDistribution.Request(directory.resolve("bad-id"), "../bad",
                "1.21.6", "0.17.3", List.of(new ClientDistribution.ModSource(companion, true)), "", "",
                directory.resolve("private.key"), directory.resolve("public.key"));
        assertThrows(Exception.class, () -> ClientDistribution.generate(badId));
    }

    private ClientDistribution.Request request(Path output, List<ClientDistribution.ModSource> mods) throws Exception {
        var keys = AuthCrypto.generateEd25519();
        Files.write(directory.resolve("private.key"), keys.getPrivate().getEncoded());
        Files.write(directory.resolve("public.key"), keys.getPublic().getEncoded());
        return new ClientDistribution.Request(output, "gang", "1.21.6", "0.17.3", mods,
                "https://gang.example/manifest.json", "gang.example:25565",
                directory.resolve("private.key"), directory.resolve("public.key"));
    }

    private Path jar(String id, String version, String filename) throws Exception {
        Path path = directory.resolve(filename);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        String metadata = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
                + "\",\"contact\":{\"homepage\":\"https://example.com/" + id
                + "\",\"sources\":\"https://example.com/" + id + "/source\"}}";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(metadata.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.txt"));
            zip.write(("content-" + id).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }
}
