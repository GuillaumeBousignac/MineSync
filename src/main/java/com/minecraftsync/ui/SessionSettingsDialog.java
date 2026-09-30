package com.minecraftsync.ui;

import com.minecraftsync.minecraft.WorldInfo;
import com.minecraftsync.model.Session;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.util.Desktops;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Nom, version et type d'une session ; accès au dossier, aux sauvegardes, restauration. */
public class SessionSettingsDialog extends Dialog<ButtonType> {

    public SessionSettingsDialog(Stage owner, Session s, SyncService service, Consumer<Session> restoreFromRemote) {
        initOwner(owner);
        setTitle("Paramètres de la session");
        setHeaderText(s.name());

        TextField name = new TextField(s.name());
        TextField version = new TextField(s.minecraftVersion() == null ? "" : s.minecraftVersion());
        version.setPromptText("ex. 1.21.10");
        ComboBox<String> type = new ComboBox<>(FXCollections.observableArrayList("Vanilla", "Moddé", "Inconnu"));
        type.getSelectionModel().select(s.modded() == null ? "Inconnu" : (s.modded() ? "Moddé" : "Vanilla"));

        Button detect = new Button("Relire level.dat");
        detect.setOnAction(e -> {
            WorldInfo info = WorldInfo.read(s.localPath(), "");
            if (info.version() != null) version.setText(info.version());
            if (info.modded() != null) type.getSelectionModel().select(info.modded() ? "Moddé" : "Vanilla");
        });

        Label path = new Label(s.localPathString());
        path.setWrapText(true);
        path.getStyleClass().add("muted");

        Button openWorld = new Button("Ouvrir le dossier du monde");
        openWorld.setOnAction(e -> open(s.localPath()));
        Button openBackups = new Button("Ouvrir les sauvegardes");
        openBackups.setOnAction(e -> {
            Path dir = service.conflicts().backupsDirFor(s);
            if (Files.isDirectory(dir)) {
                open(dir);
            } else {
                info("Aucune sauvegarde pour cette session pour l'instant.");
            }
        });
        Button restore = new Button("Restaurer depuis Google Drive…");
        restore.getStyleClass().add("danger");
        restore.setOnAction(e -> {
            close();
            restoreFromRemote.accept(s);
        });

        GridPane form = Ui.form();
        HBox versionRow = new HBox(8, version, detect);
        HBox.setHgrow(version, Priority.ALWAYS);
        form.addRow(0, new Label("Nom"), name);
        form.addRow(1, new Label("Version Minecraft"), versionRow);
        form.addRow(2, new Label("Type"), type);
        form.addRow(3, new Label("Emplacement"), path);
        GridPane.setHgrow(name, Priority.ALWAYS);

        Label note = new Label("Retirer la session ne supprime jamais le monde, ni sur ce PC, ni sur Google Drive.");
        note.getStyleClass().add("muted");
        note.setWrapText(true);
        note.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(12, form, new HBox(8, openWorld, openBackups), restore, note);
        content.setPadding(new Insets(10));
        content.setPrefWidth(520);
        getDialogPane().setContent(content);

        ButtonType save = new ButtonType("Enregistrer", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, Ui.CANCEL);
        setResultConverter(bt -> {
            if (bt == save) {
                if (!name.getText().isBlank()) s.setName(name.getText().trim());
                s.setMinecraftVersion(version.getText().isBlank() ? null : version.getText().trim());
                String t = type.getSelectionModel().getSelectedItem();
                s.setModded("Moddé".equals(t) ? Boolean.TRUE : "Vanilla".equals(t) ? Boolean.FALSE : null);
                service.saveSession(s);
            }
            return bt;
        });
    }

    private void open(Path dir) {
        try {
            Desktops.openFolder(dir);
        } catch (Exception ex) {
            info("Ouverture impossible : " + ex.getMessage());
        }
    }

    private void info(String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, Ui.OK);
        a.initOwner(getDialogPane().getScene().getWindow());
        a.showAndWait();
    }
}
