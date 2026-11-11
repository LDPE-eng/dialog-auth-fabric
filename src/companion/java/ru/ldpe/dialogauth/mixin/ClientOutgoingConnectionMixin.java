package ru.ldpe.dialogauth.mixin;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.AuthTask;
import ru.ldpe.dialogauth.client.SecureClientHandler;

@Mixin(ClientConnection.class)
public abstract class ClientOutgoingConnectionMixin {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void dialogauth$encryptOutgoing(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof CustomClickActionC2SPacket click) || !click.id().equals(AuthTask.CLICK_EVENT_ID)) return;
        if (SecureClientHandler.encryptResponse((ClientConnection) (Object) this,
                click.payload().map(NbtCompound.class::cast).orElse(new NbtCompound()))) {
            ci.cancel();
        }
    }
}
