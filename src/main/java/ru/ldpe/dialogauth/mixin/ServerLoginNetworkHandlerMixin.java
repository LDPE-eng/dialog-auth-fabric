package ru.ldpe.dialogauth.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.network.chat.Component;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.DialogAuth;
import ru.ldpe.dialogauth.MicrosoftProfileVerifier;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetworkHandlerMixin {
    @Shadow @Final private MinecraftServer server;
    @Shadow @Final private Connection connection;
    @Shadow private String requestedUsername;
    @Unique private boolean dialogauth$resolved;
    @Unique private boolean dialogauth$resolving;

    @Shadow public abstract void disconnect(Component reason);
    @Shadow protected abstract void startClientVerification(com.mojang.authlib.GameProfile profile);

    @Inject(method = "handleHello", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"), cancellable = true)
    private void dialogauth$hybridLogin(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!DialogAuth.isMicrosoftAuthEnabled() || dialogauth$resolved || connection.isMemoryConnection()) return;
        ci.cancel();
        if (dialogauth$resolving) return;
        dialogauth$resolving = true;
        MicrosoftProfileVerifier.lookup(packet.name(), packet.profileId()).whenComplete((decision, error) -> {
            var channel = ((ClientConnectionAccessorMixin) connection).dialogauth$getChannel();
            channel.eventLoop().execute(() -> {
                if (!channel.isActive()) return;
                if (error != null) {
                    DialogAuth.LOGGER.warn("Mojang profile lookup failed for {}: {}", packet.name(), error.getMessage());
                    disconnect(Component.literal("Не удалось проверить Microsoft-аккаунт. Повторите подключение позже."));
                    return;
                }
                if (decision == MicrosoftProfileVerifier.Decision.ONLINE) {
                    dialogauth$resolved = true;
                    ((ServerLoginPacketListenerImpl) (Object) this).handleHello(packet);
                } else {
                    startClientVerification(UUIDUtil.createOfflineProfile(requestedUsername));
                }
            });
        });
    }
}
