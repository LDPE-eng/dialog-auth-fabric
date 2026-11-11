package ru.ldpe.dialogauth;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.dialog.AfterAction;
import net.minecraft.dialog.DialogActionButtonData;
import net.minecraft.dialog.DialogButtonData;
import net.minecraft.dialog.DialogCommonData;
import net.minecraft.dialog.action.DynamicCustomDialogAction;
import net.minecraft.dialog.body.PlainMessageDialogBody;
import net.minecraft.dialog.input.TextInputControl;
import net.minecraft.dialog.type.DialogInput;
import net.minecraft.dialog.type.NoticeDialog;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.ShowDialogS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import net.minecraft.server.network.ServerPlayerConfigurationTask;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import ru.ldpe.dialogauth.mixin.ClientConnectionAccessorMixin;
import ru.ldpe.dialogauth.mixin.ServerConfigurationNetworkHandlerAccessorMixin;

public final class AuthTask implements ServerPlayerConfigurationTask {
    public static final Key KEY = new Key("dialogauth:auth");
    public static final Identifier CLICK_EVENT_ID = Identifier.of("dialogauth", "submit");
    private final GameProfile profile;
    private final ClientConnection connection;
    private final ServerConfigurationNetworkHandler handler;
    private AuthService.Session session;
    private UUID challengeId;
    private SecureServerSession secureSession;

    public AuthTask(GameProfile profile, ClientConnection connection, ServerConfigurationNetworkHandler handler) {
        this.profile = profile;
        this.connection = connection;
        this.handler = handler;
    }

    @Override
    public Key getKey() { return KEY; }

    @Override
    public void sendPacket(Consumer<Packet<?>> sender) {
        Channel channel = ((ClientConnectionAccessorMixin) connection).dialogauth$getChannel();
        channel.eventLoop().execute(() -> {
            if (session != null || !channel.isActive()) return;
            String ip = channel.remoteAddress() instanceof InetSocketAddress address && address.getAddress() != null
                    ? address.getAddress().getHostAddress() : "local";
            session = DialogAuth.service().session(profile.getId(), profile.getName(), ip, new AuthService.Transport() {
                public void execute(Runnable action) { channel.eventLoop().execute(action); }
                public Runnable schedule(java.time.Duration delay, Runnable action) {
                    var future = channel.eventLoop().schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                }
                public boolean isOpen() {
                    return channel.isActive() && connection.getPacketListener() == handler
                            && ((ServerConfigurationNetworkHandlerAccessorMixin) handler).dialogauth$getCurrentTask() == AuthTask.this;
                }
                public void dialog(boolean registering, String error) {
                    connection.send(createDialogPacket(registering, error));
                }
                public void success() {
                    if (isOpen()) {
                        ((ServerConfigurationNetworkHandlerAccessorMixin) handler).dialogauth$finishTask(KEY);
                        DialogAuth.LOGGER.info("Player {} ({}) passed Dialog Auth", profile.getName(), profile.getId());
                    }
                }
                public void disconnect(String reason) { AuthTask.disconnect(connection, reason); }
            });
            channel.closeFuture().addListener(future -> session.close());
            if (DialogAuth.offlineEncryptionRequired()) {
                session.start(false);
                if (!ServerConfigurationNetworking.canSend(handler, DialogAuthPayloads.ChallengePayload.ID)) {
                    disconnect(withDownloadLink("Для этого сервера нужен Dialog Auth companion-мод и Fabric API."));
                    return;
                }
                challengeId = UUID.randomUUID();
                connection.send(ServerConfigurationNetworking.createS2CPacket(new DialogAuthPayloads.ChallengePayload(challengeId)));
            } else {
                session.start();
            }
        });
    }

    public void handleClientHello(DialogAuthPayloads.ClientHelloPayload hello) {
        try {
            if (secureSession != null || challengeId == null) return;
            var established = SecureServerSession.establish(DialogAuth.identity(), hello, challengeId);
            secureSession = established.session();
            connection.send(ServerConfigurationNetworking.createS2CPacket(established.packet()));
            session.showDialog();
        } catch (Exception e) {
            DialogAuth.LOGGER.warn("Encrypted authentication handshake failed", e);
            disconnect("Ошибка защищённого handshake Dialog Auth. Переподключитесь.");
        }
    }

