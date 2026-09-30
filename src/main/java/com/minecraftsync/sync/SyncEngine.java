package com.minecraftsync.sync;

import com.minecraftsync.database.Database;
import com.minecraftsync.minecraft.MinecraftDetector;
import com.minecraftsync.model.DeviceIdentity;
import com.minecraftsync.model.FileEntry;
import com.minecraftsync.model.Session;
import com.minecraftsync.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Moteur de synchronisation d'une session. Indépendant de l'interface graphique.
 *
 * <h2>Organisation distante d'une session</h2>
 * <pre>
 * &lt;dossier de la session&gt;/
 *   manifest.json   ← description de la dernière version complète (point de validation)
 *   lock.json       ← présent pendant qu'un appareil joue sur le monde
 *   files/          ← contenu des fichiers ; chaque fichier porte son chemin relatif comme nom
 * </pre>
 *
 * <h2>Règles de sûreté</h2>
 * <ul>
 *   <li>Envoi : les nouvelles versions sont envoyées comme NOUVEAUX fichiers ; le manifeste n'est
 *       réécrit que si tous les envois ont réussi. Les anciennes versions ne partent à la corbeille
 *       qu'après ce point de validation. Une coupure réseau laisse donc la version distante intacte.</li>
 *   <li>Réception : téléchargement dans un dossier temporaire, vérification SHA-256 de chaque fichier,
 *       puis remplacement avec possibilité de retour arrière.</li>
 *   <li>Aucune suppression n'est propagée si le dossier du monde a disparu localement.</li>
 *   <li>Modifications des deux côtés : conflit, aucune écriture automatique.</li>
 * </ul>
 */
public class SyncEngine {

    public static final String FILES_FOLDER = "files";

    private final Database db;
    private final CloudStorage cloud;
    private final MinecraftDetector detector;
    private final DeviceIdentity device;
    private final ConflictManager conflicts;
    private int retries = 3;
    private long retryDelayMillis = 1500;

    /** Vue de l'état distant d'une session. */
    record Remote(String folderId, String filesFolderId, Manifest manifest, String manifestFileId,
                  RemoteLock lock, String lockFileId) {
    }

    @FunctionalInterface
    private interface IoCall<T> {
        T call() throws IOException;
    }

    public SyncEngine(Database db, CloudStorage cloud, MinecraftDetector detector,
                      DeviceIdentity device, ConflictManager conflicts) {
        this.db = db;
        this.cloud = cloud;
        this.detector = detector;
        this.device = device;
        this.conflicts = conflicts;
    }

    /** Réglage des nouvelles tentatives (utile pour les tests). */
    public void setRetryPolicy(int retries, long delayMillis) {
        this.retries = Math.max(1, retries);
        this.retryDelayMillis = Math.max(0, delayMillis);
    }

    public CloudStorage cloud() {
        return cloud;
    }

    // =====================================================================
    // Points d'entrée
    // =====================================================================

    /** Synchronisation automatique ou manuelle : décide seule de l'envoi, de la réception ou du conflit. */
    public SyncResult sync(Session s, ProgressListener p) {
        return run(s, p, null);
    }

    /** Résolution explicite d'un conflit par l'utilisateur (avec copie de sauvegarde locale). */
    public SyncResult resolve(Session s, ConflictManager.Resolution resolution, ProgressListener p) {
        return run(s, p, resolution);
    }

    /** Recalcule la description d'un conflit (par exemple après un redémarrage). */
    public SyncResult.ConflictInfo inspect(Session s) throws IOException {
        Remote remote = openRemote(s);
        FileComparator.Scan local = FileComparator.scan(s.localPath(), db.loadBaseline(s.id()));
        return ConflictManager.describe(local, remote.manifest());
    }

    /**
     * Signale sur le stockage distant que ce PC joue sur le monde (verrou de présence).
     *
     * @return le verrou d'un AUTRE appareil s'il est actif (monde ouvert des deux côtés)
     */
    public Optional<RemoteLock> markPlaying(Session s) throws IOException {
        requireRemote(s);
        Optional<CloudStorage.RemoteItem> item = cloud.findChild(s.remoteFolderId(), RemoteLock.FILE_NAME);
        RemoteLock existing = null;
        if (item.isPresent()) {
            existing = readLockQuietly(item.get().id());
        }
        Optional<RemoteLock> foreign = Optional.ofNullable(existing)
                .filter(RemoteLock::isFresh).filter(l -> !device.id().equals(l.deviceId()));
        RemoteLock mine = new RemoteLock(device.id(), device.name(), Instant.now());
        cloud.writeText(s.remoteFolderId(), RemoteLock.FILE_NAME, mine.toJson(), item.map(CloudStorage.RemoteItem::id).orElse(null));
        return foreign;
    }

