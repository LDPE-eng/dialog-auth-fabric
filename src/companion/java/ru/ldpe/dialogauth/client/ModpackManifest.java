package ru.ldpe.dialogauth.client;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ModpackManifest(int formatVersion, String minecraft, String fabricLoader, List<Mod> mods) {
    private static final Gson GSON = new Gson();
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    public static final int MAX_MOD_BYTES = 64 * 1024 * 1024;

    public static ModpackManifest fetch(URI uri, PublicKey publicKey, HttpClient http) throws IOException {
        try {
            byte[] json = getBytes(http, uri, MAX_MANIFEST_BYTES);
            byte[] signature = getBytes(http, uri.resolve("manifest.sig"), 4096);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(json);
            if (!verifier.verify(signature)) throw new IOException("Invalid modpack manifest signature");
            ModpackManifest manifest = GSON.fromJson(new String(json, java.nio.charset.StandardCharsets.UTF_8), ModpackManifest.class);
            manifest.validate();
            return manifest;
        } catch (GeneralSecurityException | JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid signed modpack manifest", e);
        }
    }

    public static PublicKey readPublicKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static byte[] getBytes(HttpClient http, URI uri, int limit) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + uri);
            if (response.body().length > limit) throw new IOException("Response too large: " + uri);
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void validate() throws IOException {
        if (formatVersion != 1 || minecraft == null || fabricLoader == null || mods == null) {
            throw new IOException("Unsupported modpack manifest");
        }
        Set<String> files = new HashSet<>();
        for (Mod mod : mods) {
            if (mod == null || mod.file() == null || mod.url() == null || mod.sha256() == null) {
                throw new IOException("Invalid mod entry");
            }
            if (!mod.file().matches("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar") || !files.add(mod.file())) {
                throw new IOException("Unsafe or duplicate mod filename: " + mod.file());
            }
            if (mod.size() <= 0 || mod.size() > MAX_MOD_BYTES || !mod.sha256().matches("[0-9a-fA-F]{64}")) {
                throw new IOException("Invalid mod size or hash: " + mod.file());
            }
            String scheme = URI.create(mod.url()).getScheme();
            if (scheme != null && !scheme.equals("http") && !scheme.equals("https")) {
                throw new IOException("Unsupported mod URL scheme");
            }
        }
    }

    public record Mod(String id, String version, String file, String url, String sha256, long size) {}
}
