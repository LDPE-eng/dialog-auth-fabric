package ru.ldpe.dialogauth.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.client.ModpackSync;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
    private static void dialogauth$syncBeforeConnect(Screen screen, Minecraft client, ServerAddress address,
                                                     ServerData info, boolean quickPlay, TransferState transferState,
                                                     CallbackInfo ci) {
        if (ModpackSync.shouldIntercept(info.ip)) {
            ci.cancel();
            ModpackSync.connect(screen, client, address, info, quickPlay, transferState);
        }
    }
}
