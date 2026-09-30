package com.minecraftsync.sync;

import java.time.Instant;
import java.util.List;

/** Résultat d'une opération de synchronisation. */
public record SyncResult(Outcome outcome, String message, int uploaded, int downloaded, int deleted,
                         long bytesTransferred, ConflictInfo conflict, List<String> failedFiles) {

    public enum Outcome {
        UP_TO_DATE, PUSHED, PULLED, CONFLICT, WORLD_IN_USE, REMOTE_IN_USE, ERROR
    }

    /** Éléments affichés à l'utilisateur pour trancher un conflit. */
    public record ConflictInfo(int localFiles, long localSize, Instant localModified,
                               int remoteFiles, long remoteSize, Instant remoteUpdatedAt, String remoteDevice,
                               int differingFiles) {
    }

    public boolean success() {
        return outcome == Outcome.UP_TO_DATE || outcome == Outcome.PUSHED || outcome == Outcome.PULLED;
    }

    public static SyncResult upToDate() {
        return new SyncResult(Outcome.UP_TO_DATE, "Déjà à jour", 0, 0, 0, 0, null, List.of());
    }

    public static SyncResult pushed(int uploaded, int deleted, long bytes) {
        return new SyncResult(Outcome.PUSHED,
                uploaded + " fichier(s) envoyé(s), " + deleted + " retiré(s)", uploaded, 0, deleted, bytes, null, List.of());
    }

    public static SyncResult pulled(int downloaded, int deleted, long bytes) {
        return new SyncResult(Outcome.PULLED,
                downloaded + " fichier(s) téléchargé(s), " + deleted + " retiré(s)", 0, downloaded, deleted, bytes, null, List.of());
    }

    public static SyncResult conflict(ConflictInfo info) {
        return new SyncResult(Outcome.CONFLICT, "Le monde a été modifié sur plusieurs appareils.",
                0, 0, 0, 0, info, List.of());
    }

    public static SyncResult worldInUse() {
        return new SyncResult(Outcome.WORLD_IN_USE, "Le monde est ouvert dans Minecraft.", 0, 0, 0, 0, null, List.of());
    }

    public static SyncResult remoteInUse(String device) {
        return new SyncResult(Outcome.REMOTE_IN_USE, "Monde en cours d'utilisation sur « " + device + " ».",
                0, 0, 0, 0, null, List.of());
    }

    public static SyncResult error(String message) {
        return new SyncResult(Outcome.ERROR, message, 0, 0, 0, 0, null, List.of());
    }

    public static SyncResult error(String message, List<String> failed) {
        return new SyncResult(Outcome.ERROR, message, 0, 0, 0, 0, null, List.copyOf(failed));
    }
}
