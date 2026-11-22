package ru.ldpe.dialogauth.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Connection.class)
public interface ClientConnectionAccessorMixin {
    @Accessor("channel") Channel dialogauth$getChannel();
    @Accessor("disconnectionDetails") void dialogauth$setDisconnectionInfo(DisconnectionDetails info);
}
