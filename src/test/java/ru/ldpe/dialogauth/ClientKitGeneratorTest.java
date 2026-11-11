package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientKitGeneratorTest {
    @TempDir Path directory;

    @Test void writesExpectedEntriesWithRussianInstallInstructions() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-0.128.2+1.21.6.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path target = directory.resolve("kit/dialog-auth-client.zip");
        byte[] companion = "companion".getBytes(StandardCharsets.UTF_8);

        ClientKitGenerator.generate(target, "1.4.0", ownJar, new ByteArrayInputStream(companion), fabricApiJar);

        Map<String, byte[]> entries = readZip(target);
        assertEquals(4, entries.size());
        assertArrayEquals("server-jar".getBytes(StandardCharsets.UTF_8), entries.get("mods/dialog-auth-1.4.0.jar"));
        assertArrayEquals(companion, entries.get("mods/dialog-auth-companion-1.4.0.jar"));
        assertArrayEquals("fabric-api".getBytes(StandardCharsets.UTF_8), entries.get("mods/fabric-api-0.128.2+1.21.6.jar"));
        String instructions = new String(entries.get("INSTALL.txt"), StandardCharsets.UTF_8);
        assertTrue(instructions.contains("Prism Launcher"));
        assertTrue(instructions.contains("dialog-auth-1.4.0.jar"));
        assertTrue(instructions.contains("dialog-auth-companion-1.4.0.jar"));
        assertTrue(instructions.contains("fabric-api-0.128.2+1.21.6.jar"));
        assertTrue(instructions.contains("config/dialogauth/modpack.properties"));
        assertTrue(instructions.contains("1.3.0"));
    }

    @Test void replacesExistingZipAndLeavesNoTemporaryFiles() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path kit = directory.resolve("kit");
        Files.createDirectories(kit);
        Path target = kit.resolve("dialog-auth-client.zip");
        Files.writeString(target, "old");
        ClientKitGenerator.generate(target, "1.4.0", ownJar,
                new ByteArrayInputStream("companion".getBytes(StandardCharsets.UTF_8)), fabricApiJar);
        assertTrue(readZip(target).containsKey("mods/dialog-auth-1.4.0.jar"));
        try (var files = Files.list(kit)) { assertEquals(1, files.count()); }
    }

    @Test void failedWriteKeepsOldZipAndCleansTemporaryFile() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path kit = directory.resolve("kit");
        Files.createDirectories(kit);
        Path target = kit.resolve("dialog-auth-client.zip");
        Files.writeString(target, "old");
        InputStream failing = new InputStream() {
            private int remaining = 3;
            @Override public int read() throws IOException {
                if (remaining-- > 0) return 'x';
                throw new IOException("boom");
            }
        };
        assertThrows(IOException.class, () ->
                ClientKitGenerator.generate(target, "1.4.0", ownJar, failing, fabricApiJar));
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(kit)) { assertEquals(1, files.count()); }
    }

    @Test void rejectsUnsafeVersionAndNonFabricApiJarName() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Path wrongFabricApiJar = sources.resolve("evil.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Files.writeString(wrongFabricApiJar, "fabric-api");
        Path target = directory.resolve("kit/dialog-auth-client.zip");
        assertThrows(IOException.class, () -> ClientKitGenerator.generate(target, "1.4.0/evil", ownJar,
                new ByteArrayInputStream(new byte[0]), fabricApiJar));
        assertThrows(IOException.class, () -> ClientKitGenerator.generate(target, "1.4.0", ownJar,
                new ByteArrayInputStream(new byte[0]), wrongFabricApiJar));
        assertFalse(Files.exists(target));
    }

    @Test void allowsRenamedServerJarButKeepsCanonicalEntryName() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path renamedJar = sources.resolve("renamed-server.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Files.writeString(renamedJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path target = directory.resolve("kit/dialog-auth-client.zip");

        ClientKitGenerator.generate(target, "1.4.0", renamedJar,
                new ByteArrayInputStream("companion".getBytes(StandardCharsets.UTF_8)), fabricApiJar);

        Map<String, byte[]> entries = readZip(target);
        assertEquals(4, entries.size());
        assertArrayEquals("server-jar".getBytes(StandardCharsets.UTF_8), entries.get("mods/dialog-auth-1.4.0.jar"));
        assertFalse(entries.containsKey("mods/renamed-server.jar"));
    }

    @Test void addsPublicModpackPropertiesOnlyWhenBothValuesAreSet() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path target = directory.resolve("kit/dialog-auth-client.zip");
        byte[] companion = "companion".getBytes(StandardCharsets.UTF_8);

        ClientKitGenerator.generate(target, "1.4.0", ownJar, new ByteArrayInputStream(companion), fabricApiJar,
                "https://example.com/manifest.json", "play.example.com, 192.0.2.1");

        Map<String, byte[]> entries = readZip(target);
        assertEquals(5, entries.size());
        String properties = new String(entries.get("config/dialogauth/modpack.properties"), StandardCharsets.UTF_8);
        assertEquals("url=https://example.com/manifest.json\nservers=play.example.com, 192.0.2.1\n", properties);
        assertFalse(entries.containsKey("config/dialogauth/config.properties"));
        assertFalse(entries.containsKey("accounts.json"));
    }

    @Test void omitsModpackPropertiesWhenOnlyOneValueIsSet() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path target = directory.resolve("kit/dialog-auth-client.zip");
        byte[] companion = "companion".getBytes(StandardCharsets.UTF_8);

        ClientKitGenerator.generate(target, "1.4.0", ownJar, new ByteArrayInputStream(companion), fabricApiJar,
                "https://example.com/manifest.json", "");

        Map<String, byte[]> entries = readZip(target);
        assertEquals(4, entries.size());
        assertFalse(entries.containsKey("config/dialogauth/modpack.properties"));
    }

    @Test void rejectsModpackPropertyInjection() throws Exception {
        Path sources = directory.resolve("sources");
        Files.createDirectories(sources);
        Path ownJar = sources.resolve("dialog-auth-1.4.0.jar");
        Path fabricApiJar = sources.resolve("fabric-api-1.0.0.jar");
        Files.writeString(ownJar, "server-jar");
        Files.writeString(fabricApiJar, "fabric-api");
        Path target = directory.resolve("kit/dialog-auth-client.zip");
        byte[] companion = "companion".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> ClientKitGenerator.generate(target, "1.4.0", ownJar,
                new ByteArrayInputStream(companion), fabricApiJar,
                "https://example.com/manifest.json", "evil\nservers=hacked"));
        assertThrows(IOException.class, () -> ClientKitGenerator.generate(target, "1.4.0", ownJar,
                new ByteArrayInputStream(companion), fabricApiJar,
                "https://example.com/manifest.json", "evil\rother=hacked"));
        assertFalse(Files.exists(target));
    }

    private static Map<String, byte[]> readZip(Path file) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }
}
