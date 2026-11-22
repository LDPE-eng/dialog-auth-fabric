package ru.ldpe.dialogauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public interface ServerConfigurationNetworkHandlerAccessorMixin {
    @Invoker("playerProfile") GameProfile dialogauth$getProfile();
}
