package ru.ldpe.dialogauth;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.KeyAgreement;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AuthCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AuthCrypto() {}

    public static KeyPair generateX25519() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    public static KeyPair generateEd25519() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    public static byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return value;
    }

    public static byte[] handshakeTranscript(UUID sessionId, byte[] clientNonce, byte[] serverNonce,
                                             byte[] clientPublic, byte[] serverPublic, byte[] identityPublic) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.write("DialogAuth/1".getBytes(StandardCharsets.UTF_8));
            write(output, uuidBytes(sessionId));
            write(output, clientNonce);
            write(output, serverNonce);
            write(output, clientPublic);
            write(output, serverPublic);
            write(output, identityPublic);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] sign(PrivateKey key, byte[] content) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(content);
        return signature.sign();
    }

    public static boolean verify(PublicKey key, byte[] content, byte[] signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(content);
        return verifier.verify(signature);
    }

    public static SecretKey deriveKey(PrivateKey privateKey, PublicKey peerPublic, UUID sessionId,
                                      byte[] clientNonce, byte[] serverNonce, byte[] clientPublic,
                                      byte[] serverPublic, byte[] identityPublic) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(peerPublic, true);
        byte[] shared = agreement.generateSecret();
        try {
            byte[] salt = new byte[clientNonce.length + serverNonce.length];
            System.arraycopy(clientNonce, 0, salt, 0, clientNonce.length);
            System.arraycopy(serverNonce, 0, salt, clientNonce.length, serverNonce.length);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] info = handshakeTranscript(sessionId, clientNonce, serverNonce, clientPublic, serverPublic, identityPublic);
            byte[] okm = mac.doFinal(info);
            byte[] key = new byte[32];
            System.arraycopy(okm, 0, key, 0, key.length);
            java.util.Arrays.fill(prk, (byte) 0);
            java.util.Arrays.fill(okm, (byte) 0);
            return new SecretKeySpec(key, "AES");
        } finally {
            java.util.Arrays.fill(shared, (byte) 0);
        }
    }

    public static Encrypted encrypt(SecretKey key, UUID sessionId, int sequence, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(sessionId, sequence));
        return new Encrypted(nonce, cipher.doFinal(plaintext));
    }

    public static byte[] decrypt(SecretKey key, UUID sessionId, int sequence, byte[] nonce, byte[] ciphertext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(sessionId, sequence));
        return cipher.doFinal(ciphertext);
    }

    public static byte[] packSecrets(String password, String confirmation) {
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] confirmationBytes = confirmation.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + passwordBytes.length + confirmationBytes.length)
                .putShort((short) passwordBytes.length).put(passwordBytes)
                .putShort((short) confirmationBytes.length).put(confirmationBytes).array();
    }

    public static String[] unpackSecrets(byte[] packed) throws GeneralSecurityException {
        ByteBuffer buffer = ByteBuffer.wrap(packed);
        if (buffer.remaining() < 4) throw new GeneralSecurityException("Truncated password response");
        byte[] password = read(buffer);
        byte[] confirmation = read(buffer);
        if (buffer.hasRemaining()) throw new GeneralSecurityException("Trailing password response data");
        return new String[] {new String(password, StandardCharsets.UTF_8), new String(confirmation, StandardCharsets.UTF_8)};
    }

    public static String fingerprint(PublicKey key) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(ByteBuffer buffer) throws GeneralSecurityException {
        int length = Short.toUnsignedInt(buffer.getShort());
        if (length > 1024 || length > buffer.remaining()) throw new GeneralSecurityException("Invalid password response length");
        byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }

    private static byte[] aad(UUID sessionId, int sequence) {
        return (sessionId + ":response:" + sequence).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }

    private static void write(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    public record Encrypted(byte[] nonce, byte[] ciphertext) {}
}
