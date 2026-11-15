package ru.ldpe.dialogauth.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record ModpackChangePlan(List<Change> changes, String fingerprint) {
    public enum Type {
        INSTALL("Установка"), UPDATE("Обновление"), REMOVE("Удаление старой версии"), UNCHANGED("Без изменений");
        private final String label;
        Type(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Change(Type type, ModpackManifest.Mod mod, String oldVersion, String oldFile) {}

    public static ModpackChangePlan create(ModpackManifest manifest, Path modsDirectory, String fingerprint)
            throws IOException {
        Map<String, ModpackInstaller.ModInfo> installed = new HashMap<>();
        Map<String, Path> installedFiles = new HashMap<>();
        if (Files.isDirectory(modsDirectory)) {
            try (var paths = Files.list(modsDirectory)) {
                for (Path jar : paths.toList()) {
                    if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) continue;
                    ModpackInstaller.ModInfo info = ModpackInstaller.readModInfo(jar);
                    if (info != null) {
                        installed.put(info.id(), info);
                        installedFiles.put(info.id(), jar);
                    }
                }
            }
        }
        List<Change> changes = new ArrayList<>();
        for (ModpackManifest.Mod mod : manifest.mods()) {
            Path target = modsDirectory.resolve(mod.file());
            Path old = installedFiles.get(mod.id());
            boolean sameFile = old == null || old.equals(target);
            boolean current = sameFile && Files.isRegularFile(target)
                    && ModpackInstaller.hash(target).equalsIgnoreCase(mod.sha256());
            String oldVersion = installed.containsKey(mod.id()) ? installed.get(mod.id()).version() : "";
            String oldFile = old == null ? "" : old.getFileName().toString();
            if (current) {
                changes.add(new Change(Type.UNCHANGED, mod, oldVersion, oldFile));
            } else {
                changes.add(new Change(oldVersion.isEmpty() ? Type.INSTALL : Type.UPDATE, mod, oldVersion, oldFile));
            }
            if (old != null && !sameFile) changes.add(new Change(Type.REMOVE, mod, oldVersion, oldFile));
        }
        return new ModpackChangePlan(List.copyOf(changes), fingerprint);
    }

    public boolean isEmpty() {
        return changes.stream().allMatch(change -> change.type() == Type.UNCHANGED);
    }

    public String report(String serverId, String address) {
        StringBuilder text = new StringBuilder("Сервер: " + serverId + "\nАдрес: " + address + "\n"
                + "Fingerprint ключа подписи:\n" + fingerprint + "\n\n"
                + "После перезапуска JAR выполняется как обычный Java-код.\n"
                + "До подтверждения ничего не записывается в mods/.\n\n");
        for (Change change : changes) {
            if (change.type() == Type.UNCHANGED) continue;
            ModpackManifest.Mod mod = change.mod();
            text.append(change.type().label()).append(": ").append(mod.id()).append('\n');
            if (change.type() == Type.REMOVE) {
                text.append("  ").append(change.oldFile()).append(" (").append(change.oldVersion()).append(")\n\n");
                continue;
            }
            if (!change.oldVersion().isEmpty()) text.append("  ").append(change.oldVersion()).append(" -> ");
            text.append(mod.version())
                    .append(mod.required() ? " [required]" : " [optional]")
                    .append("\n  ").append(mod.file()).append("\n  Размер: ").append(mod.size())
                    .append(" байт\n  SHA-256: ").append(mod.sha256()).append("\n\n");
        }
        return text.toString();
    }
}
