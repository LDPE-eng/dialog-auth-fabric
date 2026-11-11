package ru.ldpe.dialogauth;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;
import javax.crypto.SecretKey;

public final class SecureServerSession {
    private final UUID sessionId;
    private final SecretKey key;
    private int expectedSequence;

    private SecureServerSession(UUID sessionId, SecretKey key) {
        this.sessionId = sessionId;
        this.key = key;
    }

    public static Established establish(DialogAuthIdentity identity, DialogAuthPayloads.ClientHelloPayload hello,
                                        UUID expectedSession) throws GeneralSecurityException {
        if (!hello.sessionId().equals(expectedSession)) throw new GeneralSecurityException("Unexpected authentication session");
        KeyPair serverKeys = AuthCrypto.generateX25519();
        byte[] serverNonce = AuthCrypto.randomBytes(32);
        byte[] clientPublic = hello.publicKey();
        byte[] serverPublic = serverKeys.getPublic().getEncoded();
        byte[] identityPublic = identity.publicKey().getEncoded();
        byte[] transcript = AuthCrypto.handshakeTranscript(hello.sessionId(), hello.nonce(), serverNonce,
                clientPublic, serverPublic, identityPublic);
        byte[] signature = AuthCrypto.sign(identity.privateKey(), transcript);
        PublicKey clientKey = java.security.KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(clientPublic));
        SecretKey key = AuthCrypto.deriveKey(serverKeys.getPrivate(), clientKey, hello.sessionId(), hello.nonce(),
                serverNonce, clientPublic, serverPublic, identityPublic);
        var packet = new DialogAuthPayloads.ServerHelloPayload(hello.sessionId(), hello.nonce(), serverNonce,
                serverPublic, identityPublic, signature);
        return new Established(packet, new SecureServerSession(hello.sessionId(), key));
    }

    public String[] decrypt(DialogAuthPayloads.ResponsePayload response) throws GeneralSecurityException {
        if (!response.sessionId().equals(sessionId) || response.sequence() != expectedSequence) {
            throw new GeneralSecurityException("Unexpected or replayed authentication response");
        }
        String[] secrets = AuthCrypto.unpackSecrets(AuthCrypto.decrypt(key, sessionId, expectedSequence,
                response.nonce(), response.ciphertext()));
        expectedSequence++;
        return secrets;
    }

    public record Established(DialogAuthPayloads.ServerHelloPayload packet, SecureServerSession session) {}
}