    // =====================================================================
    // Déroulé
    // =====================================================================

    private SyncResult run(Session s, ProgressListener p, ConflictManager.Resolution forced) {
        try {
            return doSync(s, p == null ? ProgressListener.NONE : p, forced);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return SyncResult.error("Synchronisation interrompue : " + msg + ". Le monde local n'a pas été supprimé.");
        }
    }

    private SyncResult doSync(Session s, ProgressListener p, ConflictManager.Resolution forced) throws IOException {
        Path world = s.localPath();
        if (detector.isWorldInUse(world)) {
            return SyncResult.worldInUse();
        }

        p.progress("Lecture de l'état distant…", -1);
        Remote remote = openRemote(s);
        Manifest m = remote.manifest();

        if (forced == null && remote.lock() != null && remote.lock().isFresh()
                && !device.id().equals(remote.lock().deviceId())) {
            return SyncResult.remoteInUse(remote.lock().deviceName());
        }

        Map<String, FileEntry> baseline = db.loadBaseline(s.id());
        p.progress("Analyse du monde…", -1);
        FileComparator.Scan local = FileComparator.scan(world, baseline);
        boolean localLooksValid = Files.isRegularFile(world.resolve("level.dat"));

        // --- Choix explicite de l'utilisateur ---
        if (forced == ConflictManager.Resolution.KEEP_LOCAL) {
            if (!localLooksValid) {
                return SyncResult.error("Le monde local est introuvable ou incomplet (level.dat absent) : envoi refusé.");
            }
            return finish(remote, push(s, world, local, remote, p));
        }
        if (forced == ConflictManager.Resolution.KEEP_REMOTE) {
            if (m == null || m.files().isEmpty()) {
                return SyncResult.error("Aucune version du monde n'existe sur le stockage distant.");
            }
            return finish(remote, pull(s, world, local, remote, true, p));
        }

        // --- Garde-fou : le monde a disparu alors qu'il était synchronisé ---
        if (!baseline.isEmpty() && !localLooksValid) {
            return SyncResult.error("Le dossier du monde est introuvable ou incomplet (" + world
                    + "). Aucune suppression n'a été envoyée. Vérifiez le chemin, ou utilisez "
                    + "« Restaurer depuis Google Drive » dans les paramètres de la session.");
        }

        // --- Aucune version distante ---
        if (m == null) {
            if (local.files().isEmpty() || !localLooksValid) {
                return SyncResult.error("Le monde local est vide et aucune version n'existe sur le stockage distant.");
            }
            return finish(remote, push(s, world, local, remote, p));
        }

        Map<String, String> localH = local.hashes();
        Map<String, String> remoteH = m.hashes();
        Map<String, String> baseH = new TreeMap<>();
        baseline.forEach((k, v) -> baseH.put(k, v.hash()));

        // Contenus identiques : on se contente de mettre à jour la référence.
        if (FileComparator.sameContent(localH, remoteH)) {
            adopt(s, local, m);
            return finish(remote, SyncResult.upToDate());
        }

        // Première synchronisation de cette session sur ce PC.
        if (baseline.isEmpty()) {
            if (local.files().isEmpty()) {
                return finish(remote, pull(s, world, local, remote, false, p));
            }
            return SyncResult.conflict(ConflictManager.describe(local, m));
        }

        boolean localChanged = !FileComparator.sameContent(localH, baseH);
        boolean remoteChanged = !FileComparator.sameContent(remoteH, baseH);

        if (localChanged && !remoteChanged) {
            return finish(remote, push(s, world, local, remote, p));
        }
        if (!localChanged && remoteChanged) {
            return finish(remote, pull(s, world, local, remote, false, p));
        }
        // Les deux côtés ont changé différemment.
        return SyncResult.conflict(ConflictManager.describe(local, m));
    }

    /** Après une synchro réussie, libère notre propre verrou de présence. */
    private SyncResult finish(Remote remote, SyncResult result) {
        if (result.success() && remote.lock() != null && device.id().equals(remote.lock().deviceId())) {
            try {
                cloud.trash(remote.lockFileId());
            } catch (IOException ignored) {
                // sans gravité : le verrou expirera seul
            }
        }
        return result;
    }

