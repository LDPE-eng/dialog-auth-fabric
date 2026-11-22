package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class DialogAuthPayloadsTest {
    @Test void challengeCarriesVersionAndStableServerId() {
        UUID session = UUID.randomUUID();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        DialogAuthPayloads.ChallengePayload.CODEC.encode(buffer,
                new DialogAuthPayloads.ChallengePayload(session, 1, "gang"));
        DialogAuthPayloads.ChallengePayload decoded = DialogAuthPayloads.ChallengePayload.CODEC.decode(buffer);
        assertEquals(session, decoded.sessionId());
        assertEquals(1, decoded.protocolVersion());
        assertEquals("gang", decoded.serverId());
        assertFalse(buffer.isReadable());
    }

    @Test void companionFormCarriesPolicyAndErrorButNoSecrets() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        DialogAuthPayloads.AuthFormPayload.CODEC.encode(buffer,
                new DialogAuthPayloads.AuthFormPayload(1, true, "Ошибка", 8, 128));
        DialogAuthPayloads.AuthFormPayload decoded = DialogAuthPayloads.AuthFormPayload.CODEC.decode(buffer);
        assertEquals(1, decoded.protocolVersion());
        assertTrue(decoded.registering());
        assertEquals("Ошибка", decoded.error());
        assertEquals(8, decoded.minPasswordLength());
        assertEquals(128, decoded.maxPasswordLength());
        assertFalse(buffer.isReadable());
    }
}
