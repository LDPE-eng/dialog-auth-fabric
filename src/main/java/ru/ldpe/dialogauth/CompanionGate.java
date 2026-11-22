package ru.ldpe.dialogauth;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

final class CompanionGate {
    private CompanionGate() {}

    static void check(ServerConfigurationPacketListenerImpl handler) {
        boolean companionPresent = ServerConfigurationNetworking.canSend(handler, DialogAuthPayloads.ChallengePayload.TYPE);
        DialogAuth.LOGGER.debug("Running early companion gate: companionPresent={}", companionPresent);
        run(companionPresent, DialogAuth.clientDownloadUrl(), handler::disconnect);
    }

    static void run(boolean companionPresent, String downloadUrl, Consumer<Component> disconnect) {
        if (!companionPresent) disconnect.accept(missingCompanionMessage(downloadUrl));
    }

    static Component missingCompanionMessage(String downloadUrl) {
        String message = "Для входа на этот сервер нужен Dialog Auth Companion.";
        if (downloadUrl != null && !downloadUrl.isBlank()) message += "\nСкачать клиентский мод: " + downloadUrl.trim();
        return Component.literal(message + "\nПосле установки перезапустите игру.");
    }
}
