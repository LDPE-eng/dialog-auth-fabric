package ru.ldpe.dialogauth.client;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModpackInstaller {
    private static final Gson GSON = new Gson();

    private ModpackInstaller() {}

    public static List<String> sync(ModpackManifest manifest, URI manifestUri, Path modsDirectory, HttpClient http) throws IOException {
        Path staging = modsDirectory.resolve(".dialogauth-staging");
        Files.createDirectories(staging);
        List<Pending> pending = new ArrayList<>();
        for (ModpackManifest.Mod mod : manifest.mods()) {
            Path target = modsDirectory.resolve(mod.file());
            if (Files.isRegularFile(target) && hash(target).equalsIgnoreCase(mod.sha256())) continue;
            Path downloaded = staging.resolve(mod.file());
            byte[] bytes = ModpackManifest.getBytes(http, manifestUri.resolve(mod.url()), ModpackManifest.MAX_MOD_BYTES);
            if (bytes.length != mod.size()) throw new IOException("Unexpected size for " + mod.file());
            String actual;
            try {
                actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            if (!actual.equalsIgnoreCase(mod.sha256())) throw new IOException("Hash mismatch for " + mod.file());
            Files.write(downloaded, bytes);
            pending.add(new Pending(mod.file(), downloaded, target));
        }
        if (pending.isEmpty()) return List.of();
        Path backup = modsDirectory.resolve(".dialogauth-backup").resolve(Instant.now().toString().replace(':', '-'));
        List<String> changed = new ArrayList<>();
        removeStaleVersions(manifest, modsDirectory, backup, changed);
        for (Pending item : pending) {
            if (Files.exists(item.target())) {
                Files.createDirectories(backup);
                Files.move(item.target(), backup.resolve(item.target().getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(item.downloaded(), item.target(), StandardCopyOption.REPLACE_EXISTING);
            changed.add(item.target().getFileName().toString());
        }
        return List.copyOf(changed);
    }

    private static void removeStaleVersions(ModpackManifest manifest, Path modsDirectory, Path backup,
                                            List<String> changed) throws IOException {
        Map<String, String> desiredFileById = new HashMap<>();
        for (ModpackManifest.Mod mod : manifest.mods()) {
            desiredFileById.put(mod.id(), mod.file());
        }
        List<Path> jars = new ArrayList<>();
        try (var paths = Files.list(modsDirectory)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                    jars.add(path);
                }
            }
        }
        for (Path jar : jars) {
            String fileName = jar.getFileName().toString();
            String id = readModId(jar);
            if (id == null) continue;
            String desired = desiredFileById.get(id);
            if (desired == null || desired.equals(fileName)) continue;
            Files.createDirectories(backup);
            Files.move(jar, backup.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            changed.add(fileName);
        }
    }

    private static String readModId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream input = zip.getInputStream(entry)) {
                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                ModInfo info = GSON.fromJson(json, ModInfo.class);
                return info == null || info.id == null ? null : info.id;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String hash(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private record ModInfo(String id) {}
    private record Pending(String name, Path downloaded, Path target) {}
}