    private void adopt(Session s, FileComparator.Scan local, Manifest m) {
        db.replaceBaseline(s.id(), local.files().values());
        s.setRemoteRevision(m.revision());
        s.setLastSync(Instant.now());
        s.setLastSyncDevice(m.deviceName());
    }

    // =====================================================================
    // Envoi
    // =====================================================================

    private SyncResult push(Session s, Path world, FileComparator.Scan local, Remote remote, ProgressListener p)
            throws IOException {
        Manifest old = remote.manifest();
        Map<String, Manifest.Entry> next = new TreeMap<>();
        List<FileEntry> toUpload = new ArrayList<>();
        for (FileEntry f : local.files().values()) {
            Manifest.Entry e = old == null ? null : old.files().get(f.path());
            if (e != null && e.fileId() != null && f.hash().equals(e.hash())) {
                next.put(f.path(), e); // identique → rien à faire
            } else {
                toUpload.add(f);
            }
        }
        int removed = old == null ? 0 : (int) old.files().keySet().stream()
                .filter(k -> !local.files().containsKey(k)).count();

        long total = Math.max(1, toUpload.stream().mapToLong(FileEntry::size).sum());
        long done = 0;
        List<String> newIds = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (FileEntry f : toUpload) {
            p.progress("Envoi : " + f.path(), (double) done / total);
            Path file = world.resolve(f.path());
            if (detector.isWorldInUse(world)) {
                trashQuietly(newIds);
                return SyncResult.error("Le monde a été ouvert pendant l'envoi. Nouvel essai à sa fermeture.");
            }
            if (!FileComparator.unchangedSinceScan(file, f)) {
                trashQuietly(newIds);
                return SyncResult.error("Le monde a été modifié pendant l'envoi (" + f.path() + "). Nouvel essai plus tard.");
            }
            try {
                String id = retry(() -> cloud.uploadFile(remote.filesFolderId(), f.path(), file));
                newIds.add(id);
                next.put(f.path(), new Manifest.Entry(f.size(), f.hash(), id));
                done += f.size();
            } catch (IOException e) {
                failed.add(f.path() + " : " + e.getMessage());
            }
        }

        if (!failed.isEmpty()) {
            trashQuietly(newIds);
            return SyncResult.error("Certains fichiers n'ont pas pu être transférés ("
                    + failed.size() + "). La version distante et le monde local sont intacts.", failed);
        }

        // Contrôle de concurrence : personne n'a publié entre-temps ?
        p.progress("Validation…", 1);
        Optional<CloudStorage.RemoteItem> currentItem = cloud.findChild(remote.folderId(), Manifest.FILE_NAME);
        long expected = old == null ? -1 : old.revision();
        long current = currentItem.isPresent()
                ? Manifest.fromJson(cloud.readText(currentItem.get().id())).revision() : -1;
        if (current != expected) {
            trashQuietly(newIds);
            return SyncResult.error("Une autre version a été publiée pendant l'envoi. Nouvelle analyse au prochain essai.");
        }

        Manifest nm = new Manifest();
        nm.setRevision((old == null ? 0 : old.revision()) + 1);
        nm.setSessionName(s.name());
        nm.setWorldFolderName(world.getFileName().toString());
        nm.setMinecraftVersion(s.minecraftVersion());
        nm.setModded(s.modded());
        nm.setDeviceId(device.id());
        nm.setDeviceName(device.name());
        nm.setUpdatedAt(Instant.now());
        nm.files().putAll(next);
        nm.dirs().addAll(local.dirs());

        String manifestId = currentItem.map(CloudStorage.RemoteItem::id).orElse(null);
        retry(() -> cloud.writeText(remote.folderId(), Manifest.FILE_NAME, nm.toJson(), manifestId));
        // ---- Point de validation franchi ----

        db.replaceBaseline(s.id(), local.files().values());
        s.setRemoteRevision(nm.revision());
        s.setLastSync(Instant.now());
        s.setLastSyncDevice(device.name());

        cleanupOrphans(remote.filesFolderId(), next);
        long bytes = toUpload.stream().mapToLong(FileEntry::size).sum();
        return SyncResult.pushed(toUpload.size(), removed, bytes);
    }

