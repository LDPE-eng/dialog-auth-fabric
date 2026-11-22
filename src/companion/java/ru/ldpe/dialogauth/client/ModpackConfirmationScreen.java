package ru.ldpe.dialogauth.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ModpackConfirmationScreen extends Screen {
    private final Screen parent;
    private final ModpackChangePlan plan;
    private final String serverId;
    private final String address;
    private final Runnable install;
    private final Runnable manual;

    public ModpackConfirmationScreen(Screen parent, ModpackChangePlan plan, String serverId, String address,
                                     Runnable install, Runnable manual) {
        super(Component.literal("Dialog Auth — подтверждение сборки"));
        this.parent = parent;
        this.plan = plan;
        this.serverId = serverId;
        this.address = address;
        this.install = install;
        this.manual = manual;
    }

    @Override
    protected void init() {
        String report = plan.report(serverId, address);
        MultiLineEditBox text = MultiLineEditBox.builder().setX(12).setY(38).setShowBackground(true)
                .build(font, width - 24, height - 112, Component.literal(report));
        text.setValue(report);
        addRenderableWidget(text);
        int buttonWidth = Math.min(200, (width - 36) / 2);
        int left = width / 2 - buttonWidth - 6;
        int right = width / 2 + 6;
        int firstRow = height - 60;
        int secondRow = height - 32;
        addRenderableWidget(Button.builder(Component.literal("Проверить и установить"), button -> install.run())
                .bounds(left, firstRow, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Установить вручную"), button -> manual.run())
                .bounds(right, firstRow, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Скопировать отчёт"), button ->
                        Minecraft.getInstance().keyboardHandler.setClipboard(report))
                .bounds(left, secondRow, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Отмена"), button -> onClose())
                .bounds(right, secondRow, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 14, 0xffffff);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
    @Override
    public boolean isPauseScreen() { return false; }
}
