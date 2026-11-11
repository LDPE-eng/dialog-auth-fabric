package ru.ldpe.dialogauth;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ldpe.dialogauth.mixin.ServerConfigurationNetworkHandlerAccessorMixin;

public final class DialogAuth implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("DialogAuth");
    private static AuthService service;
    private static boolean onlineMode;
    private static boolean encryptedOffline;
    private static DialogAuthIdentity identity;
    private static String clientDownloadUrl = "";
    private static String clientKitPath = "client-kit";
    private static String clientModpackManifestUrl = "";
    private static String clientModpackServers = "";

    @Override
    public void onInitialize() {
        DialogAuthPayloads.initialize();
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) return;
        var configDirectory = FabricLoader.getInstance().getConfigDir().resolve("dialogauth");
        DialogAuthConfig config;
        try {
            config = DialogAuthConfig.load(FabricLoader.getInstance().getGameDir().resolve("server.properties"),
                    configDirectory.resolve("config.properties"));
            onlineMode = config.onlineMode();
            encryptedOffline = !config.onlineMode() && config.allowOfflineMode() && config.requireEncryptedAuth();
            clientDownloadUrl = config.clientDownloadUrl();
            clientKitPath = config.clientKitPath();
            clientModpackManifestUrl = config.clientModpackManifestUrl();
            clientModpackServers = config.clientModpackServers();
            if (encryptedOffline) identity = new DialogAuthIdentity(configDirectory);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load Dialog Auth configuration", e);
        }
        generateClientKit();
        AccountStore store = new AccountStore(configDirectory.resolve("accounts.json"));
        service = new AuthService(store, new RateLimiter(System::nanoTime, 16384), 2, 32,
                exception -> LOGGER.error("Authentication storage failure", exception),
                config.onlineMode(), config.allowOfflineMode(), LOGGER::warn);
        if (!config.onlineMode()) {
            if (encryptedOffline) {
                LOGGER.warn("Offline authentication requires the companion mod and encrypted password responses");
            } else if (config.allowOfflineMode()) {
                LOGGER.warn("Offline authentication is enabled without companion encryption: Microsoft does not verify usernames, "
                        + "an unregistered name can be claimed, and dialog passwords travel unencrypted");
            } else {
                LOGGER.warn("Server is in offline mode, but Dialog Auth offline support is disabled; "
                        + "all players will be disconnected (fail closed)");
            }
        }
        registerNetworkHandlers();
        CompletableFuture<Void> loaded = new CompletableFuture<>();
        service.initialize(() -> {
            try {
                store.load();
                loaded.complete(null);
            } catch (Exception e) {
                loaded.completeExceptionally(e);
            }
        });
        try {
            loaded.join();
        } catch (RuntimeException e) {
            service.close();
            throw new IllegalStateException("Could not initialize Dialog Auth", e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "dialogauth-shutdown"));
        LOGGER.info("Dialog Auth initialized: 2 workers, queue 32, timeout 120s; PBKDF2-HMAC-SHA256; online-mode={}",
                config.onlineMode());
    }

    private static void generateClientKit() {
        if (clientKitPath.isBlank()) {
            LOGGER.info("Dialog Auth client kit generation is disabled (client-kit-path is empty)");
            return;
        }
        try {
            Path directory = Path.of(clientKitPath);
            if (!directory.isAbsolute()) directory = FabricLoader.getInstance().getGameDir().resolve(directory);
            Path output = directory.resolve("dialog-auth-client.zip");
            ClientKitGenerator.generateFromFabric(output, clientModpackManifestUrl, clientModpackServers);
            LOGGER.info("Dialog Auth client kit written to {}", output);
        } catch (Exception e) {
            LOGGER.error("Could not generate the Dialog Auth client kit", e);
        }
    }

    private static void registerNetworkHandlers() {
        ServerConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ClientHelloPayload.ID, (payload, context) -> {
            if (((ServerConfigurationNetworkHandlerAccessorMixin) context.networkHandler()).dialogauth$getCurrentTask()
                    instanceof AuthTask task) {
                task.handleClientHello(payload);
            }
        });
        ServerConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ResponsePayload.ID, (payload, context) -> {
            if (((ServerConfigurationNetworkHandlerAccessorMixin) context.networkHandler()).dialogauth$getCurrentTask()
                    instanceof AuthTask task) {
                task.handleEncryptedResponse(payload);
            }
        });
    }

    public static AuthService service() {
        if (service == null) throw new IllegalStateException("Dialog Auth has not been initialized");
        return service;
    }

    public static boolean isOnlineMode() { return onlineMode; }
    public static boolean offlineEncryptionRequired() { return encryptedOffline; }
    public static String clientDownloadUrl() { return clientDownloadUrl; }
    public static DialogAuthIdentity identity() {
        if (identity == null) throw new IllegalStateException("Dialog Auth server identity is not initialized");
        return identity;
    }
}
