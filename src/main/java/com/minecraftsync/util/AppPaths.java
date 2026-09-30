package com.minecraftsync.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Emplacements des données de l'application, selon le système.
 * <p>
 * La propriété système {@code minecraftsync.home} permet de forcer un autre
 * dossier (utile pour simuler deux « PC » sur une même machine).
 */
public final class AppPaths {

    private AppPaths() {
    }

    public enum Os { WINDOWS, MAC, LINUX }

    public static Os os() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("mac") || name.contains("darwin")) return Os.MAC;
        return Os.LINUX;
    }

    public static Path home() {
        return Paths.get(System.getProperty("user.home"));
    }

    /** %APPDATA% sous Windows (ou son équivalent si la variable manque). */
    public static Path windowsAppData() {
        String appData = System.getenv("APPDATA");
        return appData != null ? Paths.get(appData) : home().resolve("AppData").resolve("Roaming");
    }

    public static Path dataDir() {
        String forced = System.getProperty("minecraftsync.home");
        Path dir;
        if (forced != null && !forced.isBlank()) {
            dir = Paths.get(forced);
        } else {
            dir = switch (os()) {
                case WINDOWS -> windowsAppData().resolve("MinecraftSync");
                case MAC -> home().resolve("Library").resolve("Application Support").resolve("MinecraftSync");
                case LINUX -> {
                    String xdg = System.getenv("XDG_DATA_HOME");
                    Path base = xdg != null && !xdg.isBlank() ? Paths.get(xdg) : home().resolve(".local").resolve("share");
                    yield base.resolve("minecraft-sync");
                }
            };
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de créer le dossier de données " + dir, e);
        }
        return dir;
    }

    public static Path database() {
        return dataDir().resolve("minecraft-sync.db");
    }

    public static Path credentialsFile() {
        return dataDir().resolve("credentials.json");
    }

    public static Path tokensDir() {
        return dataDir().resolve("tokens");
    }

    public static Path backupsDir() {
        return dataDir().resolve("backups");
    }
}
