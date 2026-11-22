package ru.ldpe.dialogauth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ModpackDownloadScreen extends ConfirmScreen {
    private final Screen parent;
    private final boolean restart;

    public ModpackDownloadScreen(Screen parent) { this(parent, "Проверяем сборку сервера…", false); }

    private ModpackDownloadScreen(Screen parent, String status, boolean restart) {
        super(button -> { if (!button) Minecraft.getInstance().setScreen(parent); },
                Component.literal(restart ? "Dialog Auth — требуется перезапуск" : "Dialog Auth — синхронизация модов"),
                Component.literal(status));
        this.parent = parent;
        this.restart = restart;
    }

    public void status(String value) {
        Minecraft.getInstance().setScreen(new ModpackDownloadScreen(parent, value, restart));
    }

    public void restartRequired() {
        Minecraft.getInstance().setScreen(new ModpackDownloadScreen(parent,
                "Моды установлены с резервной копией прежних файлов. Перезапустите Minecraft перед подключением.", true));
    }
}
