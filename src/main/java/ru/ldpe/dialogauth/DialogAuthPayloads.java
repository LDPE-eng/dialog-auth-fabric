package ru.ldpe.dialogauth;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class DialogAuthPayloads {
    public static final Identifier CLICK_EVENT_ID = Identifier.fromNamespaceAndPath("dialogauth", "submit");
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private DialogAuthPayloads() {}

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        PayloadTypeRegistry.clientboundConfiguration().register(ChallengePayload.TYPE, ChallengePayload.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(AuthFormPayload.TYPE, AuthFormPayload.CODEC);
        PayloadTypeRegistry.clientboundConfiguration().register(ServerHelloPayload.TYPE, ServerHelloPayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(ClientHelloPayload.TYPE, ClientHelloPayload.CODEC);
        PayloadTypeRegistry.serverboundConfiguration().register(ResponsePayload.TYPE, ResponsePayload.CODEC);
    }

    public record ChallengePayload(UUID sessionId, int protocolVersion, String serverId) implements CustomPacketPayload {
        public static final int PROTOCOL_VERSION = 1;
        public static final Type<ChallengePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dialogauth", "challenge"));
        public static final StreamCodec<FriendlyByteBuf, ChallengePayload> CODEC = StreamCodec.ofMember(ChallengePayload::write, ChallengePayload::new);
        public ChallengePayload(UUID sessionId) { this(sessionId, PROTOCOL_VERSION, ""); }
        public ChallengePayload(FriendlyByteBuf buf) { this(buf.readUUID(), buf.readVarInt(), buf.readUtf(64)); }
        private void write(FriendlyByteBuf buf) { buf.writeUUID(sessionId); buf.writeVarInt(protocolVersion); buf.writeUtf(serverId, 64); }
        public Type<ChallengePayload> type() { return TYPE; }
    }

    public record AuthFormPayload(int protocolVersion, boolean registering, String error,
                                  int minPasswordLength, int maxPasswordLength) implements CustomPacketPayload {
        public static final Type<AuthFormPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dialogauth", "auth_form"));
        public static final StreamCodec<FriendlyByteBuf, AuthFormPayload> CODEC = StreamCodec.ofMember(AuthFormPayload::write, AuthFormPayload::new);
        public AuthFormPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readBoolean(), buf.readUtf(256), buf.readVarInt(), buf.readVarInt());
        }
        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeBoolean(registering);
            buf.writeUtf(error, 256);
            buf.writeVarInt(minPasswordLength);
            buf.writeVarInt(maxPasswordLength);
        }
        public Type<AuthFormPayload> type() { return TYPE; }
    }

    public record ClientHelloPayload(UUID sessionId, byte[] nonce, byte[] publicKey) implements CustomPacketPayload {
        public static final Type<ClientHelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dialogauth", "client_hello"));
        public static final StreamCodec<FriendlyByteBuf, ClientHelloPayload> CODEC = StreamCodec.ofMember(ClientHelloPayload::write, ClientHelloPayload::new);
        public ClientHelloPayload(FriendlyByteBuf buf) { this(buf.readUUID(), buf.readByteArray(), buf.readByteArray()); }
        private void write(FriendlyByteBuf buf) { buf.writeUUID(sessionId); buf.writeByteArray(nonce); buf.writeByteArray(publicKey); }
        public Type<ClientHelloPayload> type() { return TYPE; }
    }

    public record ServerHelloPayload(UUID sessionId, byte[] clientNonce, byte[] serverNonce, byte[] publicKey,
                                     byte[] identityPublicKey, byte[] signature) implements CustomPacketPayload {
        public static final Type<ServerHelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dialogauth", "server_hello"));
        public static final StreamCodec<FriendlyByteBuf, ServerHelloPayload> CODEC = StreamCodec.ofMember(ServerHelloPayload::write, ServerHelloPayload::new);
        public ServerHelloPayload(FriendlyByteBuf buf) {
            this(buf.readUUID(), buf.readByteArray(), buf.readByteArray(), buf.readByteArray(), buf.readByteArray(), buf.readByteArray());
        }
        private void write(FriendlyByteBuf buf) {
            buf.writeUUID(sessionId);
            buf.writeByteArray(clientNonce);
            buf.writeByteArray(serverNonce);
            buf.writeByteArray(publicKey);
            buf.writeByteArray(identityPublicKey);
            buf.writeByteArray(signature);
        }
        public Type<ServerHelloPayload> type() { return TYPE; }
    }

    public record ResponsePayload(UUID sessionId, int sequence, byte[] nonce, byte[] ciphertext) implements CustomPacketPayload {
        public static final Type<ResponsePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("dialogauth", "response"));
        public static final StreamCodec<FriendlyByteBuf, ResponsePayload> CODEC = StreamCodec.ofMember(ResponsePayload::write, ResponsePayload::new);
        public ResponsePayload(FriendlyByteBuf buf) { this(buf.readUUID(), buf.readVarInt(), buf.readByteArray(), buf.readByteArray()); }
        private void write(FriendlyByteBuf buf) {
            buf.writeUUID(sessionId);
            buf.writeVarInt(sequence);
            buf.writeByteArray(nonce);
            buf.writeByteArray(ciphertext);
        }
        public Type<ResponsePayload> type() { return TYPE; }
    }
}
