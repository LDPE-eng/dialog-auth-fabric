package ru.ldpe.dialogauth.mixin;

import com.mojang.authlib.GameProfile;
import java.util.Queue;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.AuthTask;

@Mixin(ServerConfigurationNetworkHandler.class)
public abstract class ServerConfigurationNetworkHandlerMixin {
    @Shadow @Final private Queue<ServerPlayerConfigurationTask> tasks;
    @Shadow protected abstract GameProfile getProfile();

    @Inject(method = "queueSendResourcePackTask()V", at = @At("RETURN"))
    private void dialogauth$queueAuthentication(CallbackInfo ci) {
        var handler = (ServerConfigurationNetworkHandler) (Object) this;
        tasks.add(new AuthTask(getProfile(),
                ((ServerCommonNetworkHandlerAccessorMixin) handler).dialogauth$getConnection(), handler));
    }
}
