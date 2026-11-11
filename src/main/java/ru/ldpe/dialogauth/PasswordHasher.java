package ru.ldpe.dialogauth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    public static final int ITERATIONS = 210000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static EncodedPassword hash(char[] password) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, ITERATIONS);
        try {
            return new EncodedPassword(Base64.getEncoder().encodeToString(salt),
                    Base64.getEncoder().encodeToString(hash), ITERATIONS);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    public static boolean verify(char[] password, EncodedPassword encoded) throws GeneralSecurityException {
        byte[] salt = Base64.getDecoder().decode(encoded.saltBase64());
        byte[] expected = Base64.getDecoder().decode(encoded.hashBase64());
        byte[] actual = pbkdf2(password, salt, encoded.iterations());
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        if (iterations < 100000 || iterations > 1000000) {
            throw new GeneralSecurityException("Invalid PBKDF2 iteration count");
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    public record EncodedPassword(String saltBase64, String hashBase64, int iterations) {}
}
