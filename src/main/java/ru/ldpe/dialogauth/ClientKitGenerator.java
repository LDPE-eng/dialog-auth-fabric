package ru.ldpe.dialogauth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

public final class ClientKitGenerator {
    public static final String COMPANION_RESOURCE = "/assets/dialogauth/client-kit/dialog-auth-companion.jar";
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final Pattern SAFE_JAR_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*\\.jar");

    private ClientKitGenerator() {}

    public static void generateFromFabric(Path outputFile, String clientModpackManifestUrl, String clientModpackServers)
            throws IOException {
        FabricLoader loader = FabricLoader.getInstance();
        ModContainer server = loader.getModContainer("dialogauth")
                .orElseThrow(() -> new IllegalStateException("Dialog Auth mod container is not present"));
        ModContainer fabricApi = loader.getModContainer("fabric-api")
                .orElseThrow(() -> new IllegalStateException("Fabric API mod container is not present"));
        String version = server.getMetadata().getVersion().getFriendlyString();
        Path ownJar = productionJar(server, "Dialog Auth");
        Path fabricApiJar = productionJar(fabricApi, "Fabric API");
        try (InputStream companion = ClientKitGenerator.class.getResourceAsStream(COMPANION_RESOURCE)) {
            if (companion == null) throw new IOException("Missing embedded companion resource: " + COMPANION_RESOURCE);
            generate(outputFile, version, ownJar, companion, fabricApiJar,
                    clientModpackManifestUrl, clientModpackServers);
        }
    }

    private static Path productionJar(ModContainer container, String label) throws IOException {
        Path fromOrigin = firstJar(container.getOrigin().getPaths());
        if (fromOrigin != null) return fromOrigin;
        Path fromRoot = firstJar(container.getRootPaths());
        if (fromRoot == null) throw new IOException(label + " production jar was not found");
        return fromRoot;
    }

    private static Path firstJar(List<Path> paths) {
        for (Path path : paths) {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) return path;
        }
        return null;
    }

    public static void generate(Path outputFile, String version, Path ownJar, InputStream companion,
                                Path fabricApiJar) throws IOException {
        generate(outputFile, version, ownJar, companion, fabricApiJar, "", "");
    }

    public static void generate(Path outputFile, String version, Path ownJar, InputStream companion,
                                Path fabricApiJar, String clientModpackManifestUrl, String clientModpackServers)
            throws IOException {
        Path target = outputFile.toAbsolutePath();
        requireSafeToken(version, "version");
        Path ownPath = ownJar.toAbsolutePath();
        if (!Files.isRegularFile(ownPath)) throw new IOException("Server jar was not found: " + ownPath);
        String fabricApiName = fabricApiJar.toAbsolutePath().getFileName().toString();
        requireSafeJarName(fabricApiName, "Fabric API jar");
        if (!fabricApiName.startsWith("fabric-api-")) {
            throw new IOException("Fabric API jar must be named fabric-api-<version>.jar but was " + fabricApiName);
        }
        String serverEntry = "dialog-auth-" + version + ".jar";
        String companionEntry = "dialog-auth-companion-" + version + ".jar";
        String manifestUrl = clientModpackManifestUrl == null ? "" : clientModpackManifestUrl.trim();
        String servers = clientModpackServers == null ? "" : clientModpackServers.trim();
        boolean includeModpackConfig = !manifestUrl.isBlank() && !servers.isBlank();
        if (includeModpackConfig) {
            requirePropertyValue(manifestUrl, "client-modpack-manifest-url");
            requirePropertyValue(servers, "client-modpack-servers");
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "dialog-auth-client-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                addFile(zip, "mods/" + serverEntry, ownPath);
                addStream(zip, "mods/" + companionEntry, companion);
                addFile(zip, "mods/" + fabricApiName, fabricApiJar);
                addText(zip, "INSTALL.txt", installText(serverEntry, companionEntry, fabricApiName));
                if (includeModpackConfig) {
                    addText(zip, "config/dialogauth/modpack.properties",
                            "url=" + manifestUrl + "\nservers=" + servers + "\n");
                }
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void addFile(ZipOutputStream zip, String entry, Path file) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        Files.copy(file, zip);
        zip.closeEntry();
    }

    private static void addStream(ZipOutputStream zip, String entry, InputStream input) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        input.transferTo(zip);
        zip.closeEntry();
    }

    private static void addText(ZipOutputStream zip, String entry, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void requireSafeToken(String value, String label) throws IOException {
        if (!SAFE_TOKEN.matcher(value).matches()) throw new IOException("Unsafe " + label + ": " + value);
    }

    private static void requireSafeJarName(String value, String label) throws IOException {
        if (!SAFE_JAR_NAME.matcher(value).matches()) throw new IOException("Unsafe " + label + ": " + value);
    }

    private static void requirePropertyValue(String value, String label) throws IOException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\u0000') >= 0) {
            throw new IOException("Unsafe " + label + " value");
        }
    }

    private static String installText(String serverJar, String companionJar, String fabricApiJar) {
        return """
                Dialog Auth - клиентский набор для Prism Launcher

                Установка:
                1. Если у вас была версия 1.3.0, один раз удалите из папки mods экземпляра
                   старые dialog-auth-1.3.0.jar и dialog-auth-companion-1.3.0.jar.
                2. Откройте Prism Launcher.
                3. Создайте экземпляр Minecraft 1.21.6 с Fabric Loader или откройте существующий.
                4. Скопируйте в папку mods этого экземпляра файлы из папки mods архива:
                   - %s
                   - %s
                   - %s
                5. Если в архиве есть config/dialogauth/modpack.properties, скопируйте папку config в папку экземпляра — моды будут синхронизироваться автоматически.
                6. Запустите экземпляр и подключитесь к серверу.

                Companion-мод шифрует пароль перед отправкой на сервер.
                В архиве нет серверных настроек, accounts.json и миров.
                """.formatted(serverJar, companionJar, fabricApiJar);
    }
}
