package ru.ldpe.dialogauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public final class DialogAuthIdentity {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public DialogAuthIdentity(Path directory) throws IOException, GeneralSecurityException {
        Path privateFile = directory.resolve("identity-private.key");
        Path publicFile = directory.resolve("identity-public.key");
        if (Files.exists(privateFile)) {
            privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)));
            publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(publicFile)));
        } else {
            KeyPair pair = AuthCrypto.generateEd25519();
            privateKey = pair.getPrivate();
            publicKey = pair.getPublic();
            Files.createDirectories(directory);
            Files.write(privateFile, privateKey.getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.write(publicFile, publicKey.getEncoded(), StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(publicFile, PosixFilePermissions.fromString("rw-r--r--"));
        }
        DialogAuth.LOGGER.info("Dialog Auth server fingerprint: {}", AuthCrypto.fingerprint(publicKey));
    }

    public PublicKey publicKey() { return publicKey; }
    public PrivateKey privateKey() { return privateKey; }
}
