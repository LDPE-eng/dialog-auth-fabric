package ru.ldpe.dialogauth.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class ModpackDownloadScreen extends ConfirmScreen {
    private final Screen parent;
    private final boolean restart;

    public ModpackDownloadScreen(Screen parent) {
        this(parent, "Проверяем сборку сервера…", false);
    }

    private ModpackDownloadScreen(Screen parent, String status, boolean restart) {
        super(button -> {
            if (!button) MinecraftClient.getInstance().setScreen(parent);
        }, Text.literal(restart ? "Dialog Auth — требуется перезапуск" : "Dialog Auth — синхронизация модов"),
                Text.literal(status));
        this.parent = parent;
        this.restart = restart;
    }

    public void status(String value) {
        MinecraftClient.getInstance().setScreen(new ModpackDownloadScreen(parent, value, restart));
    }

    public void restartRequired() {
        MinecraftClient.getInstance().setScreen(new ModpackDownloadScreen(parent,
                "Моды установлены с резервной копией прежних файлов. Перезапустите Minecraft перед подключением.", true));
    }
}
