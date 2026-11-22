package ru.ldpe.dialogauth.client;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import ru.ldpe.dialogauth.AuthCrypto;
import ru.ldpe.dialogauth.DialogAuthPayloads;
import ru.ldpe.dialogauth.mixin.ClientCommonNetworkHandlerAccessorMixin;
import ru.ldpe.dialogauth.mixin.ClientConnectionAccessorMixin;

public final class SecureClientHandler {
    private static final Map<Connection, ClientSession> SESSIONS = new ConcurrentHashMap<>();
    private static ServerProfileStore profiles;

    private SecureClientHandler() {}

    public static void initialize(ServerProfileStore profileStore) {
        profiles = profileStore;
        ClientConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ChallengePayload.TYPE,
                (payload, context) -> challenge(connection(context.packetListener()), payload));
        ClientConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ServerHelloPayload.TYPE,
                (payload, context) -> serverHello(context.client(), connection(context.packetListener()), payload));
    }

    private static Connection connection(ClientCommonPacketListenerImpl handler) {
        return ((ClientCommonNetworkHandlerAccessorMixin) handler).dialogauth$getConnection();
    }

    private static void challenge(Connection connection, DialogAuthPayloads.ChallengePayload payload) {
        try {
            if (payload.protocolVersion() != DialogAuthPayloads.ChallengePayload.PROTOCOL_VERSION) {
                throw new GeneralSecurityException("Unsupported Dialog Auth protocol version");
            }
            String address = connection.getRemoteAddress().toString().replaceFirst("^/", "");
            ServerProfileStore.ServerProfile profile = payload.serverId().isBlank()
                    ? profiles.ensureLegacy(address, "") : profiles.ensure(payload.serverId(), address, "");
            Path legacy = FabricLoader.getInstance().getConfigDir().resolve("dialogauth/auth-server-public.key");
            AuthServerTrustStore trust = new AuthServerTrustStore(profile.authKey(), legacy);
            ClientSession session = new ClientSession(payload.sessionId(), AuthCrypto.generateX25519(), AuthCrypto.randomBytes(32), trust);
            SESSIONS.put(connection, session);
            ((ClientConnectionAccessorMixin) connection).dialogauth$getChannel().closeFuture()
                    .addListener(future -> SESSIONS.remove(connection));
            ClientConfigurationNetworking.send(new DialogAuthPayloads.ClientHelloPayload(
                    payload.sessionId(), session.nonce, session.keys.getPublic().getEncoded()));
        } catch (Exception e) {
            fail(connection, e);
        }
    }

    private static void serverHello(Minecraft client, Connection connection, DialogAuthPayloads.ServerHelloPayload payload) {
        ClientSession session = SESSIONS.get(connection);
        if (session == null || !session.sessionId.equals(payload.sessionId())
                || !MessageDigest.isEqual(session.nonce, payload.clientNonce())) return;
        try {
            java.security.PublicKey presented = ed25519Key(payload.identityPublicKey());
            AuthServerTrustStore.Change change = session.trust.inspectChange(presented);
            if (change != null) {
                if (session.identityPrompt) return;
                session.identityPrompt = true;
                client.execute(() -> client.setScreen(new ConfirmScreen(accepted -> {
                    if (!accepted) {
                        connection.disconnect(Component.literal("Изменение ключа Dialog Auth отклонено."));
                        return;
                    }
                    try {
                        session.trust.confirmChange(change);
                        session.identity = change.presented();
                        completeHandshake(session, payload);
                    } catch (Exception e) {
                        fail(connection, e);
                    }
                }, Component.literal("Dialog Auth — изменился ключ сервера"),
                        Component.literal("Прежний fingerprint:\n" + change.oldFingerprint()
                                + "\nНовый fingerprint:\n" + change.newFingerprint()
                                + "\nПродолжить только если администратор подтвердил смену ключа."))));
                return;
            }
            session.identity = session.trust.verifyOrPin(presented);
            completeHandshake(session, payload);
        } catch (Exception e) {
            fail(connection, e);
        }
    }

    private static void completeHandshake(ClientSession session, DialogAuthPayloads.ServerHelloPayload payload) throws Exception {
        byte[] transcript = AuthCrypto.handshakeTranscript(payload.sessionId(), payload.clientNonce(),
                payload.serverNonce(), session.keys.getPublic().getEncoded(), payload.publicKey(), payload.identityPublicKey());
        if (!AuthCrypto.verify(session.identity, transcript, payload.signature())) {
            throw new GeneralSecurityException("Invalid server handshake signature");
        }
        session.aesKey = AuthCrypto.deriveKey(session.keys.getPrivate(), key(payload.publicKey()), payload.sessionId(),
                payload.clientNonce(), payload.serverNonce(), session.keys.getPublic().getEncoded(),
                payload.publicKey(), payload.identityPublicKey());
    }

    public static boolean encryptResponse(Connection connection, CompoundTag response) {
        ClientSession session = SESSIONS.get(connection);
        if (session == null) return false;
        try {
            if (session.aesKey == null) throw new GeneralSecurityException("Encrypted handshake is not complete");
            int sequence = session.sequence++;
            String password = response.getStringOr("password", "");
            var encrypted = AuthCrypto.encrypt(session.aesKey, session.sessionId, sequence,
                    AuthCrypto.packSecrets(password, response.getStringOr("password_confirm", "")));
            ClientConfigurationNetworking.send(new DialogAuthPayloads.ResponsePayload(
                    session.sessionId, sequence, encrypted.nonce(), encrypted.ciphertext()));
        } catch (Exception e) {
            fail(connection, e);
        }
        return true;
    }

    private static java.security.PublicKey key(byte[] encoded) throws GeneralSecurityException {
        return java.security.KeyFactory.getInstance("X25519").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
    }

    private static java.security.PublicKey ed25519Key(byte[] encoded) throws GeneralSecurityException {
        return java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded));
    }

    private static void fail(Connection connection, Exception error) {
        SESSIONS.remove(connection);
        DialogAuthClient.LOGGER.error("Encrypted authentication failed", error);
        connection.disconnect(Component.literal("Не удалось установить защищённое соединение Dialog Auth: " + error.getMessage()));
    }

    private static final class ClientSession {
        final UUID sessionId;
        final KeyPair keys;
        final byte[] nonce;
        final AuthServerTrustStore trust;
        SecretKey aesKey;
        java.security.PublicKey identity;
        int sequence;
        boolean identityPrompt;

        ClientSession(UUID sessionId, KeyPair keys, byte[] nonce, AuthServerTrustStore trust) {
            this.sessionId = sessionId;
            this.keys = keys;
            this.nonce = nonce;
            this.trust = trust;
        }
    }
}
