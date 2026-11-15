package ru.ldpe.dialogauth.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModpackChangePlanTest {
    @TempDir Path directory;

    @Test void reportsUpdateAndStaleRemovalWithoutWritingFiles() throws Exception {
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        Path old = mods.resolve("test-1.0.jar");
        writeMod(old, "test", "1.0");
        byte[] oldBytes = Files.readAllBytes(old);
        String hash = ModpackInstaller.hash(old);
        ModpackManifest.Mod mod = new ModpackManifest.Mod("test", "2.0", "test-2.0.jar", "mods/test-2.0.jar",
                hash, 123, false, "https://example.com", "https://example.com/source");
        ModpackChangePlan plan = ModpackChangePlan.create(new ModpackManifest(2, "1.21.6", "0.17.3", List.of(mod), "gang"),
                mods, "fingerprint");

        assertEquals(List.of(ModpackChangePlan.Type.UPDATE, ModpackChangePlan.Type.REMOVE),
                plan.changes().stream().map(ModpackChangePlan.Change::type).toList());
        String report = plan.report("gang", "gang.example:25565");
        assertTrue(report.contains("1.0 -> 2.0"));
        assertTrue(report.contains("[optional]"));
        assertTrue(report.contains(hash));
        assertTrue(report.contains("fingerprint"));
        assertArrayEquals(oldBytes, Files.readAllBytes(old));
        assertFalse(Files.exists(mods.resolve("test-2.0.jar")));
        assertFalse(plan.isEmpty());
    }

    @Test void matchingCurrentFileNeedsNoConfirmation() throws Exception {
        Path mods = directory.resolve("mods");
        Files.createDirectories(mods);
        Path current = mods.resolve("test-2.0.jar");
        writeMod(current, "test", "2.0");
        ModpackManifest.Mod mod = new ModpackManifest.Mod("test", "2.0", "test-2.0.jar", "mods/test-2.0.jar",
                ModpackInstaller.hash(current), Files.size(current));
        ModpackChangePlan plan = ModpackChangePlan.create(new ModpackManifest(1, "1.21.6", "0.17.3", List.of(mod)),
                mods, "fingerprint");
        assertTrue(plan.isEmpty());
    }

    private static void writeMod(Path file, String id, String version) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(("{\"id\":\"" + id + "\",\"version\":\"" + version + "\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
