package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import ru.ldpe.dialogauth.AuthCrypto;

public final class ModpackTrustStore {
    public record TrustState(PublicKey publicKey, String oldFingerprint, String newFingerprint,
                             boolean firstUse, boolean changed) {
        public boolean requiresConfirmation() { return firstUse || changed; }
    }

    private final Path file;
    private final Path legacyFile;

    public ModpackTrustStore(Path file) {
        this(file, null);
    }

    public ModpackTrustStore(Path file, Path legacyFile) {
        this.file = file;
        this.legacyFile = legacyFile;
    }

    public synchronized TrustState inspect(URI manifestUri, HttpClient http) throws IOException {
        byte[] presentedBytes = ModpackManifest.getBytes(http, manifestUri.resolve("modpack-public.key"), 4096);
        PublicKey presented = read(presentedBytes, "Invalid downloaded modpack public key");
        PublicKey pinned = trusted();
        if (pinned == null && legacyFile != null && Files.isRegularFile(legacyFile) && !Files.exists(file)) {
            pinned = read(Files.readAllBytes(legacyFile), "Invalid legacy modpack public key");
        }
        String newFingerprint = AuthCrypto.fingerprint(presented);
        if (pinned == null) return new TrustState(presented, "", newFingerprint, true, false);
        String oldFingerprint = AuthCrypto.fingerprint(pinned);
        return new TrustState(presented, oldFingerprint, newFingerprint, false, !pinned.equals(presented));
    }

    public synchronized PublicKey trusted() throws IOException {
        if (!Files.isRegularFile(file)) return null;
        return read(Files.readAllBytes(file), "Invalid pinned modpack public key");
    }

    public synchronized void confirm(TrustState state) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temporary, state.publicKey().getEncoded());
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public PublicKey get(URI manifestUri, HttpClient http) throws IOException {
        TrustState state = inspect(manifestUri, http);
        if (state.requiresConfirmation()) throw new IOException("Modpack key requires confirmation: " + state.newFingerprint());
        return state.publicKey();
    }

    private static PublicKey read(byte[] bytes, String message) throws IOException {
        try {
            return ModpackManifest.readPublicKey(bytes);
        } catch (GeneralSecurityException e) {
            throw new IOException(message, e);
        }
    }
}
