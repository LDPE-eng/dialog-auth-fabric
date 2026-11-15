package ru.ldpe.dialogauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerConfigurationNetworkHandler.class)
public interface ServerConfigurationNetworkHandlerAccessorMixin {
    @Accessor("currentTask")
    ServerPlayerConfigurationTask dialogauth$getCurrentTask();

    @Invoker("getProfile")
    GameProfile dialogauth$getProfile();

    @Invoker("onTaskFinished")
    void dialogauth$finishTask(ServerPlayerConfigurationTask.Key key);
}
