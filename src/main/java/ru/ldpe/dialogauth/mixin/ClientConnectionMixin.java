package ru.ldpe.dialogauth.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.AuthTask;
import ru.ldpe.dialogauth.DialogAuth;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Shadow private PacketListener packetListener;

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/ClientConnection;handlePacket(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;)V"),
            cancellable = true)
    private void dialogauth$handleResponse(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof CustomClickActionC2SPacket click) || !click.id().equals(AuthTask.CLICK_EVENT_ID)) return;
        ci.cancel();
        if (!(packetListener instanceof ServerConfigurationNetworkHandler handler)) return;
        var current = ((ServerConfigurationNetworkHandlerAccessorMixin) handler).dialogauth$getCurrentTask();
        if (!(current instanceof AuthTask task)) return;
        var response = click.payload().orElse(null);
        try {
            task.handleResponse(response instanceof NbtCompound compound ? compound : new NbtCompound());
        } catch (RuntimeException e) {
            DialogAuth.LOGGER.error("Unexpected auth response failure", e);
            AuthTask.disconnect((ClientConnection) (Object) this, "Внутренняя ошибка авторизации.");
        }
    }
}
