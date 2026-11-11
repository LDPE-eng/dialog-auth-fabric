package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import ru.ldpe.dialogauth.AuthCrypto;

public final class AuthServerTrustStore {
    private final Path file;

    public AuthServerTrustStore(Path file) {
        this.file = file;
    }

    public synchronized PublicKey verifyOrPin(PublicKey presented) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                PublicKey pinned = KeyFactory.getInstance("Ed25519").generatePublic(
                        new X509EncodedKeySpec(Files.readAllBytes(file)));
                if (!pinned.equals(presented)) throw new IOException("Server identity key changed; expected fingerprint "
                        + AuthCrypto.fingerprint(pinned) + ", got " + AuthCrypto.fingerprint(presented));
                return pinned;
            } catch (GeneralSecurityException e) {
                throw new IOException("Invalid pinned server key", e);
            }
        }
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temporary, presented.getEncoded());
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        DialogAuthClient.LOGGER.warn("Pinned new Dialog Auth server fingerprint: {}", AuthCrypto.fingerprint(presented));
        return presented;
    }
}
