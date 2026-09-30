package com.minecraftsync.minecraft;

import com.minecraftsync.model.Session;
import com.minecraftsync.model.SyncState;
import com.minecraftsync.sync.SyncService;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Surveillance en tâche de fond, toutes les 5 secondes :
 * <ul>
 *   <li>monde ouvert dans Minecraft → synchro suspendue, verrou de présence déposé ;</li>
 *   <li>monde refermé → attente du délai de calme, puis synchronisation automatique ;</li>
 *   <li>sinon, vérification périodique des nouveautés distantes (réception automatique) ;</li>
 *   <li>après un échec, nouvel essai automatique.</li>
 * </ul>
 * Une interrogation régulière est préférée au {@code WatchService} : celui-ci ne signale pas
 * la fermeture d'un monde et se comporte différemment selon les systèmes.
 */
public class MinecraftManager implements AutoCloseable {

    public static final String SETTING_QUIET_DELAY = "quiet_delay_seconds";
    public static final String SETTING_CHECK_INTERVAL = "check_interval_minutes";

    private static final Duration HEARTBEAT = Duration.ofMinutes(5);
    private static final Duration RETRY_AFTER_ERROR = Duration.ofMinutes(5);

    private final SyncService service;
    private final MinecraftDetector detector;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "minecraft-monitor");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Boolean> wasInUse = new ConcurrentHashMap<>();
    private final Map<String, Instant> closedAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastHeartbeat = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastCheck = new ConcurrentHashMap<>();
    private volatile boolean minecraftRunning;
    private volatile Instant lastProcessScan = Instant.EPOCH;

    public MinecraftManager(SyncService service, MinecraftDetector detector) {
        this.service = service;
        this.detector = detector;
    }

    public void start() {
        timer.scheduleWithFixedDelay(this::tick, 2, 5, TimeUnit.SECONDS);
    }

    /** Information pour l'affichage (peu fiable sous Windows). */
    public boolean isMinecraftRunning() {
        return minecraftRunning;
    }

    /** Force une vérification distante rapide (après une reconnexion, par exemple). */
    public void checkSoon() {
        lastCheck.clear();
    }

    /** Un passage de surveillance (appelé toutes les 5 s ; visible pour les tests). */
    void tick() {
        try {
            Instant now = Instant.now();
            if (Duration.between(lastProcessScan, now).toSeconds() >= 30) {
                lastProcessScan = now;
                minecraftRunning = detector.isMinecraftRunning();
            }
            for (Session s : service.sessions()) {
                handle(s, now);
            }
        } catch (Throwable t) {
            // la surveillance ne doit jamais s'arrêter
            System.err.println("[surveillance] " + t);
        }
    }

    private void handle(Session s, Instant now) {
        String id = s.id();
        if (!s.syncEnabled() || service.isSyncing(s)) return;

        boolean inUse = detector.isWorldInUse(s.localPath());
        boolean before = wasInUse.getOrDefault(id, false);
        wasInUse.put(id, inUse);

        if (inUse) {
            closedAt.remove(id);
            Instant hb = lastHeartbeat.get(id);
            if (!before || hb == null || Duration.between(hb, now).compareTo(HEARTBEAT) >= 0) {
                lastHeartbeat.put(id, now);
                service.markWorldInUse(s);
            }
            return;
        }

        int quietDelay = service.database().getIntSetting(SETTING_QUIET_DELAY, 10);
        if (before) {
            lastHeartbeat.remove(id);
            closedAt.put(id, now);
            if (s.state() != SyncState.CONFLICT) {
                service.setState(s, SyncState.WAITING_AFTER_CLOSE, "Synchronisation dans " + quietDelay + " s");
            }
            return;
        }

        Instant closed = closedAt.get(id);
        if (closed != null) {
            if (Duration.between(closed, now).toSeconds() >= quietDelay) {
                closedAt.remove(id);
                lastCheck.put(id, now);
                if (s.state() != SyncState.CONFLICT) service.requestSync(s, SyncService.Trigger.AFTER_PLAY);
            }
            return;
        }

        if (s.state() == SyncState.CONFLICT || !service.isConnected()) return;

        Duration interval = s.state() == SyncState.ERROR
                ? RETRY_AFTER_ERROR
                : Duration.ofMinutes(Math.max(1, service.database().getIntSetting(SETTING_CHECK_INTERVAL, 2)));
        Instant last = lastCheck.get(id);
        if (last == null || Duration.between(last, now).compareTo(interval) >= 0) {
            lastCheck.put(id, now);
            service.requestSync(s, SyncService.Trigger.PERIODIC);
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