    /** Met à la corbeille les fichiers distants qui ne sont plus référencés par le manifeste. */
    private void cleanupOrphans(String filesFolderId, Map<String, Manifest.Entry> referenced) {
        Set<String> keep = new HashSet<>();
        referenced.values().forEach(e -> keep.add(e.fileId()));
        try {
            for (CloudStorage.RemoteItem item : cloud.listChildren(filesFolderId)) {
                if (!keep.contains(item.id())) {
                    try {
                        cloud.trash(item.id());
                    } catch (IOException ignored) {
                        // sera retenté au prochain envoi
                    }
                }
            }
        } catch (IOException ignored) {
            // idem
        }
    }

    // =====================================================================
    // Réception
    // =====================================================================

    private SyncResult pull(Session s, Path world, FileComparator.Scan local, Remote remote, boolean backup,
                            ProgressListener p) throws IOException {
        Manifest m = remote.manifest();
        List<String> toDownload = new ArrayList<>();
        m.files().forEach((path, e) -> {
            FileEntry l = local.files().get(path);
            if (l == null || !l.hash().equals(e.hash())) toDownload.add(path);
        });
        List<String> toDelete = local.files().keySet().stream().filter(k -> !m.files().containsKey(k)).toList();

        for (String path : m.files().keySet()) {
            checkSafeRelativePath(path);
        }

        if (backup) {
            p.progress("Copie de sauvegarde du monde local…", -1);
            conflicts.backupWorld(s, world, "avant-version-distante");
        }

        Files.createDirectories(world);
        Path tmp = world.resolve(FileComparator.TMP_DIR);
        ConflictManager.deleteRecursively(tmp);
        Path staging = tmp.resolve("staging");
        Path rollback = tmp.resolve("rollback");
        Files.createDirectories(staging);
        Files.createDirectories(rollback);

        long total = Math.max(1, toDownload.stream().mapToLong(k -> m.files().get(k).size()).sum());
        long done = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (String path : toDownload) {
                Manifest.Entry e = m.files().get(path);
                p.progress("Téléchargement : " + path, (double) done / total);
                Path target = staging.resolve(path);
                Files.createDirectories(target.getParent());
                try {
                    if (e.size() == 0) {
                        Files.write(target, new byte[0]);
                    } else {
                        retry(() -> {
                            cloud.downloadFile(e.fileId(), target);
                            return null;
                        });
                    }
                    String h = Hashing.sha256(target);
                    if (!h.equals(e.hash())) {
                        failed.add(path + " : empreinte incorrecte après téléchargement");
                    }
                } catch (IOException ex) {
                    failed.add(path + " : " + ex.getMessage());
                }
                done += e.size();
            }
            if (!failed.isEmpty()) {
                return SyncResult.error("Certains fichiers n'ont pas pu être téléchargés (" + failed.size()
                        + "). Le monde local n'a pas été modifié.", failed);
            }
            if (detector.isWorldInUse(world)) {
                return SyncResult.error("Le monde a été ouvert pendant le téléchargement. Nouvel essai à sa fermeture.");
            }

            p.progress("Mise à jour du monde…", 1);
            apply(world, staging, rollback, toDownload, toDelete);
        } finally {
            try {
                ConflictManager.deleteRecursively(tmp);
            } catch (IOException ignored) {
                // dossier technique : ignoré par l'analyse, nettoyé au prochain passage
            }
        }

        for (String d : m.dirs()) {
            checkSafeRelativePath(d);
            Files.createDirectories(world.resolve(d));
        }

        List<FileEntry> newBaseline = new ArrayList<>();
        for (Map.Entry<String, Manifest.Entry> e : m.files().entrySet()) {
            Path f = world.resolve(e.getKey());
            newBaseline.add(new FileEntry(e.getKey(), Files.size(f), Files.getLastModifiedTime(f).toMillis(),
                    e.getValue().hash()));
        }
        db.replaceBaseline(s.id(), newBaseline);
        s.setRemoteRevision(m.revision());
        s.setLastSync(Instant.now());
        s.setLastSyncDevice(m.deviceName());
        if (m.minecraftVersion() != null) s.setMinecraftVersion(m.minecraftVersion());
        if (m.modded() != null) s.setModded(m.modded());

