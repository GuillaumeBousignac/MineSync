package com.minecraftsync.ui;

import com.minecraftsync.google.DriveConnection;
import com.minecraftsync.google.GoogleAuth;
import com.minecraftsync.minecraft.MinecraftManager;
import com.minecraftsync.minecraft.WorldDetector;
import com.minecraftsync.model.RemoteSessionInfo;
import com.minecraftsync.model.Session;
import com.minecraftsync.sync.ConflictManager;
import com.minecraftsync.sync.SyncResult;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.util.Desktops;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Fenêtre principale : état de Google Drive et liste des sessions. */
public class MainWindow {

    private final Stage stage;
    private final SyncService service;
    private final DriveConnection drive;
    private final MinecraftManager manager;
    private final WorldDetector worldDetector;
    private final HostServices host;

    private final Label driveStatus = new Label();
    private final Button driveButton = new Button();
    private final Label minecraftStatus = new Label();
    private final VBox sessionsBox = new VBox(12);
    private final VBox noticeBox = new VBox(6);
    private final Map<String, SessionView> views = new LinkedHashMap<>();
    private volatile boolean connecting;

    public MainWindow(Stage stage, SyncService service, DriveConnection drive, MinecraftManager manager,
                      WorldDetector worldDetector, HostServices host) {
        this.stage = stage;
        this.service = service;
        this.drive = drive;
        this.manager = manager;
        this.worldDetector = worldDetector;
        this.host = host;
        build();
        service.addListener(new SyncService.Listener() {
            @Override
            public void sessionUpdated(Session session) {
                Platform.runLater(() -> {
                    SessionView v = views.get(session.id());
                    if (v != null) v.update();
                });
            }

            @Override
            public void sessionsChanged() {
                Platform.runLater(MainWindow.this::rebuildSessions);
            }

            @Override
            public void notice(String message) {
                Platform.runLater(() -> showNotice(message, null));
            }
        });
    }

    // =====================================================================
    // Construction
    // =====================================================================

