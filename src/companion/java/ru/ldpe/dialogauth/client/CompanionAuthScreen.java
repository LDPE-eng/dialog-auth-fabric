package ru.ldpe.dialogauth.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import ru.ldpe.dialogauth.DialogAuthPayloads;

public final class CompanionAuthScreen extends Screen {
    private final Connection connection;
    private final boolean registering;
    private int minLength;
    private int maxLength;
    private String error;
    private boolean busy;
    private EditBox password;
    private EditBox confirmation;
    private Button submitButton;

    private CompanionAuthScreen(Connection connection, DialogAuthPayloads.AuthFormPayload payload) {
        super(Component.literal(payload.registering() ? CompanionAuthStrings.REGISTER_TITLE : CompanionAuthStrings.LOGIN_TITLE));
        this.connection = connection;
        this.registering = payload.registering();
        this.minLength = payload.minPasswordLength();
        this.maxLength = payload.maxPasswordLength();
        this.error = payload.error();
    }

    public static void initialize() {
        ClientConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.AuthFormPayload.TYPE, (payload, context) ->
                context.client().execute(() -> open(context.client(),
                        ((ru.ldpe.dialogauth.mixin.ClientCommonNetworkHandlerAccessorMixin) context.packetListener())
                                .dialogauth$getConnection(), payload)));
    }

    private static void open(Minecraft client, Connection connection, DialogAuthPayloads.AuthFormPayload payload) {
        if (payload.protocolVersion() != DialogAuthPayloads.ChallengePayload.PROTOCOL_VERSION) {
            connection.disconnect(Component.literal("Неподдерживаемая версия Dialog Auth UI."));
            return;
        }
        if (client.screen instanceof CompanionAuthScreen screen && screen.connection == connection) {
            screen.error = payload.error();
            screen.busy = false;
            if (screen.submitButton != null) screen.submitButton.active = true;
        } else {
            client.setScreen(new CompanionAuthScreen(connection, payload));
        }
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(320, width - 40);
        int x = width / 2 - fieldWidth / 2;
        password = new EditBox(font, x, 72, fieldWidth, 20, Component.literal(CompanionAuthStrings.PASSWORD));
        password.setMaxLength(maxLength);
        mask(password);
        addRenderableWidget(password);
        if (registering) {
            confirmation = new EditBox(font, x, 116, fieldWidth, 20, Component.literal(CompanionAuthStrings.CONFIRM));
            confirmation.setMaxLength(maxLength);
            mask(confirmation);
            addRenderableWidget(confirmation);
        }
        int buttonY = registering ? 151 : 116;
        submitButton = Button.builder(Component.literal(registering ? CompanionAuthStrings.REGISTER : CompanionAuthStrings.LOGIN),
                        button -> submit()).bounds(x, buttonY, fieldWidth / 2 - 4, 20).build();
        addRenderableWidget(submitButton);
        addRenderableWidget(Button.builder(Component.literal(CompanionAuthStrings.CANCEL), button -> onClose())
                .bounds(x + fieldWidth / 2 + 4, buttonY, fieldWidth / 2 - 4, 20).build());
        setInitialFocus(password);
    }

    private static void mask(EditBox field) {
        field.addFormatter((value, position) -> Component.literal("•".repeat(value.length())).getVisualOrderText());
    }

    private void submit() {
        if (busy) return;
        String secret = password.getValue();
        String repeated = confirmation == null ? "" : confirmation.getValue();
        if (secret.length() < minLength || secret.length() > maxLength) {
            error = CompanionAuthStrings.LENGTH;
            return;
        }
        if (registering && !secret.equals(repeated)) {
            error = CompanionAuthStrings.MISMATCH;
            return;
        }
        CompoundTag response = new CompoundTag();
        response.putString("password", secret);
        response.putString("password_confirm", repeated);
        if (!SecureClientHandler.encryptResponse(connection, response)) {
            error = CompanionAuthStrings.NOT_READY;
            return;
        }
        busy = true;
        error = "";
        submitButton.active = false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 20, 0xffffff);
        graphics.centeredText(font, CompanionAuthStrings.ENCRYPTED, width / 2, 43, 0x9fc6dd);
        if (busy) graphics.centeredText(font, CompanionAuthStrings.BUSY, width / 2, height - 35, 0x9fc6dd);
        if (!error.isBlank()) graphics.textWithWordWrap(font, Component.literal(error), width / 2 - 160,
                height - 58, 320, 0xff6666);
    }

    @Override
    public void onClose() { connection.disconnect(Component.literal(CompanionAuthStrings.CANCELLED)); }
    @Override
    public boolean isPauseScreen() { return false; }
}
