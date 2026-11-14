package ru.ldpe.dialogauth.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.DialogAuth;
import ru.ldpe.dialogauth.MicrosoftProfileVerifier;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private ClientConnection connection;
    @Shadow @Final private String profileName;
    @Unique private boolean dialogauth$resolved;
    @Unique private boolean dialogauth$resolving;

    @Shadow public abstract void disconnect(Text reason);
    @Shadow abstract void startVerify(com.mojang.authlib.GameProfile profile);

    @Inject(method = "onHello", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isOnlineMode()Z"), cancellable = true)
    private void dialogauth$hybridLogin(LoginHelloC2SPacket packet, CallbackInfo ci) {
        if (!DialogAuth.isMicrosoftAuthEnabled() || dialogauth$resolved || connection.isLocal()) return;
        ci.cancel();
        if (dialogauth$resolving) return;
        dialogauth$resolving = true;
        MicrosoftProfileVerifier.lookup(packet.name(), packet.profileId()).whenComplete((decision, error) -> {
            var channel = ((ClientConnectionAccessorMixin) connection).dialogauth$getChannel();
            channel.eventLoop().execute(() -> {
                if (!channel.isActive()) return;
                if (error != null) {
                    DialogAuth.LOGGER.warn("Mojang profile lookup failed for {}: {}", packet.name(), error.getMessage());
                    disconnect(Text.literal("Не удалось проверить Microsoft-аккаунт. Повторите подключение позже."));
                    return;
                }
                if (decision == MicrosoftProfileVerifier.Decision.ONLINE) {
                    dialogauth$resolved = true;
                    ((ServerLoginNetworkHandler) (Object) this).onHello(packet);
                } else {
                    startVerify(Uuids.getOfflinePlayerProfile(profileName));
                }
            });
        });
    }
}
