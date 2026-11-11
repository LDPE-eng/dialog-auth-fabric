package ru.ldpe.dialogauth.client;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;
import ru.ldpe.dialogauth.AuthCrypto;
import ru.ldpe.dialogauth.DialogAuthPayloads;
import ru.ldpe.dialogauth.mixin.ClientCommonNetworkHandlerAccessorMixin;
import ru.ldpe.dialogauth.mixin.ClientConnectionAccessorMixin;

public final class SecureClientHandler {
    private static final Map<ClientConnection, ClientSession> SESSIONS = new ConcurrentHashMap<>();
    private static AuthServerTrustStore trustStore;

    private SecureClientHandler() {}

    public static void initialize(AuthServerTrustStore store) {
        trustStore = store;
        ClientConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ChallengePayload.ID,
                (payload, context) -> challenge(connection(context.networkHandler()), payload));
        ClientConfigurationNetworking.registerGlobalReceiver(DialogAuthPayloads.ServerHelloPayload.ID,
                (payload, context) -> serverHello(context.client(), connection(context.networkHandler()), payload));
    }

    private static ClientConnection connection(net.minecraft.client.network.ClientCommonNetworkHandler handler) {
        return ((ClientCommonNetworkHandlerAccessorMixin) handler).dialogauth$getConnection();
    }

    private static void challenge(ClientConnection connection, DialogAuthPayloads.ChallengePayload payload) {
        try {
            ClientSession session = new ClientSession(payload.sessionId(), AuthCrypto.generateX25519(), AuthCrypto.randomBytes(32));
            SESSIONS.put(connection, session);
            ((ClientConnectionAccessorMixin) connection).dialogauth$getChannel().closeFuture()
                    .addListener(future -> SESSIONS.remove(connection));
            ClientConfigurationNetworking.send(new DialogAuthPayloads.ClientHelloPayload(
                    payload.sessionId(), session.nonce, session.keys.getPublic().getEncoded()));
        } catch (GeneralSecurityException e) {
            fail(connection, e);
        }
    }

    private static void serverHello(MinecraftClient client, ClientConnection connection, DialogAuthPayloads.ServerHelloPayload payload) {
        ClientSession session = SESSIONS.get(connection);
        if (session == null || !session.sessionId.equals(payload.sessionId())
                || !MessageDigest.isEqual(session.nonce, payload.clientNonce())) return;
        try {
            session.identity = trustStore.verifyOrPin(ed25519Key(payload.identityPublicKey()));
            byte[] transcript = AuthCrypto.handshakeTranscript(payload.sessionId(), payload.clientNonce(),
                    payload.serverNonce(), session.keys.getPublic().getEncoded(), payload.publicKey(), payload.identityPublicKey());
            if (!AuthCrypto.verify(session.identity, transcript, payload.signature())) {
                throw new GeneralSecurityException("Invalid server handshake signature");
            }
            session.aesKey = AuthCrypto.deriveKey(session.keys.getPrivate(), key(payload.publicKey()), payload.sessionId(),
                    payload.clientNonce(), payload.serverNonce(), session.keys.getPublic().getEncoded(),
                    payload.publicKey(), payload.identityPublicKey());
        } catch (Exception e) {
            fail(connection, e);
        }
    }

    public static boolean encryptResponse(ClientConnection connection, NbtCompound response) {
        ClientSession session = SESSIONS.get(connection);
        if (session == null) return false;
        try {
            if (session.aesKey == null) throw new GeneralSecurityException("Encrypted handshake is not complete");
            int sequence = session.sequence++;
            String password = response.getString("password", "");
            var encrypted = AuthCrypto.encrypt(session.aesKey, session.sessionId, sequence,
                    AuthCrypto.packSecrets(password, response.getString("password_confirm", "")));
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

    private static void fail(ClientConnection connection, Exception error) {
        SESSIONS.remove(connection);
        DialogAuthClient.LOGGER.error("Encrypted authentication failed", error);
        connection.disconnect(Text.literal("Не удалось установить защищённое соединение Dialog Auth: " + error.getMessage()));
    }

    private static final class ClientSession {
        final UUID sessionId;
        final KeyPair keys;
        final byte[] nonce;
        SecretKey aesKey;
        java.security.PublicKey identity;
        int sequence;

        ClientSession(UUID sessionId, KeyPair keys, byte[] nonce) {
            this.sessionId = sessionId;
            this.keys = keys;
            this.nonce = nonce;
        }
    }
}
