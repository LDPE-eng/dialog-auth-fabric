package ru.ldpe.dialogauth.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;
import ru.ldpe.dialogauth.DialogAuthPayloads;

public final class CompanionAuthScreen extends Screen {
    private final ClientConnection connection;
    private final boolean registering;
    private int minLength;
    private int maxLength;
    private String error;
    private boolean busy;
    private TextFieldWidget password;
    private TextFieldWidget confirmation;
    private ButtonWidget submitButton;

    private CompanionAuthScreen(ClientConnection connection, DialogAuthPayloads.AuthFormPayload payload) {
        super(Text.literal(payload.registering() ? CompanionAuthStrings.REGISTER_TITLE : CompanionAuthStrings.LOGIN_TITLE));
        this.connection = connection;
        this.registering = payload.registering();
        this.minLength = payload.minPasswordLength();
        this.maxLength = payload.maxPasswordLength();
        this.error = payload.error();
    }

    public static void initialize() {
        ClientConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.AuthFormPayload.ID, (payload, context) ->
                context.client().execute(() -> open(context.client(),
                        ((ru.ldpe.dialogauth.mixin.ClientCommonNetworkHandlerAccessorMixin) context.networkHandler())
                                .dialogauth$getConnection(), payload)));
    }

    private static void open(MinecraftClient client, ClientConnection connection, DialogAuthPayloads.AuthFormPayload payload) {
        if (payload.protocolVersion() != DialogAuthPayloads.ChallengePayload.PROTOCOL_VERSION) {
            connection.disconnect(Text.literal("Неподдерживаемая версия Dialog Auth UI."));
            return;
        }
        if (client.currentScreen instanceof CompanionAuthScreen screen && screen.connection == connection) {
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
        password = new TextFieldWidget(textRenderer, x, 72, fieldWidth, 20, Text.literal(CompanionAuthStrings.PASSWORD));
        password.setMaxLength(maxLength);
        mask(password);
        addDrawableChild(password);
        if (registering) {
            confirmation = new TextFieldWidget(textRenderer, x, 116, fieldWidth, 20,
                    Text.literal(CompanionAuthStrings.CONFIRM));
            confirmation.setMaxLength(maxLength);
            mask(confirmation);
            addDrawableChild(confirmation);
        }
        int buttonY = registering ? 151 : 116;
        submitButton = ButtonWidget.builder(Text.literal(registering ? CompanionAuthStrings.REGISTER : CompanionAuthStrings.LOGIN),
                        button -> submit())
                .dimensions(x, buttonY, fieldWidth / 2 - 4, 20).build();
        addDrawableChild(submitButton);
        addDrawableChild(ButtonWidget.builder(Text.literal(CompanionAuthStrings.CANCEL), button -> close())
                .dimensions(x + fieldWidth / 2 + 4, buttonY, fieldWidth / 2 - 4, 20).build());
        setInitialFocus(password);
    }

    private static void mask(TextFieldWidget field) {
        field.setRenderTextProvider((value, position) -> Text.literal("•".repeat(value.length())).asOrderedText());
    }

    private void submit() {
        if (busy) return;
        String secret = password.getText();
        String repeated = confirmation == null ? "" : confirmation.getText();
        if (secret.length() < minLength || secret.length() > maxLength) {
            error = CompanionAuthStrings.LENGTH;
            return;
        }
        if (registering && !secret.equals(repeated)) {
            error = CompanionAuthStrings.MISMATCH;
            return;
        }
        NbtCompound response = new NbtCompound();
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
    public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xffffff);
        context.drawCenteredTextWithShadow(textRenderer, CompanionAuthStrings.ENCRYPTED, width / 2, 43, 0x9fc6dd);
        if (busy) context.drawCenteredTextWithShadow(textRenderer, CompanionAuthStrings.BUSY, width / 2, height - 35, 0x9fc6dd);
        if (!error.isBlank()) context.drawWrappedTextWithShadow(textRenderer, Text.literal(error), width / 2 - 160,
                height - 58, 320, 0xff6666);
    }

    @Override
    public void close() {
        connection.disconnect(Text.literal(CompanionAuthStrings.CANCELLED));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
