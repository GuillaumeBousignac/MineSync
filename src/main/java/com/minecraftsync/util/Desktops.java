package com.minecraftsync.util;

import java.io.IOException;
import java.nio.file.Path;

/** Ouverture d'un dossier dans le gestionnaire de fichiers du système. */
public final class Desktops {

    private Desktops() {
    }

    public static void openFolder(Path dir) throws IOException {
        String p = dir.toAbsolutePath().toString();
        ProcessBuilder pb = switch (AppPaths.os()) {
            case WINDOWS -> new ProcessBuilder("explorer.exe", p);
            case MAC -> new ProcessBuilder("open", p);
            case LINUX -> new ProcessBuilder("xdg-open", p);
        };
        pb.inheritIO().start();
    }
}
