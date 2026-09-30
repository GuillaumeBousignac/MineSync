package com.minecraftsync.sync;

import com.minecraftsync.database.Database;
import com.minecraftsync.minecraft.MinecraftDetector;
import com.minecraftsync.model.DeviceIdentity;
import com.minecraftsync.model.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scénarios de bout en bout avec deux « PC » (bases, mondes et identités distincts)
 * partageant un même stockage distant simulé.
 */
class SyncEngineTest {

    private Path tmp;
    private LocalFolderStorage cloud;
    private String rootId;
    private Pc a;
    private Pc b;

    /** Un PC : sa base, son moteur, son monde. */
    private final class Pc {
        final Database db;
        final SyncEngine engine;
        final Path world;
        Session session;

        Pc(String name, String worldFolder) throws Exception {
            Path dir = tmp.resolve(name);
            Files.createDirectories(dir);
            db = new Database(dir.resolve("db.sqlite"));
            DeviceIdentity device = new DeviceIdentity("id-" + name, name);
            engine = new SyncEngine(db, cloud, new MinecraftDetector(), device,
                    new ConflictManager(dir.resolve("backups"), 3));
            engine.setRetryPolicy(2, 0);
            world = dir.resolve("saves").resolve(worldFolder);
        }

        void link(String remoteFolderId) {
            session = Session.create("Ma Survie", world);
            session.setRemoteFolderId(remoteFolderId);
            session.setMinecraftVersion("1.21.10");
            db.saveSession(session);
        }

        SyncResult sync() {
            SyncResult r = engine.sync(session, ProgressListener.NONE);
            db.saveSession(session);
            return r;
        }

        Path backups() {
            return world.getParent().getParent().resolve("backups");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tmp = Files.createTempDirectory("mcsync-test");
        cloud = new LocalFolderStorage(tmp.resolve("cloud"));
        rootId = cloud.createFolder(LocalFolderStorage.ROOT, "Minecraft Sync");
        a = new Pc("PC-A", "Survival");
        b = new Pc("PC-B", "Survival");
        TestWorlds.createWorld(a.world, "1.21.10", List.of("fabric"), 1);
        String remote = cloud.createFolder(rootId, "Ma Survie");
        a.link(remote);
        b.link(remote);
    }

    @AfterEach
    void tearDown() throws Exception {
        a.db.close();
        b.db.close();
        ConflictManager.deleteRecursively(tmp);
    }

    private Manifest remoteManifest() throws Exception {
        String id = cloud.findChild(a.session.remoteFolderId(), Manifest.FILE_NAME).orElseThrow().id();
        return Manifest.fromJson(cloud.readText(id));
    }

    // 1-4 : création, première synchro, récupération sur le deuxième PC
    @Test
    void firstPushThenImportOnSecondPc() throws Exception {
        SyncResult r = a.sync();
        assertEquals(SyncResult.Outcome.PUSHED, r.outcome(), r.message());
        assertEquals(8, r.uploaded(), "tous les fichiers sauf session.lock");
        Manifest m = remoteManifest();
        assertEquals(1L, m.revision());
        assertFalse(m.files().containsKey("session.lock"));
        assertTrue(m.files().containsKey("data/mymod/custom_machines.dat"), "fichiers de mods conservés");

        SyncResult rb = b.sync();
        assertEquals(SyncResult.Outcome.PULLED, rb.outcome(), rb.message());
        assertEquals(TestWorlds.tree(a.world), TestWorlds.tree(b.world));
        assertTrue(Files.isDirectory(b.world.resolve("datapacks")), "dossier vide recréé");
        assertFalse(Files.exists(b.world.resolve(FileComparator.TMP_DIR)), "dossier technique nettoyé");
    }

    // 5 et 8 : synchronisation différentielle
    @Test
    void onlyChangedFilesAreUploaded() throws Exception {
        a.sync();
        assertEquals(SyncResult.Outcome.UP_TO_DATE, a.sync().outcome());

        int before = cloud.uploads.get();
        TestWorlds.touch(a.world.resolve("level.dat"), "x");
        TestWorlds.touch(a.world.resolve("region/r.0.1.mca"), "y");
        SyncResult r = a.sync();
        assertEquals(SyncResult.Outcome.PUSHED, r.outcome(), r.message());
        assertEquals(2, r.uploaded());
        assertEquals(before + 2, cloud.uploads.get());
        assertEquals(2L, remoteManifest().revision());

        // Les anciennes versions ne sont plus visibles dans le dossier distant
        String filesId = cloud.findChild(a.session.remoteFolderId(), SyncEngine.FILES_FOLDER).orElseThrow().id();
        assertEquals(8, cloud.listChildren(filesId).size());
    }

