package com.minecraftsync.ui;

import com.minecraftsync.model.Session;
import com.minecraftsync.sync.ConflictManager;
import com.minecraftsync.sync.SyncResult;
import com.minecraftsync.util.TimeFormat;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Choix entre la version locale et la version Google Drive. Aucun écrasement silencieux. */
public class ConflictDialog extends Dialog<ConflictManager.Resolution> {

    public ConflictDialog(Stage owner, Session session, SyncResult.ConflictInfo info) {
        initOwner(owner);
        setTitle("Conflit détecté");
        setHeaderText("⚠ Conflit détecté sur « " + session.name() + " »\n"
                + "Le monde a été modifié sur ce PC et sur un autre appareil.");

        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(6);
        Label localTitle = new Label("Version locale (ce PC)");
        Label remoteTitle = new Label("Version Google Drive");
        localTitle.getStyleClass().add("section-title");
        remoteTitle.getStyleClass().add("section-title");
        grid.add(localTitle, 0, 0);
        grid.add(remoteTitle, 1, 0);
        grid.add(new Label("Modifiée le " + TimeFormat.full(info.localModified())), 0, 1);
        grid.add(new Label("Envoyée le " + TimeFormat.full(info.remoteUpdatedAt())), 1, 1);
        grid.add(new Label(info.localFiles() + " fichiers · " + TimeFormat.size(info.localSize())), 0, 2);
        grid.add(new Label(info.remoteFiles() + " fichiers · " + TimeFormat.size(info.remoteSize())), 1, 2);
        grid.add(new Label(""), 0, 3);
        grid.add(new Label("Depuis « " + info.remoteDevice() + " »"), 1, 3);

        Label diff = new Label(info.differingFiles() + " fichier(s) diffèrent entre les deux versions.");
        Label safety = new Label("""
                • « Utiliser la version locale » : la version de ce PC est envoyée ; l'ancienne version \
                Google Drive part dans la corbeille de Google Drive (récupérable pendant 30 jours).
                • « Utiliser la version Google Drive » : une copie de sauvegarde complète du monde local \
                est d'abord créée, puis le monde est remplacé.
                Vérifiez que Minecraft est fermé sur les deux PC avant de choisir.""");
        safety.setWrapText(true);
        safety.setMinHeight(Region.USE_PREF_SIZE);
        safety.getStyleClass().add("muted");

        VBox content = new VBox(12, grid, diff, safety);
        content.setPadding(new Insets(10));
        content.setPrefWidth(600);
        getDialogPane().setContent(content);

        ButtonType local = new ButtonType("Utiliser la version locale", ButtonBar.ButtonData.LEFT);
        ButtonType remote = new ButtonType("Utiliser la version Google Drive", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(local, remote, cancel);

        setResultConverter(bt -> {
            if (bt == local) return ConflictManager.Resolution.KEEP_LOCAL;
            if (bt == remote) return ConflictManager.Resolution.KEEP_REMOTE;
            return null;
        });
    }
}