    public void handleEncryptedResponse(DialogAuthPayloads.ResponsePayload response) {
        try {
            if (secureSession == null) throw new IllegalStateException("Encrypted handshake is not complete");
            String[] secrets = secureSession.decrypt(response);
            session.submit(secrets[0], secrets[1]);
        } catch (Exception e) {
            DialogAuth.LOGGER.warn("Encrypted authentication response failed", e);
            disconnect("Некорректный зашифрованный ответ авторизации.");
        }
    }

    private void disconnect(String message) {
        disconnect(connection, message);
    }

    public static void disconnect(ClientConnection connection, String message) {
        Text reason = Text.literal(message);
        var accessor = (ClientConnectionAccessorMixin) connection;
        accessor.dialogauth$setDisconnectionInfo(new net.minecraft.network.DisconnectionInfo(reason));
        connection.send(new net.minecraft.network.packet.s2c.common.DisconnectS2CPacket(reason),
                net.minecraft.network.PacketCallbacks.always(() -> accessor.dialogauth$getChannel().close()));
        connection.tryDisableAutoRead();
    }

    private static String withDownloadLink(String message) {
        String url = DialogAuth.clientDownloadUrl();
        if (url.isBlank()) return message;
        return message + " Скачать клиент: " + url;
    }

    public void handleResponse(NbtCompound response) {
        if (DialogAuth.offlineEncryptionRequired()) {
            disconnect(withDownloadLink("Незашифрованный пароль запрещён. Установите Dialog Auth companion-мод."));
            return;
        }
        if (session != null) {
            session.submit(response.getString("password", ""), response.getString("password_confirm", ""));
        }
    }

    private ShowDialogS2CPacket createDialogPacket(boolean registering, String error) {
        var body = new ArrayList<PlainMessageDialogBody>();
        body.add(new PlainMessageDialogBody(Text.literal("Игрок: " + profile.getName()), 300));
        body.add(new PlainMessageDialogBody(Text.literal(registering
                ? "Придумайте пароль (8–128 символов) и повторите его."
                : "Введите пароль от этого аккаунта."), 300));
        if (DialogAuth.offlineEncryptionRequired()) {
            body.add(new PlainMessageDialogBody(Text.literal("Пароль передаётся в зашифрованном виде."), 300));
        } else if (DialogAuth.isOnlineMode()
                && !ServerConfigurationNetworking.canSend(handler, DialogAuthPayloads.ChallengePayload.ID)) {
            body.add(new PlainMessageDialogBody(Text.literal("Без companion-мода автосинхронизация модов недоступна."), 300));
        }
        if (!error.isBlank()) body.add(new PlainMessageDialogBody(Text.literal(error).formatted(Formatting.RED), 300));
        var inputs = new ArrayList<DialogInput>();
        inputs.add(textInput("password", "Пароль"));
        if (registering) inputs.add(textInput("password_confirm", "Повтор пароля"));
        NbtCompound additions = new NbtCompound();
        additions.putString("form", registering ? "register" : "login");
        var common = new DialogCommonData(Text.literal(registering ? "Регистрация" : "Вход на сервер"),
                Optional.empty(), false, true, AfterAction.WAIT_FOR_RESPONSE, new ArrayList<>(body), inputs);
        var dialog = new NoticeDialog(common, new DialogActionButtonData(new DialogButtonData(
                Text.literal(registering ? "Зарегистрироваться" : "Войти"), 300),
                Optional.of(new DynamicCustomDialogAction(CLICK_EVENT_ID, Optional.of(additions)))));
        return new ShowDialogS2CPacket(RegistryEntry.of(dialog));
    }

    private static DialogInput textInput(String key, String label) {
        return new DialogInput(key, new TextInputControl(300, Text.literal(label), true, "", 128, Optional.empty()));
    }
}
