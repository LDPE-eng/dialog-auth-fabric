package ru.ldpe.dialogauth.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.DialogAuthPayloads;
import ru.ldpe.dialogauth.client.SecureClientHandler;

@Mixin(Connection.class)
public abstract class ClientOutgoingConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void dialogauth$encryptOutgoing(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ServerboundCustomClickActionPacket click) || !click.id().equals(DialogAuthPayloads.CLICK_EVENT_ID)) return;
        if (SecureClientHandler.encryptResponse((Connection) (Object) this,
                click.payload().map(CompoundTag.class::cast).orElse(new CompoundTag()))) ci.cancel();
    }
}
