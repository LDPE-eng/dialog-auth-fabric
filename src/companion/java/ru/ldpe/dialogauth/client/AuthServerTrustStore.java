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
    public record Change(PublicKey presented, String oldFingerprint, String newFingerprint) {}

    private final Path file;
    private final Path legacyFile;

    public AuthServerTrustStore(Path file) {
        this(file, null);
    }

    public AuthServerTrustStore(Path file, Path legacyFile) {
        this.file = file;
        this.legacyFile = legacyFile;
    }

    public synchronized PublicKey verifyOrPin(PublicKey presented) throws IOException {
        PublicKey pinned = trusted();
        if (pinned == null) {
            pin(presented);
            DialogAuthClient.LOGGER.warn("Pinned Dialog Auth server fingerprint for this profile: {}",
                    AuthCrypto.fingerprint(presented));
            return presented;
        }
        if (!pinned.equals(presented)) throw new IOException("Server identity key changed; expected fingerprint "
                + AuthCrypto.fingerprint(pinned) + ", got " + AuthCrypto.fingerprint(presented));
        return pinned;
    }

    public synchronized Change inspectChange(PublicKey presented) throws IOException {
        PublicKey pinned = trusted();
        if (pinned == null || pinned.equals(presented)) return null;
        return new Change(presented, AuthCrypto.fingerprint(pinned), AuthCrypto.fingerprint(presented));
    }

    public synchronized void confirmChange(Change change) throws IOException {
        if (trusted() == null) throw new IOException("Cannot replace an uninitialized auth identity");
        pin(change.presented());
    }

    public synchronized PublicKey trusted() throws IOException {
        Path effective = file;
        if (!Files.isRegularFile(file) && legacyFile != null && Files.isRegularFile(legacyFile)) effective = legacyFile;
        if (!Files.isRegularFile(effective)) return null;
        return read(effective);
    }

    private PublicKey read(Path path) throws IOException {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(path)));
        } catch (GeneralSecurityException e) {
            throw new IOException("Invalid pinned server key", e);
        }
    }

    private void pin(PublicKey presented) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temporary, presented.getEncoded());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
