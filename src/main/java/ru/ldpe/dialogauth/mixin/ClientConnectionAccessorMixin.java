package ru.ldpe.dialogauth.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientConnection.class)
public interface ClientConnectionAccessorMixin {
    @Accessor("channel")
    Channel dialogauth$getChannel();

    @Accessor("disconnectionInfo")
    void dialogauth$setDisconnectionInfo(net.minecraft.network.DisconnectionInfo info);
}
