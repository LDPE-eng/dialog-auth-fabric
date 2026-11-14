package ru.ldpe.dialogauth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public record DialogAuthConfig(boolean onlineMode, boolean allowOfflineMode, boolean requireEncryptedAuth,
                               String clientDownloadUrl, String clientKitPath,
                               String clientModpackManifestUrl, String clientModpackServers,
                               boolean microsoftAuth, String microsoftProfileUrl,
                               boolean resetApiEnabled, int resetApiPort, String resetApiToken,
                               int resetCodeTtlMinutes) {
    private static final String DEFAULT_CONFIG = """
            # Dialog Auth offline-client support is disabled by default (fail closed).
            # WARNING: with online-mode=false and allow-offline-mode=true, Microsoft does not
            # verify usernames, unregistered names can be claimed, and dialog passwords travel
            # over the unencrypted Minecraft connection. Enable only if you accept these risks.
            allow-offline-mode=false
            # Require the companion mod and encrypted password responses in offline mode.
            require-encrypted-auth=true
            # Download link shown to players who join without the companion mod.
            # Leave empty to hide the link.
            client-download-url=
            # Directory where dialog-auth-client.zip is written at startup.
            # Relative paths are resolved against the server directory.
            client-kit-path=client-kit
            # Optional public modpack sync settings. If both are set, the generated
            # dialog-auth-client.zip includes config/dialogauth/modpack.properties.
            client-modpack-manifest-url=
            client-modpack-servers=
            # Let verified Microsoft profiles skip the server password dialog.
            microsoft-auth=false
            microsoft-profile-url=https://api.mojang.com/users/profiles/minecraft/
            # Localhost API used by the password-reset website. Requires a long random token.
            reset-api-enabled=false
            reset-api-port=25578
            reset-api-token=
            reset-code-ttl-minutes=15
            """;

    public static DialogAuthConfig load(Path serverProperties, Path configFile) throws IOException {
        boolean onlineMode = readBoolean(serverProperties, "online-mode", true);
        if (!Files.exists(configFile)) {
            Files.createDirectories(configFile.getParent());
            try {
                Files.writeString(configFile, DEFAULT_CONFIG, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException ignored) {
                // Another server instance created the same safe default; read it below.
            }
        }
        return new DialogAuthConfig(onlineMode, readBoolean(configFile, "allow-offline-mode", false),
                readBoolean(configFile, "require-encrypted-auth", true),
                readString(configFile, "client-download-url", ""),
                readString(configFile, "client-kit-path", "client-kit"),
                readString(configFile, "client-modpack-manifest-url", ""),
                readString(configFile, "client-modpack-servers", ""),
                readBoolean(configFile, "microsoft-auth", false),
                readString(configFile, "microsoft-profile-url", "https://api.mojang.com/users/profiles/minecraft/"),
                readBoolean(configFile, "reset-api-enabled", false),
                readInt(configFile, "reset-api-port", 25578, 1, 65535),
                readString(configFile, "reset-api-token", ""),
                readInt(configFile, "reset-code-ttl-minutes", 15, 1, 1440));
    }

    private static Properties properties(Path file) throws IOException {
        if (!Files.exists(file)) return new Properties();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid properties file: " + file, e);
        }
        return properties;
    }

    private static boolean readBoolean(Path file, String key, boolean defaultValue) throws IOException {
        String value = properties(file).getProperty(key);
        if (value == null) return defaultValue;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IOException("Property " + key + " must be true or false in " + file);
    }

    private static String readString(Path file, String key, String defaultValue) throws IOException {
        String value = properties(file).getProperty(key);
        if (value == null) return defaultValue;
        return value.trim();
    }

    private static int readInt(Path file, String key, int defaultValue, int minimum, int maximum) throws IOException {
        String value = readString(file, key, "");
        if (value.isEmpty()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new IOException("Property " + key + " is out of range");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IOException("Property " + key + " must be an integer", e);
        }
    }
}