    // 9 : aller-retour A → B → A, avec ajout et suppression de fichiers
    @Test
    void roundTripBothDirections() throws Exception {
        a.sync();
        b.sync();

        TestWorlds.touch(b.world.resolve("region/r.0.0.mca"), "joué sur B");
        TestWorlds.writeRandom(b.world.resolve("region/r.5.5.mca"), 10_000, new java.util.Random(9));
        Files.delete(b.world.resolve("serverconfig/mymod-server.toml"));
        SyncResult rb = b.sync();
        assertEquals(SyncResult.Outcome.PUSHED, rb.outcome(), rb.message());
        assertEquals(1, rb.deleted());

        SyncResult ra = a.sync();
        assertEquals(SyncResult.Outcome.PULLED, ra.outcome(), ra.message());
        assertEquals(TestWorlds.tree(b.world), TestWorlds.tree(a.world));
        assertFalse(Files.exists(a.world.resolve("serverconfig/mymod-server.toml")));

        // Et de nouveau A → B
        TestWorlds.touch(a.world.resolve("level.dat"), "retour sur A");
        assertEquals(SyncResult.Outcome.PUSHED, a.sync().outcome());
        assertEquals(SyncResult.Outcome.PULLED, b.sync().outcome());
        assertEquals(TestWorlds.tree(a.world), TestWorlds.tree(b.world));
    }

    // 10 : conflit simple, résolu en gardant la version distante (avec sauvegarde locale)
    @Test
    void conflictDetectedAndResolvedWithBackup() throws Exception {
        a.sync();
        b.sync();
        TestWorlds.touch(a.world.resolve("level.dat"), "A");
        TestWorlds.touch(b.world.resolve("level.dat"), "B");
        Map<String, String> bBefore = TestWorlds.tree(b.world);

        assertEquals(SyncResult.Outcome.PUSHED, a.sync().outcome());
        SyncResult rb = b.sync();
        assertEquals(SyncResult.Outcome.CONFLICT, rb.outcome());
        assertNotNull(rb.conflict());
        assertEquals("PC-A", rb.conflict().remoteDevice());
        // rien n'a été écrasé
        assertEquals(bBefore, TestWorlds.tree(b.world));
        assertEquals(2L, remoteManifest().revision());

        SyncResult resolved = b.engine.resolve(b.session, ConflictManager.Resolution.KEEP_REMOTE, ProgressListener.NONE);
        assertEquals(SyncResult.Outcome.PULLED, resolved.outcome(), resolved.message());
        assertEquals(TestWorlds.tree(a.world), TestWorlds.tree(b.world));

        // La version locale de B a été sauvegardée avant d'être remplacée
        Path backupWorld;
        try (Stream<Path> s = Files.walk(b.backups())) {
            backupWorld = s.filter(p -> p.getFileName().toString().equals("Survival") && Files.isDirectory(p))
                    .findFirst().orElseThrow();
        }
        assertEquals(bBefore, TestWorlds.tree(backupWorld));
    }

    @Test
    void conflictResolvedKeepingLocal() throws Exception {
        a.sync();
        b.sync();
        TestWorlds.touch(a.world.resolve("level.dat"), "A");
        TestWorlds.touch(b.world.resolve("level.dat"), "B");
        a.sync();
        assertEquals(SyncResult.Outcome.CONFLICT, b.sync().outcome());

        SyncResult r = b.engine.resolve(b.session, ConflictManager.Resolution.KEEP_LOCAL, ProgressListener.NONE);
        assertEquals(SyncResult.Outcome.PUSHED, r.outcome(), r.message());
        // A n'a pas modifié son monde depuis : il reçoit la version de B
        assertEquals(SyncResult.Outcome.PULLED, a.sync().outcome());
        assertEquals(TestWorlds.tree(b.world), TestWorlds.tree(a.world));
    }

