package ru.ldpe.dialogauth;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import ru.ldpe.dialogauth.mixin.ClientConnectionAccessorMixin;

public final class AuthTask implements ConfigurationTask {
    public static final Type TYPE = new Type("dialogauth:auth");
    public static final Identifier CLICK_EVENT_ID = DialogAuthPayloads.CLICK_EVENT_ID;
    private final GameProfile profile;
    private final Connection connection;
    private final ServerConfigurationPacketListenerImpl handler;
    private AuthService.Session session;
    private UUID challengeId;
    private SecureServerSession secureSession;

    public AuthTask(GameProfile profile, Connection connection, ServerConfigurationPacketListenerImpl handler) {
        this.profile = profile;
        this.connection = connection;
        this.handler = handler;
    }

    @Override
    public Type type() { return TYPE; }

    @Override
    public void start(Consumer<Packet<?>> sender) {
        Channel channel = ((ClientConnectionAccessorMixin) connection).dialogauth$getChannel();
        channel.eventLoop().execute(() -> {
            if (session != null || !channel.isActive()) return;
            String ip = channel.remoteAddress() instanceof InetSocketAddress address && address.getAddress() != null
                    ? address.getAddress().getHostAddress() : "local";
            session = DialogAuth.service().session(profile.id(), profile.name(), ip, new AuthService.Transport() {
                public void execute(Runnable action) { channel.eventLoop().execute(action); }
                public Runnable schedule(java.time.Duration delay, Runnable action) {
                    var future = channel.eventLoop().schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                }
                public boolean isOpen() {
                    return channel.isActive() && connection.getPacketListener() == handler && DialogAuth.authTask(handler) == AuthTask.this;
                }
                public void dialog(boolean registering, String error) {
                    if (DialogAuth.isCompanionAuthUi()) {
                        connection.send(ServerConfigurationNetworking.createClientboundPacket(new DialogAuthPayloads.AuthFormPayload(
                                DialogAuthPayloads.ChallengePayload.PROTOCOL_VERSION, registering, error, 8, 128)));
                    } else {
                        connection.send(createDialogPacket(registering, error));
                    }
                }
                public void success() {
                    if (isOpen()) {
                        ((FabricServerConfigurationPacketListenerImpl) handler).completeTask(TYPE);
                        DialogAuth.completeAuthTask(handler);
                        DialogAuth.LOGGER.info("Player {} ({}) passed Dialog Auth", profile.name(), profile.id());
                    }
                }
                public void disconnect(String reason) { AuthTask.disconnect(connection, reason); }
            });
            channel.closeFuture().addListener(future -> session.close());
            if (DialogAuth.companionRequired()) {
                session.start(false);
                if (!ServerConfigurationNetworking.canSend(handler, DialogAuthPayloads.ChallengePayload.TYPE)) {
                    disconnect(withDownloadLink("Для этого сервера нужен Dialog Auth companion-мод и Fabric API."));
                    return;
                }
                challengeId = UUID.randomUUID();
                connection.send(ServerConfigurationNetworking.createClientboundPacket(new DialogAuthPayloads.ChallengePayload(
                        challengeId, DialogAuthPayloads.ChallengePayload.PROTOCOL_VERSION, DialogAuth.serverId())));
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
            connection.send(ServerConfigurationNetworking.createClientboundPacket(established.packet()));
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

    private void disconnect(String message) { disconnect(connection, message); }

    public static void disconnect(Connection connection, String message) {
        Component reason = Component.literal(message);
        var accessor = (ClientConnectionAccessorMixin) connection;
        accessor.dialogauth$setDisconnectionInfo(new DisconnectionDetails(reason));
        connection.send(new ClientboundDisconnectPacket(reason), future -> accessor.dialogauth$getChannel().close());
        connection.setReadOnly();
    }

    private static String withDownloadLink(String message) {
        String url = DialogAuth.clientDownloadUrl();
        return url.isBlank() ? message : message + " Скачать клиент: " + url;
    }

    public void handleResponse(CompoundTag response) {
        if (DialogAuth.companionRequired()) {
            disconnect(withDownloadLink("Незашифрованный пароль запрещён. Установите Dialog Auth companion-мод."));
            return;
        }
        if (session != null) session.submit(response.getStringOr("password", ""), response.getStringOr("password_confirm", ""));
    }

    private ClientboundShowDialogPacket createDialogPacket(boolean registering, String error) {
        var body = new ArrayList<PlainMessage>();
        body.add(new PlainMessage(Component.literal("Игрок: " + profile.name()), 300));
        body.add(new PlainMessage(Component.literal(registering
                ? "Придумайте пароль (8–128 символов) и повторите его."
                : "Введите пароль от этого аккаунта."), 300));
        if (DialogAuth.companionRequired()) {
            body.add(new PlainMessage(Component.literal("Пароль передаётся в зашифрованном виде."), 300));
        } else if (DialogAuth.isOnlineMode()
                && !ServerConfigurationNetworking.canSend(handler, DialogAuthPayloads.ChallengePayload.TYPE)) {
            body.add(new PlainMessage(Component.literal("Без companion-мода автосинхронизация модов недоступна."), 300));
        }
        if (!error.isBlank()) body.add(new PlainMessage(Component.literal(error).withStyle(ChatFormatting.RED), 300));
        var inputs = new ArrayList<Input>();
        inputs.add(textInput("password", "Пароль"));
        if (registering) inputs.add(textInput("password_confirm", "Повтор пароля"));
        CompoundTag additions = new CompoundTag();
        additions.putString("form", registering ? "register" : "login");
        var common = new CommonDialogData(Component.literal(registering ? "Регистрация" : "Вход на сервер"),
                Optional.empty(), false, true, DialogAction.WAIT_FOR_RESPONSE, new ArrayList<>(body), inputs);
        var dialog = new NoticeDialog(common, new ActionButton(new CommonButtonData(
                Component.literal(registering ? "Зарегистрироваться" : "Войти"), 300),
                Optional.of(new CustomAll(CLICK_EVENT_ID, Optional.of(additions)))));
        return new ClientboundShowDialogPacket(Holder.direct(dialog));
    }

    private static Input textInput(String key, String label) {
        return new Input(key, new TextInput(300, Component.literal(label), true, "", 128, Optional.empty()));
    }
}
