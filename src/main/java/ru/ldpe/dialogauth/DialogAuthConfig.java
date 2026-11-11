package ru.ldpe.dialogauth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

public record DialogAuthConfig(boolean onlineMode, boolean allowOfflineMode, boolean requireEncryptedAuth) {
    private static final String DEFAULT_CONFIG = """
            # Dialog Auth offline-client support is disabled by default (fail closed).
            # WARNING: with online-mode=false and allow-offline-mode=true, Microsoft does not
            # verify usernames, unregistered names can be claimed, and dialog passwords travel
            # over the unencrypted Minecraft connection. Enable only if you accept these risks.
            allow-offline-mode=false
            # Require the companion mod and encrypted password responses in offline mode.
            require-encrypted-auth=true
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
                readBoolean(configFile, "require-encrypted-auth", true));
    }

    private static boolean readBoolean(Path file, String key, boolean defaultValue) throws IOException {
        if (!Files.exists(file)) return defaultValue;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid properties file: " + file, e);
        }
        String value = properties.getProperty(key);
        if (value == null) return defaultValue;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IOException("Property " + key + " must be true or false in " + file);
    }
}
