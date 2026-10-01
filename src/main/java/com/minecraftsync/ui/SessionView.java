package com.minecraftsync.ui;

import com.minecraftsync.model.Session;
import com.minecraftsync.model.SyncState;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.util.TimeFormat;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.nio.file.Files;
import java.nio.file.Path;

/** Carte affichant une session et ses actions. */
public class SessionView extends VBox {

    /** Actions déléguées à la fenêtre principale. */
    public interface Actions {
        void syncNow(Session s);

        void resolveConflict(Session s);

        void openSettings(Session s);

        void openFolder(Session s);

        void remove(Session s);
    }

    private final Session session;
    private final SyncService service;

    private final Label name = new Label();
    private final Label version = new Label();
    private final Circle dot = new Circle(5);
    private final Label state = new Label();
    private final Label lastSync = new Label();
    private final Label message = new Label();
    private final HBox stateLine = new HBox(7);
    private final CheckBox auto = new CheckBox("Synchronisation automatique");
    private final Button syncButton = new Button("Synchroniser");
    private final Button conflictButton = new Button("Résoudre le conflit");

    public SessionView(Session session, SyncService service, Actions actions) {
        this.session = session;
        this.service = service;
        getStyleClass().add("card");
        setSpacing(10);

        name.getStyleClass().add("session-name");
        version.getStyleClass().add("muted");
        lastSync.getStyleClass().add("muted");
        message.getStyleClass().add("session-message");
        message.setWrapText(true);
        dot.getStyleClass().add("state-dot");
        state.getStyleClass().add("state-text");
        stateLine.getChildren().setAll(dot, state);
        stateLine.setAlignment(Pos.CENTER_LEFT);
        stateLine.getStyleClass().add("state-pill");
        stateLine.setMaxWidth(Region.USE_PREF_SIZE);

        VBox info = new VBox(5, name, version, stateLine, lastSync, message);
        HBox.setHgrow(info, Priority.ALWAYS);
        info.setMinWidth(0);

        auto.setOnAction(e -> service.setSyncEnabled(session, auto.isSelected()));
        syncButton.setOnAction(e -> actions.syncNow(session));
        syncButton.getStyleClass().add("sync-button");
        conflictButton.getStyleClass().add("danger");
        conflictButton.setOnAction(e -> actions.resolveConflict(session));

        MenuItem settings = new MenuItem("Paramètres…");
        settings.setOnAction(e -> actions.openSettings(session));
        MenuItem open = new MenuItem("Ouvrir le dossier du monde");
        open.setOnAction(e -> actions.openFolder(session));
        MenuItem remove = new MenuItem("Retirer la session…");
        remove.setOnAction(e -> actions.remove(session));
        remove.getStyleClass().add("menu-danger");
        MenuButton more = new MenuButton("Plus", null, settings, open, new SeparatorMenuItem(), remove);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actionsRow = new HBox(8, auto, gap, conflictButton, syncButton, more);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        actionsRow.getStyleClass().add("card-actions");

        HBox top = new HBox(14, icon(session.localPath()), info);
        top.setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(top, actionsRow);
        update();
    }

    /** Icône du monde (icon.png, créée par Minecraft) ou pastille par défaut. */
    private static Node icon(Path world) {
        Path png = world.resolve("icon.png");
        if (Files.isRegularFile(png)) {
            try {
                Image img = new Image(png.toUri().toString(), 48, 48, true, false);
                if (!img.isError()) {
                    ImageView v = new ImageView(img);
                    v.setFitWidth(48);
                    v.setFitHeight(48);
                    return v;
                }
            } catch (Exception ignored) {
                // icône par défaut
            }
        }
        Region r = new Region();
        r.getStyleClass().add("world-icon");
        r.setPrefSize(48, 48);
        r.setMinSize(48, 48);
        r.setMaxSize(48, 48);
        return new StackPane(r);
    }

    public void update() {
        name.setText(session.name());
        version.setText(session.describeVersion());

        SyncState st = session.state();
        boolean syncing = service.isSyncing(session);
        if (syncing) st = SyncState.SYNCING;
        state.setText(st.label());
        String cls = "state-" + st.styleClass();
        for (String c : new String[]{"state-ok", "state-warn", "state-error", "state-busy", "state-neutral"}) {
            if (!c.equals(cls)) getStyleClass().remove(c);
        }
        if (!getStyleClass().contains(cls)) getStyleClass().add(cls);

        String when = session.lastSync() == null ? "jamais"
                : TimeFormat.relative(session.lastSync())
                + (session.lastSyncDevice() != null ? " (" + session.lastSyncDevice() + ")" : "");
        lastSync.setText("Dernière synchronisation : " + when);

        String msg = session.statusMessage();
        message.setText(msg == null ? "" : msg);
        message.setVisible(msg != null && !msg.isBlank());
        message.setManaged(message.isVisible());
        message.getStyleClass().removeAll("msg-error", "msg-normal");
        message.getStyleClass().add(st == SyncState.ERROR || st == SyncState.CONFLICT ? "msg-error" : "msg-normal");

        auto.setSelected(session.syncEnabled());
        syncButton.setDisable(syncing);
        boolean conflict = st == SyncState.CONFLICT;
        conflictButton.setVisible(conflict);
        conflictButton.setManaged(conflict);
    }
}