    // 6 : monde ouvert dans Minecraft → aucune synchronisation
    @Test
    void worldInUseIsNeverSynced() throws Exception {
        try (FileChannel ch = FileChannel.open(a.world.resolve("session.lock"), StandardOpenOption.WRITE);
             FileLock lock = ch.lock()) {
            assertTrue(lock.isValid());
            assertEquals(SyncResult.Outcome.WORLD_IN_USE, a.sync().outcome());
            assertFalse(cloud.findChild(a.session.remoteFolderId(), Manifest.FILE_NAME).isPresent());
        }
        assertEquals(SyncResult.Outcome.PUSHED, a.sync().outcome());
    }

    // Panne réseau pendant l'envoi : la version distante reste cohérente, nouvel essai possible
    @Test
    void failedUploadKeepsRemoteConsistent() throws Exception {
        a.sync();
        b.sync();
        TestWorlds.touch(a.world.resolve("level.dat"), "v2");
        TestWorlds.touch(a.world.resolve("region/r.0.1.mca"), "v2");
        cloud.failUploadsWhen(name -> name.equals("region/r.0.1.mca"));

        SyncResult r = a.sync();
        assertEquals(SyncResult.Outcome.ERROR, r.outcome());
        assertFalse(r.failedFiles().isEmpty());
        assertEquals(1L, remoteManifest().revision(), "manifeste inchangé");
        assertTrue(Files.exists(a.world.resolve("level.dat")), "monde local intact");
        assertEquals(SyncResult.Outcome.UP_TO_DATE, b.sync().outcome(), "B ne voit aucune version partielle");

        cloud.failUploadsWhen(name -> false);
        assertEquals(SyncResult.Outcome.PUSHED, a.sync().outcome());
        assertEquals(SyncResult.Outcome.PULLED, b.sync().outcome());
        assertEquals(TestWorlds.tree(a.world), TestWorlds.tree(b.world));
    }

    // Monde supprimé ou disque débranché : aucune suppression propagée
    @Test
    void missingWorldNeverDeletesRemote() throws Exception {
        a.sync();
        ConflictManager.deleteRecursively(a.world);
        SyncResult r = a.sync();
        assertEquals(SyncResult.Outcome.ERROR, r.outcome());
        assertEquals(8, remoteManifest().files().size());

        // Restauration explicite depuis le stockage distant
        SyncResult restored = a.engine.resolve(a.session, ConflictManager.Resolution.KEEP_REMOTE, ProgressListener.NONE);
        assertEquals(SyncResult.Outcome.PULLED, restored.outcome(), restored.message());
        assertTrue(Files.exists(a.world.resolve("region/r.0.0.mca")));
    }

    // Verrou de présence : l'autre PC n'importe pas un monde en cours de partie
    @Test
    void remotePresenceLockBlocksOtherDevice() throws Exception {
        a.sync();
        b.sync();
        assertTrue(a.engine.markPlaying(a.session).isEmpty());
        assertEquals(SyncResult.Outcome.REMOTE_IN_USE, b.sync().outcome());

        TestWorlds.touch(a.world.resolve("level.dat"), "fin de partie");
        assertEquals(SyncResult.Outcome.PUSHED, a.sync().outcome(), "l'envoi libère le verrou");
        assertFalse(cloud.findChild(a.session.remoteFolderId(), RemoteLock.FILE_NAME).isPresent());
        assertEquals(SyncResult.Outcome.PULLED, b.sync().outcome());
    }

    // Manifeste distant illisible : jamais traité comme « absent »
    @Test
    void corruptedManifestIsNotOverwritten() throws Exception {
        a.sync();
        String id = cloud.findChild(a.session.remoteFolderId(), Manifest.FILE_NAME).orElseThrow().id();
        cloud.overwrite(id, "{ corrompu");
        TestWorlds.touch(a.world.resolve("level.dat"), "z");
        assertEquals(SyncResult.Outcome.ERROR, a.sync().outcome());
        assertEquals("{ corrompu", cloud.readText(id));
    }

    // Import sur un PC où un monde différent existe déjà au même endroit → conflit, pas d'écrasement
    @Test
    void importOverExistingDifferentWorldIsAConflict() throws Exception {
        a.sync();
        TestWorlds.createWorld(b.world, "1.20.1", null, 42);
        Map<String, String> before = TestWorlds.tree(b.world);
        assertEquals(SyncResult.Outcome.CONFLICT, b.sync().outcome());
        assertEquals(before, TestWorlds.tree(b.world));
    }
}
