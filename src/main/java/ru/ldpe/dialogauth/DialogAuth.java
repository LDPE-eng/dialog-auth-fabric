package ru.ldpe.dialogauth;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ldpe.dialogauth.mixin.ServerCommonNetworkHandlerAccessorMixin;
import ru.ldpe.dialogauth.mixin.ServerConfigurationNetworkHandlerAccessorMixin;

public final class DialogAuth implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("DialogAuth");
    private static final Map<ServerConfigurationPacketListenerImpl, AuthTask> AUTH_TASKS = new ConcurrentHashMap<>();
    private static AuthService service;
    private static ResetApiServer resetApiServer;
    private static boolean onlineMode;
    private static boolean microsoftAuth;
    private static boolean companionAuthUi;
    private static boolean encryptedOffline;
    private static DialogAuthIdentity identity;
    private static String clientDownloadUrl = "";
    private static String serverId = "";

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
            microsoftAuth = onlineMode && config.microsoftAuth();
            companionAuthUi = config.authUi().equals("companion");
            encryptedOffline = (!onlineMode || microsoftAuth) && config.allowOfflineMode() && config.requireEncryptedAuth();
            clientDownloadUrl = config.clientDownloadUrl();
            serverId = config.serverId();
            if (microsoftAuth) MicrosoftProfileVerifier.configure(config.microsoftProfileUrl());
            if (encryptedOffline || companionAuthUi) identity = new DialogAuthIdentity(configDirectory);
        } catch (Exception e) {
            throw new IllegalStateException("Could not load Dialog Auth configuration", e);
        }
        generateClientDistribution(config);
        AccountStore store = new AccountStore(configDirectory.resolve("accounts.json"));
        service = new AuthService(store, new RateLimiter(System::nanoTime, 16384), 2, 32,
                exception -> LOGGER.error("Authentication storage failure", exception),
                onlineMode && !microsoftAuth, config.allowOfflineMode(), LOGGER::warn);
        if (microsoftAuth) LOGGER.info("Hybrid authentication enabled: verified Microsoft profiles skip the password dialog");
        if (!onlineMode || microsoftAuth) {
            if (encryptedOffline) {
                LOGGER.warn("Offline authentication requires the companion mod and encrypted password responses");
            } else if (config.allowOfflineMode()) {
                LOGGER.warn("Offline authentication is enabled without companion encryption: Microsoft does not verify usernames, "
                        + "an unregistered name can be claimed, and dialog passwords travel unencrypted");
            } else {
                LOGGER.warn("Server is in offline mode, but Dialog Auth offline support is disabled; all players will be disconnected (fail closed)");
            }
        }
        registerCompanionGate();
        registerAuthTasks();
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
        if (config.resetApiEnabled()) {
            try {
                ResetCodeStore resetCodes = new ResetCodeStore(configDirectory.resolve("reset-codes.json"),
                        java.time.Duration.ofMinutes(config.resetCodeTtlMinutes()), java.time.Clock.systemUTC());
                resetCodes.load();
                ResetCommand.register(store, resetCodes);
                resetApiServer = new ResetApiServer(store, resetCodes, config.resetApiToken(), config.resetApiPort());
                resetApiServer.start();
            } catch (Exception e) {
                service.close();
                throw new IllegalStateException("Could not initialize password reset API", e);
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (resetApiServer != null) resetApiServer.close();
            service.close();
        }, "dialogauth-shutdown"));
        LOGGER.info("Dialog Auth initialized: 2 workers, queue 32, timeout 120s; PBKDF2-HMAC-SHA256; online-mode={}",
                config.onlineMode());
    }

    private static void generateClientDistribution(DialogAuthConfig config) {
        if (!config.clientDistributionEnabled()) {
            LOGGER.info("Client distribution is disabled");
            return;
        }
        try {
            ClientDistribution.generateFromFabric(config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate configured client distribution", e);
        }
    }

    private static void registerCompanionGate() {
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((handler, server) -> {
            if (!companionRequired()) return;
            var profile = ((ServerConfigurationNetworkHandlerAccessorMixin) handler).dialogauth$getProfile();
            if (isMicrosoftProfile(profile)) return;
            CompanionGate.check(handler);
        });
    }

    private static void registerAuthTasks() {
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            var profile = ((ServerConfigurationNetworkHandlerAccessorMixin) handler).dialogauth$getProfile();
            if (isMicrosoftProfile(profile)) {
                LOGGER.info("Player {} passed vanilla Microsoft authentication; server password skipped", profile.name());
                return;
            }
            var connection = ((ServerCommonNetworkHandlerAccessorMixin) handler).dialogauth$getConnection();
            AuthTask task = new AuthTask(profile, connection, handler);
            AUTH_TASKS.put(handler, task);
            ((FabricServerConfigurationPacketListenerImpl) handler).addTask(task);
        });
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> AUTH_TASKS.remove(handler));
    }

    private static void registerNetworkHandlers() {
        ServerConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ClientHelloPayload.TYPE, (payload, context) -> {
            AuthTask task = authTask(context.packetListener());
            if (task != null) task.handleClientHello(payload);
        });
        ServerConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ResponsePayload.TYPE, (payload, context) -> {
            AuthTask task = authTask(context.packetListener());
            if (task != null) task.handleEncryptedResponse(payload);
        });
    }

    public static AuthTask authTask(ServerConfigurationPacketListenerImpl handler) { return AUTH_TASKS.get(handler); }
    public static void completeAuthTask(ServerConfigurationPacketListenerImpl handler) { AUTH_TASKS.remove(handler); }
    public static AuthService service() {
        if (service == null) throw new IllegalStateException("Dialog Auth has not been initialized");
        return service;
    }
    public static boolean isOnlineMode() { return onlineMode; }
    public static boolean isMicrosoftAuthEnabled() { return microsoftAuth; }
    public static boolean isMicrosoftProfile(com.mojang.authlib.GameProfile profile) {
        return microsoftAuth && !UUIDUtil.createOfflinePlayerUUID(profile.name()).equals(profile.id());
    }
    public static boolean offlineEncryptionRequired() { return encryptedOffline; }
    public static boolean isCompanionAuthUi() { return companionAuthUi; }
    public static boolean companionRequired() { return encryptedOffline || companionAuthUi; }
    public static String clientDownloadUrl() { return clientDownloadUrl; }
    public static String serverId() { return serverId; }
    public static DialogAuthIdentity identity() {
        if (identity == null) throw new IllegalStateException("Dialog Auth server identity is not initialized");
        return identity;
    }
}
