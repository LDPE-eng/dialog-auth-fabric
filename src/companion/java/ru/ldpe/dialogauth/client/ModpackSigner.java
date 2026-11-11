package ru.ldpe.dialogauth.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class ModpackSigner {
    private ModpackSigner() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("generate")) {
            Files.createDirectories(argsPath(args[1]).getParent());
            var generator = KeyPairGenerator.getInstance("Ed25519");
            var pair = generator.generateKeyPair();
            write(argsPath(args[1]), pair.getPrivate().getEncoded());
            write(argsPath(args[2]), pair.getPublic().getEncoded());
            Files.setPosixFilePermissions(argsPath(args[1]), PosixFilePermissions.fromString("rw-------"));
            System.out.println("Public key: " + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            return;
        }
        if (args.length == 4 && args[0].equals("sign")) {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(argsPath(args[1]))));
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            byte[] manifest = Files.readAllBytes(argsPath(args[2]));
            signer.update(manifest);
            write(argsPath(args[3]), signer.sign());
            return;
        }
        throw new IllegalArgumentException("Usage: generate private.key public.key | sign private.key manifest.json manifest.sig");
    }

    private static Path argsPath(String value) {
        return Path.of(value).toAbsolutePath();
    }

    private static void write(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }
}
