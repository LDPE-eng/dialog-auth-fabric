package ru.ldpe.dialogauth;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.text.Text;

final class CompanionGate {
    private CompanionGate() {}

    static void check(ServerConfigurationNetworkHandler handler) {
        boolean companionPresent = ServerConfigurationNetworking.canSend(handler, DialogAuthPayloads.ChallengePayload.ID);
        DialogAuth.LOGGER.debug("Running early companion gate: companionPresent={}", companionPresent);
        run(companionPresent, DialogAuth.clientDownloadUrl(), handler::disconnect);
    }

    static void run(boolean companionPresent, String downloadUrl, Consumer<Text> disconnect) {
        if (!companionPresent) {
            disconnect.accept(missingCompanionMessage(downloadUrl));
        }
    }

    static Text missingCompanionMessage(String downloadUrl) {
        String message = "Для входа на этот сервер нужен Dialog Auth Companion.";
        if (downloadUrl != null && !downloadUrl.isBlank()) {
            message += "\nСкачать клиентский мод: " + downloadUrl.trim();
        }
        return Text.literal(message + "\nПосле установки перезапустите игру.");
    }
}