        long bytes = toDownload.stream().mapToLong(k -> m.files().get(k).size()).sum();
        return SyncResult.pulled(toDownload.size(), toDelete.size(), bytes);
    }

    /**
     * Remplace les fichiers du monde. Chaque ancien fichier est d'abord déplacé dans le dossier
     * de retour arrière ; en cas d'erreur, tout est remis en place.
     */
    private void apply(Path world, Path staging, Path rollback, List<String> toReplace, List<String> toDelete)
            throws IOException {
        record Step(String path, boolean hadOld, boolean placedNew) {
        }
        List<Step> journal = new ArrayList<>();
        try {
            for (String path : toReplace) {
                Path target = world.resolve(path);
                boolean hadOld = Files.exists(target);
                if (hadOld) {
                    Path back = rollback.resolve(path);
                    Files.createDirectories(back.getParent());
                    Files.move(target, back, StandardCopyOption.REPLACE_EXISTING);
                }
                journal.add(new Step(path, hadOld, false));
                Files.createDirectories(target.getParent());
                Files.move(staging.resolve(path), target, StandardCopyOption.REPLACE_EXISTING);
                journal.set(journal.size() - 1, new Step(path, hadOld, true));
            }
            for (String path : toDelete) {
                Path target = world.resolve(path);
                Path back = rollback.resolve(path);
                Files.createDirectories(back.getParent());
                Files.move(target, back, StandardCopyOption.REPLACE_EXISTING);
                journal.add(new Step(path, true, false));
            }
        } catch (IOException e) {
            for (int i = journal.size() - 1; i >= 0; i--) {
                Step st = journal.get(i);
                Path target = world.resolve(st.path());
                try {
                    if (st.placedNew()) Files.deleteIfExists(target);
                    if (st.hadOld()) Files.move(rollback.resolve(st.path()), target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // on continue la remise en place des autres fichiers
                }
            }
            throw new IOException("échec de la mise à jour du monde, fichiers d'origine restaurés (" + e.getMessage() + ")", e);
        }
    }

    // =====================================================================
    // Outils
    // =====================================================================

    Remote openRemote(Session s) throws IOException {
        requireRemote(s);
        String folderId = s.remoteFolderId();
        List<CloudStorage.RemoteItem> children = cloud.listChildren(folderId);

        Map<String, CloudStorage.RemoteItem> latest = new HashMap<>();
        for (CloudStorage.RemoteItem it : children) {
            latest.merge(it.name() + (it.folder() ? "/" : ""), it, (a, b) ->
                    Comparator.comparing((CloudStorage.RemoteItem x) -> x.modified() == null ? Instant.EPOCH : x.modified())
                            .compare(a, b) >= 0 ? a : b);
        }

        CloudStorage.RemoteItem filesFolder = latest.get(FILES_FOLDER + "/");
        String filesFolderId = filesFolder != null ? filesFolder.id() : cloud.createFolder(folderId, FILES_FOLDER);

        Manifest manifest = null;
        String manifestId = null;
        CloudStorage.RemoteItem mi = latest.get(Manifest.FILE_NAME);
        if (mi != null) {
            manifestId = mi.id();
            try {
                manifest = Manifest.fromJson(retry(() -> cloud.readText(mi.id())));
            } catch (IllegalArgumentException e) {
                // Ne JAMAIS traiter un manifeste illisible comme « absent » : on écraserait la version distante.
                throw new IOException("manifeste distant illisible (" + e.getMessage() + ")", e);
            }
        }

        RemoteLock lock = null;
        String lockId = null;
        CloudStorage.RemoteItem li = latest.get(RemoteLock.FILE_NAME);
        if (li != null) {
            lockId = li.id();
            lock = readLockQuietly(li.id());
        }
        return new Remote(folderId, filesFolderId, manifest, manifestId, lock, lockId);
    }

    private RemoteLock readLockQuietly(String id) {
        try {
            return RemoteLock.fromJson(cloud.readText(id));
        } catch (Exception e) {
            return null;
        }
    }

    private static void requireRemote(Session s) throws IOException {
        if (s.remoteFolderId() == null) {
            throw new IOException("la session n'est liée à aucun dossier distant");
        }
    }

    /** Refuse les chemins distants qui sortiraient du dossier du monde (« ../ », chemins absolus). */
    private static void checkSafeRelativePath(String path) throws IOException {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")
                || path.equals("..") || path.startsWith("../") || path.contains("/../") || path.endsWith("/..")
                || path.contains("\\")) {
            throw new IOException("chemin distant refusé : " + path);
        }
    }

    private void trashQuietly(List<String> ids) {
        for (String id : ids) {
            try {
                cloud.trash(id);
            } catch (IOException ignored) {
                // orphelin nettoyé lors d'un prochain envoi réussi
            }
        }
    }

    private <T> T retry(IoCall<T> call) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                return call.call();
            } catch (IOException e) {
                last = e;
                if (attempt < retries && retryDelayMillis > 0) {
                    try {
                        Thread.sleep(retryDelayMillis * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }
}
