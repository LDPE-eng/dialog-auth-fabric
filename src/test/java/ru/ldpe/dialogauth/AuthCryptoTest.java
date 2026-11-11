package ru.ldpe.dialogauth;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthCryptoTest {
    @TempDir Path directory;

    @Test void signedHandshakeDerivesSameKeyAndRejectsTampering() throws Exception {
        var identity = AuthCrypto.generateEd25519();
        var client = AuthCrypto.generateX25519();
        var server = AuthCrypto.generateX25519();
        UUID session = UUID.randomUUID();
        byte[] clientNonce = AuthCrypto.randomBytes(32);
        byte[] serverNonce = AuthCrypto.randomBytes(32);
        byte[] transcript = AuthCrypto.handshakeTranscript(session, clientNonce, serverNonce,
                client.getPublic().getEncoded(), server.getPublic().getEncoded(), identity.getPublic().getEncoded());
        byte[] signature = AuthCrypto.sign(identity.getPrivate(), transcript);
        assertTrue(AuthCrypto.verify(identity.getPublic(), transcript, signature));
        transcript[10] ^= 1;
        assertFalse(AuthCrypto.verify(identity.getPublic(), transcript, signature));
        transcript[10] ^= 1;

        var clientKey = AuthCrypto.deriveKey(client.getPrivate(), server.getPublic(), session, clientNonce, serverNonce,
                client.getPublic().getEncoded(), server.getPublic().getEncoded(), identity.getPublic().getEncoded());
        var serverKey = AuthCrypto.deriveKey(server.getPrivate(), client.getPublic(), session, clientNonce, serverNonce,
                client.getPublic().getEncoded(), server.getPublic().getEncoded(), identity.getPublic().getEncoded());
        assertArrayEquals(clientKey.getEncoded(), serverKey.getEncoded());
        byte[] plain = AuthCrypto.packSecrets("пароль-123", "пароль-123");
        var encrypted = AuthCrypto.encrypt(clientKey, session, 0, plain);
        assertFalse(new String(encrypted.ciphertext(), java.nio.charset.StandardCharsets.UTF_8).contains("пароль-123"));
        assertArrayEquals(new String[]{"пароль-123", "пароль-123"},
                AuthCrypto.unpackSecrets(AuthCrypto.decrypt(serverKey, session, 0, encrypted.nonce(), encrypted.ciphertext())));
        encrypted.ciphertext()[0] ^= 1;
        assertThrows(AEADBadTagException.class,
                () -> AuthCrypto.decrypt(serverKey, session, 0, encrypted.nonce(), encrypted.ciphertext()));
    }

    @Test void serverSessionRejectsReplayAndWrongSequence() throws Exception {
        var identity = new DialogAuthIdentity(directory);
        UUID session = UUID.randomUUID();
        var client = AuthCrypto.generateX25519();
        byte[] clientNonce = AuthCrypto.randomBytes(32);
        var hello = new DialogAuthPayloads.ClientHelloPayload(session, clientNonce, client.getPublic().getEncoded());
        var established = SecureServerSession.establish(identity, hello, session);
        var packet = established.packet();
        byte[] transcript = AuthCrypto.handshakeTranscript(session, clientNonce, packet.serverNonce(),
                client.getPublic().getEncoded(), packet.publicKey(), packet.identityPublicKey());
        assertTrue(AuthCrypto.verify(identity.publicKey(), transcript, packet.signature()));
        var serverPublic = java.security.KeyFactory.getInstance("X25519").generatePublic(
                new java.security.spec.X509EncodedKeySpec(packet.publicKey()));
        var clientKey = AuthCrypto.deriveKey(client.getPrivate(), serverPublic, session, clientNonce, packet.serverNonce(),
                client.getPublic().getEncoded(), packet.publicKey(), packet.identityPublicKey());
        var encrypted = AuthCrypto.encrypt(clientKey, session, 0, AuthCrypto.packSecrets("password", ""));
        var response = new DialogAuthPayloads.ResponsePayload(session, 0, encrypted.nonce(), encrypted.ciphertext());
        assertArrayEquals(new String[]{"password", ""}, established.session().decrypt(response));
        assertThrows(Exception.class, () -> established.session().decrypt(response));
        var future = new DialogAuthPayloads.ResponsePayload(session, 2, encrypted.nonce(), encrypted.ciphertext());
        assertThrows(Exception.class, () -> established.session().decrypt(future));
    }
}
