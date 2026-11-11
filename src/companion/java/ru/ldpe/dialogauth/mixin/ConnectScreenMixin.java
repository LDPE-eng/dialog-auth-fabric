package ru.ldpe.dialogauth.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.ldpe.dialogauth.client.ModpackSync;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private static void dialogauth$syncBeforeConnect(Screen screen, MinecraftClient client, ServerAddress address,
                                                     ServerInfo info, boolean quickPlay, CookieStorage cookieStorage,
                                                     CallbackInfo ci) {
        if (ModpackSync.shouldIntercept(info.address)) {
            ci.cancel();
            ModpackSync.connect(screen, client, address, info, quickPlay, cookieStorage);
        }
    }
}
