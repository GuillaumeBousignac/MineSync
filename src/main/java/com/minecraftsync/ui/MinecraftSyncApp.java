package com.minecraftsync.ui;

import com.minecraftsync.database.Database;
import com.minecraftsync.google.DriveConnection;
import com.minecraftsync.google.GoogleAuth;
import com.minecraftsync.minecraft.MinecraftDetector;
import com.minecraftsync.minecraft.MinecraftManager;
import com.minecraftsync.minecraft.WorldDetector;
import com.minecraftsync.sync.ConflictManager;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.util.AppPaths;
import javafx.application.Application;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class MinecraftSyncApp extends Application {

    public static final String SETTING_BACKUPS = "backups_to_keep";

    private Database db;
    private SyncService service;
    private MinecraftManager manager;

    @Override
    public void start(Stage stage) {
        db = new Database(AppPaths.database());
        MinecraftDetector detector = new MinecraftDetector();
        ConflictManager conflicts = new ConflictManager(AppPaths.backupsDir(), db.getIntSetting(SETTING_BACKUPS, 3));
        service = new SyncService(db, detector, conflicts);
        DriveConnection drive = new DriveConnection(
                new GoogleAuth(AppPaths.credentialsFile(), AppPaths.tokensDir()), service, db);
        manager = new MinecraftManager(service, detector);

        MainWindow window = new MainWindow(stage, service, drive, manager, new WorldDetector(), getHostServices());
        setWindowIcons(stage);
        window.show();
        manager.start();
        window.connectAtStartup();
    }

    /**
     * Icône de la fenêtre et de la barre des tâches (Windows, Linux).
     * Sous macOS, le Dock utilise l'icône .icns du paquet .app.
     */
    private static void setWindowIcons(Stage stage) {
        var url = MinecraftSyncApp.class.getResource("logo.png");
        if (url == null) return;
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            Image img = new Image(url.toExternalForm(), size, size, true, true);
            if (!img.isError()) stage.getIcons().add(img);
        }
    }

    @Override
    public void stop() {
        if (manager != null) manager.close();
        if (service != null) service.close(); // attend la fin d'une synchronisation en cours
        if (db != null) db.close();
    }
}
