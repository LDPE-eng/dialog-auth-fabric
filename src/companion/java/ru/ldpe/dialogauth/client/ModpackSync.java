package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

public final class ModpackSync {
    private static final Set<String> BYPASS = new HashSet<>();
    private static ServerProfileStore profiles;
    private static HttpClient http;

    private ModpackSync() {}

    public static void initialize(ServerProfileStore profileStore) {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("dialogauth");
        profiles = profileStore;
        Path file = config.resolve("modpack.properties");
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
                String url = properties.getProperty("url", "");
                for (String address : properties.getProperty("servers", "").split("\\s*,\\s*")) {
                    if (!address.isBlank()) profiles.ensureLegacy(address, url);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not migrate modpack synchronization config", e);
            }
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static synchronized boolean shouldIntercept(String address) {
        if (BYPASS.contains(address)) return false;
        try {
            return profiles.findByAddress(address).isPresent();
        } catch (IOException e) {
            DialogAuthClient.LOGGER.error("Could not resolve Dialog Auth server profile", e);
            return false;
        }
    }

    public static void connect(Screen parent, Minecraft client, ServerAddress address, ServerData info,
                               boolean quickPlay, TransferState transferState) {
        ModpackDownloadScreen progress = new ModpackDownloadScreen(parent);
        client.setScreen(progress);
        CompletableFuture.supplyAsync(() -> {
            try {
                ServerProfileStore.ServerProfile initialProfile = profiles.findByAddress(info.ip).orElseThrow();
                URI uri = URI.create(initialProfile.manifestUrl());
                ModpackTrustStore trust = new ModpackTrustStore(initialProfile.modpackKey(),
                        FabricLoader.getInstance().getConfigDir().resolve("dialogauth/modpack-public.key"));
                ModpackTrustStore.TrustState state = trust.inspect(uri, http);
                ModpackManifest manifest = ModpackManifest.fetch(uri, state.publicKey(), http);
                ServerProfileStore.ServerProfile profile = initialProfile;
                if (!manifest.serverId().isEmpty() && !manifest.serverId().equals(initialProfile.serverId())) {
                    profile = profiles.ensure(manifest.serverId(), info.ip, uri.toString());
                    trust = new ModpackTrustStore(profile.modpackKey());
                    state = trust.inspect(uri, http);
                }
                ModpackChangePlan plan = ModpackChangePlan.create(manifest,
                        FabricLoader.getInstance().getGameDir().resolve("mods"), state.newFingerprint())
                        .withOldFingerprint(state.oldFingerprint());
                return new Prepared(manifest, plan, uri, trust, state);
            } catch (Exception e) {
                throw new ModpackFailure(e);
            }
        }).whenComplete((prepared, failure) -> client.execute(() -> {
            if (failure != null) {
                showFailure(progress, "синхронизации", failure);
                return;
            }
            if (prepared.plan().isEmpty() && !prepared.trustState().requiresConfirmation()) {
                connectDirect(parent, client, address, info, quickPlay, transferState);
                return;
            }
            String serverId = prepared.manifest().serverId().isEmpty() ? info.ip : prepared.manifest().serverId();
            client.setScreen(new ModpackConfirmationScreen(parent, prepared.plan(), serverId, info.ip,
                    () -> install(parent, client, address, info, quickPlay, transferState, prepared),
                    () -> ConfirmLinkScreen.confirmLinkNow(parent, prepared.manifestUri().resolve("/"), false)));
        }));
    }

    private static void install(Screen parent, Minecraft client, ServerAddress address, ServerData info,
                                boolean quickPlay, TransferState transferState, Prepared prepared) {
        ModpackDownloadScreen progress = new ModpackDownloadScreen(parent);
        progress.status("Скачиваем и проверяем выбранные моды…");
        client.setScreen(progress);
        CompletableFuture.supplyAsync(() -> {
            try {
                if (prepared.trustState().requiresConfirmation()) prepared.trust().confirm(prepared.trustState());
                if (prepared.plan().isEmpty()) return java.util.List.<String>of();
                return ModpackInstaller.sync(prepared.manifest(), prepared.manifestUri(),
                        FabricLoader.getInstance().getGameDir().resolve("mods"), http);
            } catch (Exception e) {
                throw new ModpackFailure(e);
            }
        }).whenComplete((changed, failure) -> client.execute(() -> {
            if (failure != null) {
                showFailure(progress, "установки", failure);
            } else if (changed.isEmpty()) {
                connectDirect(parent, client, address, info, quickPlay, transferState);
            } else {
                progress.restartRequired();
            }
        }));
    }

    private static void showFailure(ModpackDownloadScreen progress, String operation, Throwable failure) {
        Throwable cause = failure instanceof ModpackFailure wrapper ? wrapper.getCause() : failure;
        progress.status("Ошибка " + operation + ": " + cause.getMessage());
        DialogAuthClient.LOGGER.error("Modpack " + operation + " failed", cause);
    }

    private record Prepared(ModpackManifest manifest, ModpackChangePlan plan, URI manifestUri,
                            ModpackTrustStore trust, ModpackTrustStore.TrustState trustState) {}

    public static synchronized void connectDirect(Screen parent, Minecraft client, ServerAddress address,
                                                  ServerData info, boolean quickPlay, TransferState transferState) {
        BYPASS.add(info.ip);
        ConnectScreen.startConnecting(parent, client, address, info, quickPlay, transferState);
    }

    private static final class ModpackFailure extends RuntimeException {
        ModpackFailure(Throwable cause) { super(cause); }
    }
}
