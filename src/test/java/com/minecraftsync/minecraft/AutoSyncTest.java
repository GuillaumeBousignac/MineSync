package com.minecraftsync.minecraft;

import com.minecraftsync.database.Database;
import com.minecraftsync.model.Session;
import com.minecraftsync.model.SyncState;
import com.minecraftsync.sync.ConflictManager;
import com.minecraftsync.sync.LocalFolderStorage;
import com.minecraftsync.sync.Manifest;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.sync.TestWorlds;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scénario « jouer puis quitter » : la surveillance suspend la synchro pendant la partie,
 * puis envoie automatiquement le monde à sa fermeture.
 */
class AutoSyncTest {

    @Test
    void worldIsUploadedAutomaticallyAfterClosing() throws Exception {
        Path tmp = Files.createTempDirectory("mcsync-auto");
        Database db = new Database(tmp.resolve("db.sqlite"));
        SyncService service = new SyncService(db, new MinecraftDetector(), new ConflictManager(tmp.resolve("backups"), 3));
        try {
            db.setSetting(MinecraftManager.SETTING_QUIET_DELAY, "0");
            LocalFolderStorage cloud = new LocalFolderStorage(tmp.resolve("cloud"));
            String root = cloud.createFolder(LocalFolderStorage.ROOT, "Minecraft Sync");
            service.connect(cloud, root);
            service.engine().setRetryPolicy(1, 0);

            Path world = tmp.resolve("saves/Survival");
            TestWorlds.createWorld(world, "1.21.10", List.of("vanilla"), 3);
            Session s = service.createSession(WorldInfo.read(world, "test"), "Ma Survie", "1.21.10", false, true);
            waitIdle(service, s);
            assertEquals(SyncState.UP_TO_DATE, s.state(), s.statusMessage());

            MinecraftManager manager = new MinecraftManager(service, new MinecraftDetector());
            try (FileChannel ch = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE)) {
                FileLock lock = ch.lock();          // Minecraft ouvre le monde
                manager.tick();
                assertEquals(SyncState.WORLD_IN_USE, s.state());
                TestWorlds.touch(world.resolve("region/r.0.0.mca"), "deux heures de jeu");
                manager.tick();
                assertEquals(SyncState.WORLD_IN_USE, s.state());
                lock.release();                     // Minecraft ferme le monde
            }
            manager.tick();
            assertEquals(SyncState.WAITING_AFTER_CLOSE, s.state());
            manager.tick();                         // délai de calme écoulé → synchro
            waitIdle(service, s);
            assertEquals(SyncState.UP_TO_DATE, s.state(), s.statusMessage());

            String id = cloud.findChild(s.remoteFolderId(), Manifest.FILE_NAME).orElseThrow().id();
            Manifest m = Manifest.fromJson(cloud.readText(id));
            assertEquals(2L, m.revision());
            assertTrue(s.lastSync() != null);
        } finally {
            service.close();
            db.close();
            ConflictManager.deleteRecursively(tmp);
        }
    }

    private static void waitIdle(SyncService service, Session s) throws InterruptedException {
        for (int i = 0; i < 200 && service.isSyncing(s); i++) Thread.sleep(50);
        Thread.sleep(50);
    }
}
