package com.minecraftsync.minecraft;

import com.minecraftsync.util.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Recherche les dossiers « saves » des lanceurs courants, puis les mondes qu'ils contiennent.
 * Lanceurs pris en charge : officiel, Prism Launcher, CurseForge, Modrinth App,
 * plus les dossiers ajoutés à la main (MultiMC portable, ATLauncher, etc.).
 */
public class WorldDetector {

    /** Un dossier contenant des mondes. */
    public record SavesLocation(Path dir, String label) {
    }

    public List<SavesLocation> findSavesLocations(List<Path> customDirs) {
        Map<Path, SavesLocation> found = new LinkedHashMap<>();
        Path home = AppPaths.home();

        switch (AppPaths.os()) {
            case WINDOWS -> {
                Path appData = AppPaths.windowsAppData();
                addSaves(found, appData.resolve(".minecraft").resolve("saves"), "Lanceur officiel");
                addInstances(found, appData.resolve("PrismLauncher").resolve("instances"), "Prism");
                addInstances(found, home.resolve("curseforge").resolve("minecraft").resolve("Instances"), "CurseForge");
                addInstances(found, appData.resolve("ModrinthApp").resolve("profiles"), "Modrinth");
                addInstances(found, appData.resolve("com.modrinth.theseus").resolve("profiles"), "Modrinth");
            }
            case MAC -> {
                Path support = home.resolve("Library").resolve("Application Support");
                addSaves(found, support.resolve("minecraft").resolve("saves"), "Lanceur officiel");
                addInstances(found, support.resolve("PrismLauncher").resolve("instances"), "Prism");
                addInstances(found, home.resolve("Documents").resolve("curseforge").resolve("minecraft").resolve("Instances"), "CurseForge");
                addInstances(found, support.resolve("ModrinthApp").resolve("profiles"), "Modrinth");
                addInstances(found, support.resolve("com.modrinth.theseus").resolve("profiles"), "Modrinth");
            }
            case LINUX -> {
                Path share = home.resolve(".local").resolve("share");
                addSaves(found, home.resolve(".minecraft").resolve("saves"), "Lanceur officiel");
                addSaves(found, home.resolve(".var").resolve("app").resolve("com.mojang.Minecraft")
                        .resolve(".minecraft").resolve("saves"), "Lanceur officiel (Flatpak)");
                addInstances(found, share.resolve("PrismLauncher").resolve("instances"), "Prism");
                addInstances(found, home.resolve(".var").resolve("app").resolve("org.prismlauncher.PrismLauncher")
                        .resolve("data").resolve("PrismLauncher").resolve("instances"), "Prism (Flatpak)");
                addInstances(found, share.resolve("ModrinthApp").resolve("profiles"), "Modrinth");
                addInstances(found, share.resolve("com.modrinth.theseus").resolve("profiles"), "Modrinth");
            }
        }

        if (customDirs != null) {
            for (Path p : customDirs) {
                addSaves(found, p, "Dossier personnalisé");
                // Accepte aussi un dossier d'instances ou un dossier .minecraft
                addSaves(found, p.resolve("saves"), "Dossier personnalisé");
                addInstances(found, p, "Dossier personnalisé");
            }
        }
        return new ArrayList<>(found.values());
    }

    /** Mondes des emplacements donnés, du plus récemment joué au plus ancien. */
    public List<WorldInfo> findWorlds(List<SavesLocation> locations) {
        List<WorldInfo> worlds = new ArrayList<>();
        for (SavesLocation loc : locations) {
            try (Stream<Path> s = Files.list(loc.dir())) {
                s.filter(WorldInfo::isWorld).forEach(w -> worlds.add(WorldInfo.read(w, loc.label())));
            } catch (IOException ignored) {
                // dossier illisible : ignoré
            }
        }
        worlds.sort(Comparator.comparingLong(WorldInfo::lastPlayed).reversed());
        return worlds;
    }

    private static void addSaves(Map<Path, SavesLocation> found, Path saves, String label) {
        if (Files.isDirectory(saves)) {
            Path key = saves.toAbsolutePath().normalize();
            found.putIfAbsent(key, new SavesLocation(key, label));
        }
    }

    /** Parcourt un dossier d'instances (Prism, CurseForge, Modrinth…). */
    private static void addInstances(Map<Path, SavesLocation> found, Path instancesDir, String launcher) {
        if (!Files.isDirectory(instancesDir)) return;
        try (Stream<Path> s = Files.list(instancesDir)) {
            s.filter(Files::isDirectory).forEach(inst -> {
                String label = launcher + " · " + inst.getFileName();
                addSaves(found, inst.resolve("saves"), label);
                addSaves(found, inst.resolve(".minecraft").resolve("saves"), label);
                addSaves(found, inst.resolve("minecraft").resolve("saves"), label);
            });
        } catch (IOException ignored) {
            // ignoré
        }
    }
}
