package com.minecraftsync.minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Description d'un monde Minecraft trouvé sur le disque.
 *
 * @param dir        dossier du monde
 * @param levelName  nom affiché dans Minecraft (LevelName)
 * @param version    version Minecraft (ex. « 1.21.10 »), null si inconnue
 * @param modded     true/false, null si indéterminable
 * @param lastPlayed date de dernière partie (ms), 0 si inconnue
 * @param source     origine (lanceur/instance) pour l'affichage
 */
public record WorldInfo(Path dir, String levelName, String version, Boolean modded, long lastPlayed, String source) {

    public String folderName() {
        return dir.getFileName().toString();
    }

    public static boolean isWorld(Path dir) {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("level.dat"));
    }

    /** Lit level.dat (ou level.dat_old en secours). Ne lève jamais d'exception. */
    public static WorldInfo read(Path dir, String source) {
        for (String name : List.of("level.dat", "level.dat_old")) {
            Path f = dir.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            try {
                Map<String, Object> root = LevelDatReader.read(f);
                return fromNbt(dir, source, root);
            } catch (Exception ignored) {
                // fichier illisible : on essaie le suivant
            }
        }
        return new WorldInfo(dir, dir.getFileName().toString(), null, null, 0, source);
    }

    @SuppressWarnings("unchecked")
    static WorldInfo fromNbt(Path dir, String source, Map<String, Object> root) {
        Object dataObj = root.get("Data");
        Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : root;

        String levelName = data.get("LevelName") instanceof String s && !s.isBlank() ? s : dir.getFileName().toString();

        String version = null;
        if (data.get("Version") instanceof Map<?, ?> v && v.get("Name") instanceof String n) {
            version = n;
        }

        Boolean modded = null;
        if (data.get("WasModded") instanceof Byte b) {
            modded = b != 0;
        }
        if (data.get("ServerBrands") instanceof List<?> brands && !brands.isEmpty()) {
            boolean nonVanilla = brands.stream().anyMatch(x -> x instanceof String s && !s.equalsIgnoreCase("vanilla"));
            modded = (modded != null && modded) || nonVanilla;
        }

        long lastPlayed = data.get("LastPlayed") instanceof Long l ? l : 0L;
        return new WorldInfo(dir, levelName, version, modded, lastPlayed, source);
    }
}