    private void build() {
        Label title = new Label("Minecraft Sync");
        title.getStyleClass().add("app-title");
        Button settings = new Button("⚙");
        settings.getStyleClass().add("icon-button");
        settings.setOnAction(e -> new SettingsView(stage, service, drive, worldDetector).showAndWait());
        HBox header = new HBox(12, title, spacer(), minecraftStatus, settings);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        minecraftStatus.getStyleClass().add("muted");

        driveStatus.getStyleClass().add("drive-status");
        driveButton.setOnAction(e -> onDriveButton());
        HBox driveBox = new HBox(12, driveStatus, spacer(), driveButton);
        driveBox.setAlignment(Pos.CENTER_LEFT);
        driveBox.getStyleClass().add("drive-box");

        Label sessionsTitle = new Label("MES SESSIONS");
        sessionsTitle.getStyleClass().add("section-title");

        ScrollPane scroll = new ScrollPane(sessionsBox);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sessions-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        sessionsBox.setPadding(new Insets(4, 2, 4, 2));

        Button add = new Button("+ Ajouter une session");
        add.getStyleClass().add("primary");
        add.setOnAction(e -> onAddSession());
        Button available = new Button("☁ Sessions disponibles sur Google Drive");
        available.setOnAction(e -> onImportSession());
        HBox actions = new HBox(10, add, available);
        actions.setAlignment(Pos.CENTER);

        VBox content = new VBox(14, driveBox, noticeBox, sessionsTitle, scroll, actions);
        content.setPadding(new Insets(18));

        BorderPane root = new BorderPane(content);
        root.setTop(header);
        Scene scene = new Scene(root, 720, 700);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Minecraft Sync");
        stage.setMinWidth(560);
        stage.setMinHeight(480);
        stage.setOnCloseRequest(e -> {
            if (service.anySyncRunning()) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                        "Une synchronisation est en cours. Quitter maintenant l'interrompt (le monde reste intact, "
                                + "la synchronisation reprendra au prochain lancement).\n\nQuitter quand même ?",
                        Ui.YES, Ui.NO);
                a.setHeaderText("Synchronisation en cours");
                a.initOwner(stage);
                if (a.showAndWait().orElse(Ui.NO) != Ui.YES) e.consume();
            }
        });

        // Rafraîchit « il y a X minutes » et l'état de Minecraft
        Timeline tick = new Timeline(new KeyFrame(Duration.seconds(20), e -> {
            views.values().forEach(SessionView::update);
            updateMinecraftStatus();
        }));
        tick.setCycleCount(Timeline.INDEFINITE);
        tick.play();

        rebuildSessions();
        updateDriveStatus();
        updateMinecraftStatus();
    }

    public void show() {
        stage.show();
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private void rebuildSessions() {
        views.clear();
        sessionsBox.getChildren().clear();
        List<Session> sessions = service.sessions();
        if (sessions.isEmpty()) {
            Label empty = new Label("Aucune session. Ajoutez un monde avec « + Ajouter une session », "
                    + "ou récupérez un monde déjà envoyé depuis un autre PC.");
            empty.setWrapText(true);
            empty.getStyleClass().add("muted");
            sessionsBox.getChildren().add(empty);
            return;
        }
        for (Session s : sessions) {
            SessionView v = new SessionView(s, service, new SessionView.Actions() {
                @Override
                public void syncNow(Session session) {
                    if (!service.isConnected()) {
                        showNotice("Connectez d'abord Google Drive.", null);
                        return;
                    }
                    service.requestSync(session, SyncService.Trigger.MANUAL);
                }

                @Override
                public void resolveConflict(Session session) {
                    onResolveConflict(session);
                }

                @Override
                public void openSettings(Session session) {
                    new SessionSettingsDialog(stage, session, service, MainWindow.this::restoreFromRemote).showAndWait();
                }

                @Override
                public void openFolder(Session session) {
                    try {
                        Desktops.openFolder(session.localPath());
                    } catch (Exception ex) {
                        showError("Ouverture impossible", ex);
                    }
                }

                @Override
                public void remove(Session session) {
                    onRemoveSession(session);
                }
            });
            views.put(s.id(), v);
            sessionsBox.getChildren().add(v);
        }
    }

    private void updateMinecraftStatus() {
        minecraftStatus.setText(manager.isMinecraftRunning() ? "Minecraft : lancé" : "");
    }

    // =====================================================================
    // Google Drive
    // =====================================================================

    private void updateDriveStatus() {
        if (connecting) {
            driveStatus.setText("☁ Google Drive : connexion en cours…");
            driveButton.setText("Annuler");
        } else if (drive.isConnected()) {
            driveStatus.setText("☁ Google Drive : Connecté (" + drive.email() + ") — dossier « " + drive.rootName() + " »");
            driveButton.setText("Déconnecter");
        } else {
            driveStatus.setText("☁ Google Drive : Non connecté");
            driveButton.setText("Connecter Google Drive");
        }
    }

    /** Reconnexion silencieuse au démarrage si un jeton existe déjà. */
    public void connectAtStartup() {
        if (!drive.canConnectSilently()) return;
        connecting = true;
        updateDriveStatus();
        background(() -> drive.connect(null), email -> {
            connecting = false;
            updateDriveStatus();
            afterConnected();
        }, err -> {
            connecting = false;
            updateDriveStatus();
            showNotice("Connexion automatique à Google Drive impossible : " + message(err)
                    + " — les mondes restent intacts. Réessayez avec « Connecter Google Drive ».", null);
        });
    }

    private void onDriveButton() {
        if (connecting) {
            drive.auth().cancelPending();
            return;
        }
        if (drive.isConnected()) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "La synchronisation sera suspendue. Aucun fichier n'est supprimé, ni sur ce PC, ni sur Google Drive.",
                    Ui.OK, Ui.CANCEL);
            a.setHeaderText("Déconnecter Google Drive ?");
            a.initOwner(stage);
            if (a.showAndWait().orElse(Ui.CANCEL) == Ui.OK) {
                try {
                    drive.disconnect();
                } catch (Exception ex) {
                    showError("Déconnexion", ex);
                }
                updateDriveStatus();
            }
            return;
        }

        if (!drive.auth().hasCredentialsFile() && !askCredentialsFile()) return;
        if (!drive.rootNameChosen()) {
            TextInputDialog d = new TextInputDialog(drive.rootName());
            d.getDialogPane().getButtonTypes().setAll(Ui.OK, Ui.CANCEL);
            d.initOwner(stage);
            d.setTitle("Dossier Google Drive");
            d.setHeaderText("Dossier racine dédié à Minecraft Sync");
            d.setContentText("Nom du dossier (identique sur tous vos PC) :");
            Optional<String> name = d.showAndWait();
            if (name.isEmpty() || name.get().isBlank()) return;
            drive.setRootName(name.get());
        }

        connecting = true;
        updateDriveStatus();
        background(() -> drive.connect(url -> Platform.runLater(() -> {
            host.showDocument(url);
            showNotice("Autorisez l'accès dans votre navigateur. S'il ne s'est pas ouvert, copiez cette adresse :", url);
        })), email -> {
            connecting = false;
            noticeBox.getChildren().clear();
            updateDriveStatus();
            afterConnected();
        }, err -> {
            connecting = false;
            noticeBox.getChildren().clear();
            updateDriveStatus();
            if (err instanceof GoogleAuth.CancelledException) {
                showNotice("Connexion à Google Drive annulée.", null);
            } else {
                showError("Connexion à Google Drive impossible", err);
            }
        });
    }

    private boolean askCredentialsFile() {
        Alert info = new Alert(Alert.AlertType.INFORMATION,
                "Pour utiliser votre propre accès à Google Drive, sélectionnez le fichier credentials.json "
                        + "(identifiant OAuth « Application de bureau ») créé dans Google Cloud.\n\n"
                        + "La marche à suivre est décrite dans le README, section « Google Cloud ».\n"
                        + "Utilisez le MÊME fichier sur tous vos PC.", Ui.OK);
        info.setHeaderText("Fichier d'identification Google requis");
        info.initOwner(stage);
        info.showAndWait();
        FileChooser fc = new FileChooser();
        fc.setTitle("Choisir credentials.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Fichier JSON", "*.json"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return false;
        try {
            drive.auth().importCredentials(f.toPath());
            return true;
        } catch (Exception ex) {
            showError("Fichier refusé", ex);
            return false;
        }
    }

    /** Après connexion : vérification immédiate et signalement des sessions à récupérer. */
    private void afterConnected() {
        manager.checkSoon();
        background(service::listRemoteSessions, list -> {
            if (!list.isEmpty()) {
                showNotice(list.size() + " session(s) disponible(s) sur Google Drive, pas encore ajoutée(s) à ce PC : "
                        + "utilisez « Sessions disponibles sur Google Drive ».", null);
            }
        }, err -> { });
    }

    // =====================================================================
    // Sessions
    // =====================================================================

    private void onAddSession() {
        if (!service.isConnected()) {
            showNotice("Connectez d'abord Google Drive : chaque session a besoin de son dossier distant.", null);
            return;
        }
        Optional<AddSessionDialog.Request> req = new AddSessionDialog(stage, service, worldDetector).showAndWait();
        req.ifPresent(r -> background(
                () -> service.createSession(r.world(), r.name(), r.version(), r.modded(), r.enabled()),
                s -> { },
                err -> showError("Création de la session impossible", err)));
    }

    private void onImportSession() {
        if (!service.isConnected()) {
            showNotice("Connectez d'abord Google Drive.", null);
            return;
        }
        background(service::listRemoteSessions, (List<RemoteSessionInfo> list) -> {
            if (list.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.INFORMATION,
                        "Aucune nouvelle session sur Google Drive (dossier « " + drive.rootName() + " »).\n"
                                + "Vérifiez que ce PC utilise le même compte Google, le même credentials.json "
                                + "et le même nom de dossier racine que l'autre PC.", Ui.OK);
                a.setHeaderText("Rien à récupérer");
                a.initOwner(stage);
                a.showAndWait();
                return;
            }
            new ImportSessionDialog(stage, list, worldDetector, service).showAndWait().ifPresent(r -> {
                try {
                    service.importSession(r.info(), r.savesDir(), r.folderName());
                } catch (Exception ex) {
                    showError("Ajout impossible", ex);
                }
            });
        }, err -> showError("Lecture de Google Drive impossible", err));
    }

    private void onRemoveSession(Session s) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "La session « " + s.name() + " » sera retirée de Minecraft Sync sur ce PC.\n\n"
                        + "Le monde local n'est PAS supprimé :\n" + s.localPathString()
                        + "\n\nLa copie sur Google Drive est conservée.",
                Ui.OK, Ui.CANCEL);
        a.setHeaderText("Retirer la session ?");
        a.initOwner(stage);
        if (a.showAndWait().orElse(Ui.CANCEL) == Ui.OK) {
            if (service.isSyncing(s)) {
                showNotice("Synchronisation en cours : réessayez dans un instant.", null);
                return;
            }
            service.removeSession(s);
        }
    }

    private void onResolveConflict(Session s) {
        background(() -> service.conflictInfo(s), (SyncResult.ConflictInfo info) ->
                new ConflictDialog(stage, s, info).showAndWait().ifPresent(res -> service.resolveConflict(s, res)),
                err -> showError("Analyse du conflit impossible", err));
    }

    /** Récupère explicitement la version distante (avec sauvegarde locale préalable). */
    private void restoreFromRemote(Session s) {
        if (!service.isConnected()) {
            showNotice("Connectez d'abord Google Drive.", null);
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Le monde local sera remplacé par la version de Google Drive.\n"
                        + "Une copie de sauvegarde du monde local sera créée auparavant.",
                Ui.OK, Ui.CANCEL);
        a.setHeaderText("Restaurer « " + s.name() + " » depuis Google Drive ?");
        a.initOwner(stage);
        if (a.showAndWait().orElse(Ui.CANCEL) == Ui.OK) {
            service.resolveConflict(s, ConflictManager.Resolution.KEEP_REMOTE);
        }
    }

    // =====================================================================
    // Outils
    // =====================================================================

    private void showNotice(String message, String copyable) {
        Label l = new Label(message);
        l.setWrapText(true);
        Button close = new Button("✕");
        close.getStyleClass().add("icon-button");
        VBox text = new VBox(4, l);
        if (copyable != null) {
            TextField field = new TextField(copyable);
            field.setEditable(false);
            text.getChildren().add(field);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(8, text, close);
        box.getStyleClass().add("notice");
        close.setOnAction(e -> noticeBox.getChildren().remove(box));
        noticeBox.getChildren().add(box);
        if (noticeBox.getChildren().size() > 4) noticeBox.getChildren().remove(0);
    }

    private void showError(String title, Throwable err) {
        Alert a = new Alert(Alert.AlertType.ERROR, message(err), Ui.OK);
        a.setHeaderText(title);
        a.initOwner(stage);
        a.showAndWait();
    }

    static String message(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c.getMessage() == null || c.getMessage().isBlank())) c = c.getCause();
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private <T> void background(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Thread t = new Thread(() -> {
            try {
                T r = work.call();
                Platform.runLater(() -> onSuccess.accept(r));
            } catch (Throwable e) {
                Platform.runLater(() -> onError.accept(e));
            }
        }, "ui-task");
        t.setDaemon(true);
        t.start();
    }
}
