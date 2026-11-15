package ru.ldpe.dialogauth.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ScrollableTextWidget;
import net.minecraft.text.Text;

public final class ModpackConfirmationScreen extends Screen {
    private final Screen parent;
    private final ModpackChangePlan plan;
    private final String serverId;
    private final String address;
    private final Runnable install;
    private final Runnable manual;

    public ModpackConfirmationScreen(Screen parent, ModpackChangePlan plan, String serverId, String address,
                                     Runnable install, Runnable manual) {
        super(Text.literal("Dialog Auth — подтверждение сборки"));
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
        addDrawableChild(new ScrollableTextWidget(12, 38, width - 24, height - 112,
                Text.literal(report), textRenderer));
        int buttonWidth = Math.min(200, (width - 36) / 2);
        int left = width / 2 - buttonWidth - 6;
        int right = width / 2 + 6;
        int firstRow = height - 60;
        int secondRow = height - 32;
        addDrawableChild(ButtonWidget.builder(Text.literal("Проверить и установить"), button -> install.run())
                .dimensions(left, firstRow, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Установить вручную"), button -> manual.run())
                .dimensions(right, firstRow, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Скопировать отчёт"), button ->
                        MinecraftClient.getInstance().keyboard.setClipboard(report))
                .dimensions(left, secondRow, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> close())
                .dimensions(right, secondRow, buttonWidth, 20).build());
    }

    @Override
    public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xffffff);
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
