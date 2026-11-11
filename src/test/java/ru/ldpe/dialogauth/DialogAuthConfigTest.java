package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogAuthConfigTest {
    @TempDir Path directory;

    @Test void defaultsToOnlineAndCreatesFailClosedConfigWithWarning() throws Exception {
        Path server = directory.resolve("server.properties");
        Path config = directory.resolve("config/dialogauth/config.properties");
        DialogAuthConfig loaded = DialogAuthConfig.load(server, config);
        assertTrue(loaded.onlineMode());
        assertFalse(loaded.allowOfflineMode());
        String text = Files.readString(config);
        assertTrue(text.contains("allow-offline-mode=false"));
        assertTrue(text.contains("unencrypted Minecraft connection"));
        assertTrue(DialogAuthConfig.load(server, config).onlineMode());
    }

    @Test void readsServerOnlineModeAndExplicitOfflineOptIn() throws Exception {
        Path server = directory.resolve("server.properties");
        Path config = directory.resolve("config.properties");
        Files.writeString(server, "online-mode=false\n");
        Files.writeString(config, "allow-offline-mode=true\n");
        DialogAuthConfig loaded = DialogAuthConfig.load(server, config);
        assertFalse(loaded.onlineMode());
        assertTrue(loaded.allowOfflineMode());
    }

    @Test void missingModeUsesSafeDefaultsAndInvalidBooleanIsRejected() throws Exception {
        Path server = directory.resolve("server.properties");
        Path config = directory.resolve("config.properties");
        Files.writeString(server, "server-port=25565\n");
        Files.writeString(config, "allow-offline-mode=false\n");
        assertTrue(DialogAuthConfig.load(server, config).onlineMode());
        Files.writeString(config, "allow-offline-mode=yes\n");
        assertThrows(IOException.class, () -> DialogAuthConfig.load(server, config));
    }

    @Test void clientKitAndModpackDefaultsAreSafeAndWrittenToNewConfig() throws Exception {
        Path server = directory.resolve("server.properties");
        Path config = directory.resolve("config/dialogauth/config.properties");
        DialogAuthConfig loaded = DialogAuthConfig.load(server, config);
        assertEquals("", loaded.clientDownloadUrl());
        assertEquals("client-kit", loaded.clientKitPath());
        assertEquals("", loaded.clientModpackManifestUrl());
        assertEquals("", loaded.clientModpackServers());
        String text = Files.readString(config);
        assertTrue(text.contains("client-download-url="));
        assertTrue(text.contains("client-kit-path=client-kit"));
        assertTrue(text.contains("client-modpack-manifest-url="));
        assertTrue(text.contains("client-modpack-servers="));
    }

    @Test void readsClientKitAndDownloadUrlValuesWithTrimming() throws Exception {
        Path server = directory.resolve("server.properties");
        Path config = directory.resolve("config.properties");
        Files.writeString(config, "client-download-url= https://example.com/client.zip \n"
                + "client-kit-path= /srv/dialog-auth-client \n");
        DialogAuthConfig loaded = DialogAuthConfig.load(server, config);
        assertEquals("https://example.com/client.zip", loaded.clientDownloadUrl());
        assertEquals("/srv/dialog-auth-client", loaded.clientKitPath());
    }

    @Test void readsModpackSyncValuesWithTrimming() throws Exception {
        Path server = directory.resolve("server.properties");
        Path config = directory.resolve("config.properties");
        Files.writeString(config, "client-modpack-manifest-url= https://example.com/manifest.json \n"
                + "client-modpack-servers= play.example.com, 192.0.2.1 \n");
        DialogAuthConfig loaded = DialogAuthConfig.load(server, config);
        assertEquals("https://example.com/manifest.json", loaded.clientModpackManifestUrl());
        assertEquals("play.example.com, 192.0.2.1", loaded.clientModpackServers());
    }
}
