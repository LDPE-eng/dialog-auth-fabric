package ru.ldpe.dialogauth.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ldpe.dialogauth.DialogAuthPayloads;

public final class DialogAuthClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("DialogAuth/Client");

    @Override
    public void onInitializeClient() {
        DialogAuthPayloads.initialize();
        ServerProfileStore profiles = new ServerProfileStore(
                FabricLoader.getInstance().getConfigDir().resolve("dialogauth"));
        SecureClientHandler.initialize(profiles);
        CompanionAuthScreen.initialize();
        ModpackSync.initialize(profiles);
    }
}
