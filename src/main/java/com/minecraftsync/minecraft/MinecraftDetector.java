package com.minecraftsync.minecraft;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Détecte si un monde est utilisé par Minecraft.
 * <p>
 * Méthode principale : Minecraft verrouille le fichier {@code session.lock} du monde ouvert
 * (FileChannel.tryLock). Si on n'arrive pas à prendre ce verrou, le monde est ouvert.
 * C'est plus fiable que la recherche de processus : cela fonctionne quel que soit le lanceur,
 * et détecte aussi le retour au menu principal (le monde est fermé même si le jeu tourne encore).
 * <p>
 * La détection de processus sert d'information complémentaire (affichage).
 * Sous Windows, les arguments des autres processus ne sont généralement pas lisibles :
 * elle y est donc peu fiable.
 */
public class MinecraftDetector {

    private static final List<String> MARKERS = List.of(
            "net.minecraft.client.main.main", "net.minecraft.launchwrapper", "cpw.mods.",
            "net.fabricmc.", "org.quiltmc.", "net.neoforged.", "net.minecraftforge.",
            "minecraftlauncher", "minecraft launcher", ".minecraft", "-dminecraft.");

    /**
     * @return true si le monde est ouvert dans Minecraft (ou si le doute persiste : on préfère attendre).
     */
    public boolean isWorldInUse(Path worldDir) {
        Path lock = worldDir.resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true; // verrou tenu dans ce même processus (cas des tests)
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            // Windows : « le processus ne peut pas accéder au fichier » = verrou tenu par Minecraft.
            // Par prudence, tout échec est interprété comme « monde utilisé ».
            return true;
        }
    }

    /** Un processus ressemblant à Minecraft est-il lancé ? (information, pas une garantie) */
    public boolean isMinecraftRunning() {
        return ProcessHandle.allProcesses().anyMatch(this::looksLikeMinecraft);
    }

    private boolean looksLikeMinecraft(ProcessHandle p) {
        try {
            Optional<String> cmd = p.info().commandLine();
            if (cmd.isEmpty()) return false;
            String c = cmd.get().toLowerCase(Locale.ROOT);
            if (!(c.contains("java") || c.contains("minecraft"))) return false;
            return MARKERS.stream().anyMatch(c::contains);
        } catch (Exception e) {
            return false;
        }
    }
}
