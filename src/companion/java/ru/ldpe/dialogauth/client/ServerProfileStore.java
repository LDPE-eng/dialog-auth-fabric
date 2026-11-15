package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;

public final class ServerProfileStore {
    public record ServerProfile(String serverId, Path directory, String manifestUrl, java.util.List<String> addresses,
                                Path authKey, Path modpackKey) {}

    private final Path root;
    private final Path legacyAuthKey;
    private final Path legacyModpackKey;

    public ServerProfileStore(Path configDirectory) {
        root = configDirectory.resolve("servers");
        legacyAuthKey = configDirectory.resolve("auth-server-public.key");
        legacyModpackKey = configDirectory.resolve("modpack-public.key");
    }

    public synchronized Optional<ServerProfile> findByAddress(String address) throws IOException {
        if (!Files.isDirectory(root)) return Optional.empty();
        ServerProfile found = null;
        try (var paths = Files.list(root)) {
            for (Path directory : paths.toList()) {
                if (!Files.isDirectory(directory) || !directory.getFileName().toString().matches("[a-z0-9][a-z0-9._-]{0,63}")) continue;
                ServerProfile profile = load(directory.getFileName().toString());
                if (!profile.addresses().contains(address)) continue;
                if (found != null) throw new IOException("Multiple Dialog Auth profiles for address " + address);
                found = profile;
            }
        }
        return Optional.ofNullable(found);
    }

    public synchronized ServerProfile ensure(String serverId, String address, String manifestUrl) throws IOException {
        validateId(serverId);
        Path directory = root.resolve(serverId);
        Files.createDirectories(directory);
        Path propertiesFile = directory.resolve("server.properties");
        Properties properties = new Properties();
        if (Files.isRegularFile(propertiesFile)) {
            try (var input = Files.newInputStream(propertiesFile)) {
                properties.load(input);
            }
        }
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        for (String value : properties.getProperty("addresses", "").split("\\s*,\\s*")) {
            if (!value.isBlank()) addresses.add(value);
        }
        if (!address.isBlank()) addresses.add(address);
        String effectiveManifest = manifestUrl.isBlank() ? properties.getProperty("manifestUrl", "") : manifestUrl;
        properties.setProperty("addresses", String.join(",", addresses));
        properties.setProperty("manifestUrl", effectiveManifest);
        atomicWrite(propertiesFile, ("addresses=" + String.join(",", addresses) + "\nmanifestUrl="
                + effectiveManifest + "\n").getBytes(StandardCharsets.UTF_8));
        var existing = findByAddress(address);
        if (existing.isPresent() && !existing.get().serverId().equals(serverId)) {
            migrateLegacy(directory.resolve("auth-server-public.key"), existing.get().authKey());
            migrateLegacy(directory.resolve("modpack-public.key"), existing.get().modpackKey());
        }
        migrateLegacy(directory.resolve("auth-server-public.key"), legacyAuthKey);
        migrateLegacy(directory.resolve("modpack-public.key"), legacyModpackKey);
        return load(serverId);
    }

    public synchronized ServerProfile ensureLegacy(String address, String manifestUrl) throws IOException {
        return findByAddress(address).orElseGet(() -> {
            try {
                return ensure("legacy-" + shortHash(address), address, manifestUrl);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public ServerProfile load(String serverId) throws IOException {
        validateId(serverId);
        Path directory = root.resolve(serverId);
        Properties properties = new Properties();
        Path file = directory.resolve("server.properties");
        if (Files.isRegularFile(file)) {
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
            }
        }
        java.util.List<String> addresses = java.util.Arrays.stream(properties.getProperty("addresses", "")
                .split("\\s*,\\s*")).filter(value -> !value.isBlank()).toList();
        return new ServerProfile(serverId, directory, properties.getProperty("manifestUrl", ""), addresses,
                directory.resolve("auth-server-public.key"), directory.resolve("modpack-public.key"));
    }

    private static void migrateLegacy(Path target, Path legacy) throws IOException {
        if (Files.exists(target) || !Files.isRegularFile(legacy)) return;
        byte[] key = Files.readAllBytes(legacy);
        atomicWrite(target, key);
    }

    private static void validateId(String serverId) throws IOException {
        if (!serverId.matches("[a-z0-9][a-z0-9._-]{0,63}")) throw new IOException("Unsafe server id");
    }

    private static String shortHash(String address) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(address.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
