package com.minecraftsync.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Stockage distant simulé (sur disque local) pour tester le moteur sans Google Drive.
 * Se comporte comme Drive : identifiants opaques, noms non uniques, corbeille.
 * Permet d'injecter des pannes d'envoi.
 */
public class LocalFolderStorage implements CloudStorage {

    public static final String ROOT = "root";

    private record Meta(String id, String parent, String name, boolean folder, boolean trashed, Instant modified) {
    }

    private final Map<String, Meta> items = new LinkedHashMap<>();
    private final Path dataDir;
    public final AtomicInteger uploads = new AtomicInteger();
    public final AtomicInteger downloads = new AtomicInteger();
    private volatile Predicate<String> failUploadWhen = name -> false;

    public LocalFolderStorage(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
    }

    public void failUploadsWhen(Predicate<String> predicate) {
        this.failUploadWhen = predicate;
    }

    @Override
    public synchronized String createFolder(String parentId, String name) {
        String id = UUID.randomUUID().toString();
        items.put(id, new Meta(id, parentId, name, true, false, Instant.now()));
        return id;
    }

    @Override
    public synchronized Optional<RemoteItem> findChild(String parentId, String name) {
        return listChildren(parentId).stream().filter(i -> i.name().equals(name)).findFirst();
    }

    @Override
    public synchronized List<RemoteItem> listChildren(String parentId) {
        List<RemoteItem> list = new ArrayList<>();
        for (Meta m : items.values()) {
            if (!m.trashed() && parentId.equals(m.parent())) {
                long size = 0;
                try {
                    if (!m.folder()) size = Files.size(dataDir.resolve(m.id()));
                } catch (IOException ignored) {
                }
                list.add(new RemoteItem(m.id(), m.name(), m.folder(), size, m.modified()));
            }
        }
        return list;
    }

    @Override
    public String uploadFile(String parentId, String name, Path source) throws IOException {
        if (failUploadWhen.test(name)) throw new IOException("panne réseau simulée");
        String id = UUID.randomUUID().toString();
        Files.copy(source, dataDir.resolve(id), StandardCopyOption.REPLACE_EXISTING);
        synchronized (this) {
            items.put(id, new Meta(id, parentId, name, false, false, Instant.now()));
        }
        uploads.incrementAndGet();
        return id;
    }

    @Override
    public void downloadFile(String fileId, Path target) throws IOException {
        Files.copy(dataDir.resolve(fileId), target, StandardCopyOption.REPLACE_EXISTING);
        downloads.incrementAndGet();
    }

    @Override
    public synchronized String writeText(String parentId, String name, String content, String existingId)
            throws IOException {
        String id = existingId != null ? existingId : UUID.randomUUID().toString();
        Files.writeString(dataDir.resolve(id), content, StandardCharsets.UTF_8);
        items.put(id, new Meta(id, parentId, name, false, false, Instant.now()));
        return id;
    }

    @Override
    public String readText(String fileId) throws IOException {
        return Files.readString(dataDir.resolve(fileId), StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void trash(String id) {
        Meta m = items.get(id);
        if (m != null) items.put(id, new Meta(m.id(), m.parent(), m.name(), m.folder(), true, m.modified()));
    }

    /** Remplace le contenu d'un fichier existant (pour simuler une corruption). */
    public void overwrite(String id, String content) throws IOException {
        Files.writeString(dataDir.resolve(id), content, StandardCharsets.UTF_8);
    }
}
