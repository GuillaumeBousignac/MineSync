package com.minecraftsync.sync;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Abstraction du stockage distant. Le moteur de synchronisation ne dépend que de cette interface.
 * Implémentation fournie : Google Drive.
 */
public interface CloudStorage {

    /** Élément distant (fichier ou dossier). */
    record RemoteItem(String id, String name, boolean folder, long size, Instant modified) {
    }

    String createFolder(String parentId, String name) throws IOException;

    /** Premier enfant non supprimé portant ce nom. */
    Optional<RemoteItem> findChild(String parentId, String name) throws IOException;

    List<RemoteItem> listChildren(String parentId) throws IOException;

    /** Envoie toujours un NOUVEAU fichier et renvoie son identifiant. */
    String uploadFile(String parentId, String name, Path source) throws IOException;

    void downloadFile(String fileId, Path target) throws IOException;

    /** Crée (existingId == null) ou remplace un petit fichier texte. */
    String writeText(String parentId, String name, String content, String existingId) throws IOException;

    String readText(String fileId) throws IOException;

    /** Place l'élément dans la corbeille (récupérable), jamais de suppression définitive. */
    void trash(String id) throws IOException;

    default String findOrCreateFolder(String parentId, String name) throws IOException {
        Optional<RemoteItem> existing = findChild(parentId, name);
        if (existing.isPresent() && existing.get().folder()) {
            return existing.get().id();
        }
        return createFolder(parentId, name);
    }
}
