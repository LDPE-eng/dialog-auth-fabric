package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;

public final class ModpackTrustStore {
    private final Path file;

    public ModpackTrustStore(Path file) {
        this.file = file;
    }

    public PublicKey get(URI manifestUri, HttpClient http) throws IOException {
        if (Files.isRegularFile(file)) {
            try {
                return ModpackManifest.readPublicKey(Files.readAllBytes(file));
            } catch (GeneralSecurityException e) {
                throw new IOException("Invalid pinned modpack public key", e);
            }
        }
        try {
            byte[] key = ModpackManifest.getBytes(http, manifestUri.resolve("modpack-public.key"), 4096);
            PublicKey publicKey = ModpackManifest.readPublicKey(key);
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, key);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return publicKey;
        } catch (GeneralSecurityException e) {
            throw new IOException("Invalid modpack public key", e);
        }
    }
}
