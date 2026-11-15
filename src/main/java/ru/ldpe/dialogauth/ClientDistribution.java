package ru.ldpe.dialogauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class ClientDistribution {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record ModSource(Path jar, boolean required) {}
    public record Request(Path outputDirectory, String serverId, String minecraft, String fabricLoader,
                          List<ModSource> mods, String manifestUrl, String addresses,
                          Path privateKey, Path publicKey) {}
    public record ModEntry(String id, String version, String file, String url, String sha256, long size,
                           boolean required, String homepage, String sources) {}

    private ClientDistribution() {}

    public static void generateFromFabric(DialogAuthConfig config) throws Exception {
        if (!config.clientDistributionEnabled()) return;
        FabricLoader loader = FabricLoader.getInstance();
        Path gameDirectory = loader.getGameDir();
        Path output = Path.of(config.clientKitPath());
        if (!output.isAbsolute()) output = gameDirectory.resolve(output);
        Path companionJar = Path.of(config.clientCompanionJar());
        if (!companionJar.isAbsolute()) companionJar = gameDirectory.resolve(companionJar);
        ModEntry companion = readMod(companionJar, true);
        if (!companion.id().equals("dialogauth-companion")) {
            throw new IOException("client-companion-jar must contain dialogauth-companion");
        }
        if (!config.clientRequiredMods().contains(companion.id())) {
            throw new IOException("dialogauth-companion must be in client-required-mods");
        }
        List<ModSource> sources = new ArrayList<>();
        sources.add(new ModSource(companionJar, true));
        Set<String> added = new HashSet<>();
        added.add(companion.id());
        for (String id : config.clientRequiredMods()) {
            if (added.add(id)) sources.add(new ModSource(installedJar(loader, id, true), true));
        }
        for (String id : config.clientOptionalMods()) {
            if (added.add(id)) {
                Path jar = installedJar(loader, id, false);
                if (jar != null) sources.add(new ModSource(jar, false));
            }
        }
        Path privateKey = configuredPath(gameDirectory, config.modpackPrivateKey());
        Path publicKey = configuredPath(gameDirectory, config.modpackPublicKey());
        generate(new Request(output, config.serverId(), "1.21.6", "0.17.3", sources,
                config.clientModpackManifestUrl(), config.clientModpackServers(), privateKey, publicKey));
        DialogAuth.LOGGER.info("Generated client distribution at {}", output);
    }

    private static Path configuredPath(Path gameDirectory, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path : gameDirectory.resolve(path);
    }

    private static Path installedJar(FabricLoader loader, String id, boolean required) throws IOException {
        ModContainer container = loader.getModContainer(id).orElse(null);
        if (container == null) {
            if (required) throw new IOException("Required client mod is not installed on server: " + id);
            return null;
        }
        for (Path path : container.getOrigin().getPaths()) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) return path;
        }
        if (required) throw new IOException("No production JAR for required client mod: " + id);
        return null;
    }

    public static List<ModEntry> generate(Request request) throws Exception {
        validateRequest(request);
        List<ModEntry> entries = new ArrayList<>();
        for (ModSource source : request.mods()) entries.add(readMod(source.jar(), source.required()));
        Set<String> ids = new HashSet<>();
        Set<String> files = new HashSet<>();
        for (ModEntry entry : entries) {
            if (!ids.add(entry.id()) || !files.add(entry.file())) throw new IOException("Duplicate client mod: " + entry.id());
            if (entry.id().equals("dialogauth")) throw new IOException("Server mod is not allowed in client distribution");
        }
        Path output = request.outputDirectory().toAbsolutePath();
        Files.createDirectories(output.resolve("mods"));
        for (ModEntry entry : entries) {
            Path source = request.mods().get(entries.indexOf(entry)).jar();
            atomicCopy(source, output.resolve("mods").resolve(entry.file()));
        }
        removeUnmanagedOutputJars(output, files);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", 2);
        manifest.put("serverId", request.serverId());
        manifest.put("minecraft", request.minecraft());
        manifest.put("fabricLoader", request.fabricLoader());
        manifest.put("mods", entries);
        byte[] manifestBytes = GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(request.privateKey(), manifestBytes);
        atomicWrite(output.resolve("manifest.json"), manifestBytes);
        atomicWrite(output.resolve("manifest.sig"), signature);
        atomicCopy(request.publicKey(), output.resolve("modpack-public.key"));
        writeClientKit(request, entries, signatureFingerprint(request.publicKey()));
        return List.copyOf(entries);
    }

    public static ModEntry readMod(Path jar, boolean required) throws IOException {
        if (!Files.isRegularFile(jar)) throw new IOException("Client mod JAR not found: " + jar);
        String filename = jar.getFileName().toString();
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar")) throw new IOException("Unsafe client JAR name");
        JsonObject metadata;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IOException("Missing fabric.mod.json in " + jar);
            try (InputStream input = zip.getInputStream(entry)) {
                metadata = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        String id = metadata.get("id").getAsString();
        String version = metadata.get("version").getAsString();
        if (!id.matches("[a-z0-9][a-z0-9._-]*") || version.isBlank()) throw new IOException("Invalid mod metadata");
        JsonObject contact = metadata.getAsJsonObject("contact");
        String homepage = contact == null ? "" : stringValue(contact, "homepage");
        String sources = contact == null ? "" : stringValue(contact, "sources");
        byte[] data = Files.readAllBytes(jar);
        return new ModEntry(id, version, filename, "mods/" + filename, sha256(data), data.length,
                required, homepage, sources);
    }

    private static String stringValue(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static void validateRequest(Request request) throws IOException {
        if (!request.serverId().matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IOException("Unsafe server-id");
        if (request.mods().isEmpty()) throw new IOException("Client distribution must not be empty");
        if (!Files.isRegularFile(request.privateKey()) || !Files.isRegularFile(request.publicKey())) {
            throw new IOException("Modpack signing keys must be regular files");
        }
        requirePropertyValue(request.manifestUrl());
        requirePropertyValue(request.addresses());
    }

    private static void requirePropertyValue(String value) throws IOException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0) {
            throw new IOException("Unsafe client profile value");
        }
    }

    private static void writeClientKit(Request request, List<ModEntry> entries, String fingerprint) throws Exception {
        Path target = request.outputDirectory().resolve("dialog-auth-client.zip").toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), "dialog-auth-client-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (ModEntry entry : entries) addFile(zip, "mods/" + entry.file(), request.outputDirectory().resolve("mods").resolve(entry.file()));
                addText(zip, "INSTALL.txt", installText(entries));
                addText(zip, "MANUAL-INSTALL.txt", manualReport(request, entries, fingerprint));
                if (!request.manifestUrl().isBlank() && !request.addresses().isBlank()) {
                    addText(zip, "config/dialogauth/servers/" + request.serverId() + "/server.properties",
                            "addresses=" + request.addresses() + "\nmanifestUrl=" + request.manifestUrl() + "\n");
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String installText(List<ModEntry> entries) {
        StringBuilder text = new StringBuilder("Dialog Auth - универсальный клиентский набор\n\n"
                + "1. Создайте экземпляр Minecraft 1.21.6 с Fabric Loader 0.17.3+.\n"
                + "2. Распакуйте mods/ и config/ в корень экземпляра Prism или Pojav.\n"
                + "3. Полностью перезапустите игру после установки или обновления модов.\n\n"
                + "Моды:\n");
        entries.forEach(mod -> text.append(mod.required() ? "[required] " : "[optional] ")
                .append(mod.id()).append(' ').append(mod.version()).append(" — ").append(mod.file()).append('\n'));
        return text.toString();
    }

    private static String manualReport(Request request, List<ModEntry> entries, String fingerprint) {
        StringBuilder report = new StringBuilder("Server: " + request.serverId() + "\n"
                + "Modpack key SHA-256: " + fingerprint + "\n"
                + "После установки требуется полный перезапуск.\n\n");
        entries.forEach(mod -> report.append(mod.id()).append(' ').append(mod.version())
                .append("\n  file: ").append(mod.file()).append("\n  required: ").append(mod.required())
                .append("\n  size: ").append(mod.size()).append("\n  sha256: ").append(mod.sha256())
                .append("\n  homepage: ").append(mod.homepage()).append("\n  sources: ").append(mod.sources()).append("\n\n"));
        return report.toString();
    }

    private static byte[] sign(Path privateKey, byte[] content) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(privateKey))));
        signer.update(content);
        return signer.sign();
    }

    public static String signatureFingerprint(Path publicKey) throws Exception {
        return sha256(Files.readAllBytes(publicKey));
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void removeUnmanagedOutputJars(Path output, Set<String> desired) throws IOException {
        try (var paths = Files.list(output.resolve("mods"))) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")
                        && !desired.contains(path.getFileName().toString())) Files.delete(path);
            }
        }
    }

    private static void addFile(ZipOutputStream zip, String entry, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static void addText(ZipOutputStream zip, String entry, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void atomicCopy(Path source, Path target) throws IOException {
        atomicWrite(target, Files.readAllBytes(source));
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".dialogauth-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
