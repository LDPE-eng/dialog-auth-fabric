package ru.ldpe.dialogauth;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class DialogAuthPayloads {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private DialogAuthPayloads() {}

    public static void initialize() {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        PayloadTypeRegistry.configurationS2C().register(ChallengePayload.ID, ChallengePayload.CODEC);
        PayloadTypeRegistry.configurationS2C().register(ServerHelloPayload.ID, ServerHelloPayload.CODEC);
        PayloadTypeRegistry.configurationC2S().register(ClientHelloPayload.ID, ClientHelloPayload.CODEC);
        PayloadTypeRegistry.configurationC2S().register(ResponsePayload.ID, ResponsePayload.CODEC);
    }

    public record ChallengePayload(UUID sessionId) implements CustomPayload {
        public static final Id<ChallengePayload> ID = new Id<>(Identifier.of("dialogauth", "challenge"));
        public static final PacketCodec<PacketByteBuf, ChallengePayload> CODEC = PacketCodec.of(ChallengePayload::write, ChallengePayload::new);
        public ChallengePayload(PacketByteBuf buf) { this(buf.readUuid()); }
        private void write(PacketByteBuf buf) { buf.writeUuid(sessionId); }
        public Id<ChallengePayload> getId() { return ID; }
    }

    public record ClientHelloPayload(UUID sessionId, byte[] nonce, byte[] publicKey) implements CustomPayload {
        public static final Id<ClientHelloPayload> ID = new Id<>(Identifier.of("dialogauth", "client_hello"));
        public static final PacketCodec<PacketByteBuf, ClientHelloPayload> CODEC = PacketCodec.of(ClientHelloPayload::write, ClientHelloPayload::new);
        public ClientHelloPayload(PacketByteBuf buf) { this(buf.readUuid(), buf.readByteArray(), buf.readByteArray()); }
        private void write(PacketByteBuf buf) { buf.writeUuid(sessionId); buf.writeByteArray(nonce); buf.writeByteArray(publicKey); }
        public Id<ClientHelloPayload> getId() { return ID; }
    }

    public record ServerHelloPayload(UUID sessionId, byte[] clientNonce, byte[] serverNonce, byte[] publicKey,
                                     byte[] identityPublicKey, byte[] signature) implements CustomPayload {
        public static final Id<ServerHelloPayload> ID = new Id<>(Identifier.of("dialogauth", "server_hello"));
        public static final PacketCodec<PacketByteBuf, ServerHelloPayload> CODEC = PacketCodec.of(ServerHelloPayload::write, ServerHelloPayload::new);
        public ServerHelloPayload(PacketByteBuf buf) {
            this(buf.readUuid(), buf.readByteArray(), buf.readByteArray(), buf.readByteArray(), buf.readByteArray(), buf.readByteArray());
        }
        private void write(PacketByteBuf buf) {
            buf.writeUuid(sessionId);
            buf.writeByteArray(clientNonce);
            buf.writeByteArray(serverNonce);
            buf.writeByteArray(publicKey);
            buf.writeByteArray(identityPublicKey);
            buf.writeByteArray(signature);
        }
        public Id<ServerHelloPayload> getId() { return ID; }
    }

    public record ResponsePayload(UUID sessionId, int sequence, byte[] nonce, byte[] ciphertext) implements CustomPayload {
        public static final Id<ResponsePayload> ID = new Id<>(Identifier.of("dialogauth", "response"));
        public static final PacketCodec<PacketByteBuf, ResponsePayload> CODEC = PacketCodec.of(ResponsePayload::write, ResponsePayload::new);
        public ResponsePayload(PacketByteBuf buf) { this(buf.readUuid(), buf.readVarInt(), buf.readByteArray(), buf.readByteArray()); }
        private void write(PacketByteBuf buf) {
            buf.writeUuid(sessionId);
            buf.writeVarInt(sequence);
            buf.writeByteArray(nonce);
            buf.writeByteArray(ciphertext);
        }
        public Id<ResponsePayload> getId() { return ID; }
    }
}
