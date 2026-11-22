package ru.ldpe.dialogauth.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.AuthTask;
import ru.ldpe.dialogauth.DialogAuth;

@Mixin(Connection.class)
public abstract class ClientConnectionMixin {
    @Shadow private PacketListener packetListener;

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"),
            cancellable = true)
    private void dialogauth$handleResponse(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundCustomClickActionPacket click) || !click.id().equals(AuthTask.CLICK_EVENT_ID)) return;
        ci.cancel();
        if (!(packetListener instanceof ServerConfigurationPacketListenerImpl handler)) return;
        AuthTask task = DialogAuth.authTask(handler);
        if (task == null) return;
        var response = click.payload().orElse(null);
        try {
            task.handleResponse(response instanceof CompoundTag compound ? compound : new CompoundTag());
        } catch (RuntimeException e) {
            DialogAuth.LOGGER.error("Unexpected auth response failure", e);
            AuthTask.disconnect((Connection) (Object) this, "Внутренняя ошибка авторизации.");
        }
    }
}
