package com.minecraftsync.sync;

import com.minecraftsync.database.Database;
import com.minecraftsync.minecraft.MinecraftDetector;
import com.minecraftsync.minecraft.WorldInfo;
import com.minecraftsync.model.RemoteSessionInfo;
import com.minecraftsync.model.Session;
import com.minecraftsync.model.SyncState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Point d'entrée applicatif : gère les sessions et exécute les synchronisations en arrière-plan.
 * Ne dépend pas de l'interface graphique (communication par {@link Listener}).
 */
public class SyncService implements AutoCloseable {

    public enum Trigger { MANUAL, AFTER_PLAY, PERIODIC, CREATED }

    public interface Listener {
        void sessionUpdated(Session session);

        void sessionsChanged();

        default void notice(String message) {
        }
    }

    private final Database db;
    private final MinecraftDetector detector;
    private final ConflictManager conflicts;

    private volatile CloudStorage cloud;
    private volatile String rootFolderId;
    private volatile SyncEngine engine;

    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, SyncResult.ConflictInfo> conflictInfos = new ConcurrentHashMap<>();
    private final Map<String, Long> lastProgressEvent = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sync-worker");
        t.setDaemon(true);
        return t;
    });

    public SyncService(Database db, MinecraftDetector detector, ConflictManager conflicts) {
        this.db = db;
        this.detector = detector;
        this.conflicts = conflicts;
        sessions.addAll(db.listSessions());
        for (Session s : sessions) {
            if (!s.syncEnabled()) s.setState(SyncState.DISABLED);
            else if (s.state() != SyncState.CONFLICT && s.state() != SyncState.ERROR) s.setState(SyncState.OFFLINE);
        }
    }

    public Database database() {
        return db;
    }

    public ConflictManager conflicts() {
        return conflicts;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    // ---------------- Connexion ----------------

    public void connect(CloudStorage storage, String rootId) {
        this.cloud = storage;
        this.rootFolderId = rootId;
        this.engine = new SyncEngine(db, storage, detector, db.device(), conflicts);
        for (Session s : sessions) {
            if (s.state() == SyncState.OFFLINE) setState(s, s.syncEnabled() ? SyncState.IDLE : SyncState.DISABLED, null);
        }
    }

    /** À appeler après un changement du nom de l'appareil. */
    public void refreshDevice() {
        CloudStorage c = cloud;
        if (c != null) this.engine = new SyncEngine(db, c, detector, db.device(), conflicts);
    }

    /** Utilisé par les tests pour raccourcir les délais. */
    public SyncEngine engine() {
        return engine;
    }

    public void disconnect() {
        this.cloud = null;
        this.engine = null;
        this.rootFolderId = null;
        for (Session s : sessions) {
            if (s.syncEnabled() && s.state() != SyncState.CONFLICT) setState(s, SyncState.OFFLINE, null);
        }
    }

    public boolean isConnected() {
        return engine != null;
    }

    // ---------------- Sessions ----------------

    public List<Session> sessions() {
        return new ArrayList<>(sessions);
    }

    public Optional<Session> findByPath(Path worldDir) {
        String p = worldDir.toAbsolutePath().normalize().toString();
        return sessions.stream().filter(s -> s.localPathString().equals(p)).findFirst();
    }

    public Session createSession(WorldInfo world, String name, String version, Boolean modded, boolean enabled)
            throws IOException {
        requireConnected();
        if (findByPath(world.dir()).isPresent()) {
            throw new IllegalArgumentException("Ce monde est déjà lié à une session.");
        }
        Session s = Session.create(name, world.dir());
        s.setMinecraftVersion(blankToNull(version));
        s.setModded(modded);
        s.setSyncEnabled(enabled);
        s.setRemoteFolderId(cloud.createFolder(rootFolderId, uniqueFolderName(name)));
        s.setState(enabled ? SyncState.IDLE : SyncState.DISABLED);
        db.saveSession(s);
        sessions.add(s);
        fireSessionsChanged();
        if (enabled) requestSync(s, Trigger.CREATED);
        return s;
    }

    /** Sessions présentes sur le stockage distant et pas encore ajoutées sur ce PC. */
    public List<RemoteSessionInfo> listRemoteSessions() throws IOException {
        requireConnected();
        Set<String> known = new HashSet<>();
        sessions.forEach(s -> known.add(s.remoteFolderId()));
        List<RemoteSessionInfo> result = new ArrayList<>();
        for (CloudStorage.RemoteItem folder : cloud.listChildren(rootFolderId)) {
            if (!folder.folder() || known.contains(folder.id())) continue;
            Optional<CloudStorage.RemoteItem> mf = cloud.findChild(folder.id(), Manifest.FILE_NAME);
            if (mf.isEmpty()) continue;
            try {
                result.add(new RemoteSessionInfo(folder.id(), folder.name(), Manifest.fromJson(cloud.readText(mf.get().id()))));
            } catch (IllegalArgumentException ignored) {
                // manifeste illisible : session ignorée
            }
        }
        return result;
    }

    public Session importSession(RemoteSessionInfo info, Path savesDir, String folderName) {
        requireConnected();
        Path worldDir = savesDir.resolve(folderName);
        if (findByPath(worldDir).isPresent()) {
            throw new IllegalArgumentException("Ce dossier est déjà lié à une session.");
        }
        Session s = Session.create(info.displayName(), worldDir);
        s.setRemoteFolderId(info.folderId());
        s.setMinecraftVersion(info.manifest().minecraftVersion());
        s.setModded(info.manifest().modded());
        s.setSyncEnabled(true);
        s.setState(SyncState.IDLE);
        db.saveSession(s);
        sessions.add(s);
        fireSessionsChanged();
        requestSync(s, Trigger.CREATED);
        return s;
    }

    /** Retire la session de l'application. Le monde local et le dossier distant sont conservés. */
    public void removeSession(Session s) {
        db.deleteSession(s.id());
        sessions.removeIf(x -> x.id().equals(s.id()));
        conflictInfos.remove(s.id());
        fireSessionsChanged();
    }

    public void saveSession(Session s) {
        db.saveSession(s);
        fire(s);
    }

    public void setSyncEnabled(Session s, boolean enabled) {
        s.setSyncEnabled(enabled);
        if (!isSyncing(s)) {
            if (!enabled) setState(s, SyncState.DISABLED, null);
            else if (s.state() == SyncState.DISABLED) setState(s, isConnected() ? SyncState.IDLE : SyncState.OFFLINE, null);
        }
        db.saveSession(s);
        fire(s);
        if (enabled && isConnected()) requestSync(s, Trigger.MANUAL);
    }

    public boolean isSyncing(Session s) {
        return running.contains(s.id());
    }

    public void setState(Session s, SyncState state, String message) {
        s.setState(state);
        s.setStatusMessage(message);
        db.saveSession(s);
        fire(s);
    }

    // ---------------- Synchronisation ----------------

    public CompletableFuture<SyncResult> requestSync(Session s, Trigger trigger) {
        return submit(s, e -> e.sync(s, progress(s)));
    }

    public CompletableFuture<SyncResult> resolveConflict(Session s, ConflictManager.Resolution resolution) {
        return submit(s, e -> e.resolve(s, resolution, progress(s)));
    }

    public SyncResult.ConflictInfo conflictInfo(Session s) throws IOException {
        SyncResult.ConflictInfo info = conflictInfos.get(s.id());
        if (info != null) return info;
        requireConnected();
        info = engine.inspect(s);
        conflictInfos.put(s.id(), info);
        return info;
    }

    /** Appelé par la surveillance quand Minecraft ouvre le monde (et périodiquement ensuite). */
    public void markWorldInUse(Session s) {
        if (s.state() != SyncState.WORLD_IN_USE && s.state() != SyncState.CONFLICT) {
            setState(s, SyncState.WORLD_IN_USE, null);
        }
        SyncEngine e = engine;
        if (e == null) return;
        worker.submit(() -> {
            try {
                e.markPlaying(s).ifPresent(other -> notice("Attention : « " + s.name()
                        + " » semble aussi ouvert sur « " + other.deviceName()
                        + " ». Un conflit sera probablement signalé à la fermeture."));
            } catch (IOException ignored) {
                // hors ligne : sans conséquence sur la sûreté (conflit détecté plus tard)
            }
        });
    }

    private CompletableFuture<SyncResult> submit(Session s, Function<SyncEngine, SyncResult> op) {
        SyncEngine e = engine;
        if (e == null) {
            if (s.syncEnabled()) setState(s, SyncState.OFFLINE, null);
            return CompletableFuture.completedFuture(SyncResult.error("Google Drive non connecté."));
        }
        if (!running.add(s.id())) {
            return CompletableFuture.completedFuture(null); // déjà en cours
        }
        setState(s, SyncState.SYNCING, "Préparation…");
        return CompletableFuture.supplyAsync(() -> {
            try {
                SyncResult r = op.apply(e);
                applyResult(s, r);
                return r;
            } catch (RuntimeException ex) {
                SyncResult r = SyncResult.error("Erreur inattendue : " + ex.getMessage());
                applyResult(s, r);
                return r;
            } finally {
                running.remove(s.id());
                fire(s);
            }
        }, worker);
    }

    private ProgressListener progress(Session s) {
        return (message, fraction) -> {
            long now = System.currentTimeMillis();
            Long last = lastProgressEvent.get(s.id());
            if (last != null && now - last < 250 && fraction < 1) return;
            lastProgressEvent.put(s.id(), now);
            s.setStatusMessage(fraction >= 0 ? message + " — " + Math.round(fraction * 100) + " %" : message);
            fire(s);
        };
    }

    private void applyResult(Session s, SyncResult r) {
        switch (r.outcome()) {
            case UP_TO_DATE, PUSHED, PULLED -> {
                conflictInfos.remove(s.id());
                if (r.outcome() == SyncResult.Outcome.PULLED) refreshWorldInfo(s);
                s.setState(s.syncEnabled() ? SyncState.UP_TO_DATE : SyncState.DISABLED);
                s.setStatusMessage(r.outcome() == SyncResult.Outcome.UP_TO_DATE ? null : r.message());
            }
            case CONFLICT -> {
                conflictInfos.put(s.id(), r.conflict());
                s.setState(SyncState.CONFLICT);
                s.setStatusMessage(r.message());
                notice("⚠ Conflit détecté sur « " + s.name() + " » : le monde a été modifié sur plusieurs appareils.");
            }
            case WORLD_IN_USE -> {
                s.setState(SyncState.WORLD_IN_USE);
                s.setStatusMessage(null);
            }
            case REMOTE_IN_USE -> {
                s.setState(SyncState.REMOTE_IN_USE);
                s.setStatusMessage(r.message());
            }
            case ERROR -> {
                s.setState(SyncState.ERROR);
                String detail = r.failedFiles().isEmpty() ? "" : "\n" + String.join("\n",
                        r.failedFiles().subList(0, Math.min(5, r.failedFiles().size())));
                s.setStatusMessage(r.message() + detail);
            }
        }
        db.saveSession(s);
    }

    private void refreshWorldInfo(Session s) {
        WorldInfo info = WorldInfo.read(s.localPath(), "");
        if (info.version() != null) s.setMinecraftVersion(info.version());
        if (info.modded() != null) s.setModded(info.modded());
    }

    private String uniqueFolderName(String name) throws IOException {
        Set<String> names = new HashSet<>();
        for (CloudStorage.RemoteItem it : cloud.listChildren(rootFolderId)) names.add(it.name());
        String base = name.isBlank() ? "Monde" : name.trim();
        String candidate = base;
        for (int i = 2; names.contains(candidate); i++) candidate = base + " (" + i + ")";
        return candidate;
    }

    private void requireConnected() {
        if (engine == null) throw new IllegalStateException("Connectez d'abord Google Drive.");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void fire(Session s) {
        listeners.forEach(l -> l.sessionUpdated(s));
    }

    private void fireSessionsChanged() {
        listeners.forEach(Listener::sessionsChanged);
    }

    private void notice(String message) {
        listeners.forEach(l -> l.notice(message));
    }

    /** Attend la fin des synchronisations en cours (au plus {@code seconds}). */
    @Override
    public void close() {
        worker.shutdown();
        try {
            worker.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean anySyncRunning() {
        return !running.isEmpty();
    }
}
