package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

public final class ModpackSync {
    private static final Set<String> BYPASS = new HashSet<>();
    private static URI manifestUri;
    private static Set<String> servers = Set.of();
    private static ModpackTrustStore trustStore;
    private static HttpClient http;

    private ModpackSync() {}

    public static void initialize() {
        Properties properties = new Properties();
        Path file = FabricLoader.getInstance().getConfigDir().resolve("dialogauth/modpack.properties");
        if (Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            } catch (IOException e) {
                throw new IllegalStateException("Could not load modpack synchronization config", e);
            }
        }
        String url = properties.getProperty("url", "");
        manifestUri = url.isBlank() ? null : URI.create(url);
        servers = Set.of(properties.getProperty("servers", "").split("\\s*,\\s*"));
        trustStore = new ModpackTrustStore(FabricLoader.getInstance().getConfigDir().resolve("dialogauth/modpack-public.key"));
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static synchronized boolean shouldIntercept(String address) {
        return manifestUri != null && servers.contains(address) && !BYPASS.contains(address);
    }

    public static void connect(Screen parent, MinecraftClient client, ServerAddress address, ServerInfo info,
                               boolean quickPlay, CookieStorage cookieStorage) {
        ModpackDownloadScreen progress = new ModpackDownloadScreen(parent);
        client.setScreen(progress);
        CompletableFuture.supplyAsync(() -> {
            try {
                var publicKey = trustStore.get(manifestUri, http);
                var manifest = ModpackManifest.fetch(manifestUri, publicKey, http);
                return ModpackInstaller.sync(manifest, manifestUri,
                        FabricLoader.getInstance().getGameDir().resolve("mods"), http);
            } catch (Exception e) {
                throw new ModpackFailure(e);
            }
        }).whenComplete((changed, failure) -> client.execute(() -> {
            if (failure != null) {
                Throwable cause = failure instanceof ModpackFailure wrapper ? wrapper.getCause() : failure;
                progress.status("Ошибка синхронизации: " + cause.getMessage());
                DialogAuthClient.LOGGER.error("Modpack synchronization failed", cause);
                return;
            }
            if (changed.isEmpty()) {
                connectDirect(parent, client, address, info, quickPlay, cookieStorage);
            } else {
                progress.restartRequired();
            }
        }));
    }

    public static synchronized void connectDirect(Screen parent, MinecraftClient client, ServerAddress address,
                                                  ServerInfo info, boolean quickPlay, CookieStorage cookieStorage) {
        BYPASS.add(info.address);
        ConnectScreen.connect(parent, client, address, info, quickPlay, cookieStorage);
    }

    private static final class ModpackFailure extends RuntimeException {
        ModpackFailure(Throwable cause) { super(cause); }
    }
}
