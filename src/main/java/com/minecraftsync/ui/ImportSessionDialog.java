package com.minecraftsync.ui;

import com.minecraftsync.minecraft.WorldDetector;
import com.minecraftsync.model.RemoteSessionInfo;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.util.TimeFormat;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Ajout sur ce PC d'une session déjà présente sur Google Drive. */
public class ImportSessionDialog extends Dialog<ImportSessionDialog.Request> {

    public record Request(RemoteSessionInfo info, Path savesDir, String folderName) {
    }

    private final ListView<RemoteSessionInfo> list;
    private final ComboBox<WorldDetector.SavesLocation> destination = new ComboBox<>();
    private final TextField folderName = new TextField();
    private final Label warning = new Label();

    public ImportSessionDialog(Stage owner, List<RemoteSessionInfo> sessions, WorldDetector detector, SyncService service) {
        initOwner(owner);
        setTitle("Sessions disponibles sur Google Drive");
        setHeaderText("Choisissez le monde à récupérer sur ce PC");
        setResizable(true);

        list = new ListView<>(FXCollections.observableArrayList(sessions));
        list.setPrefHeight(200);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RemoteSessionInfo r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) {
                    setText(null);
                    return;
                }
                var m = r.manifest();
                String version = m.minecraftVersion() == null ? "version inconnue" : "Minecraft " + m.minecraftVersion();
                String type = m.modded() == null ? "" : (m.modded() ? " · Moddé" : " · Vanilla");
                setText(r.displayName() + "\n" + version + type + " · " + TimeFormat.size(m.totalSize())
                        + " · envoyé par " + m.deviceName() + ", " + TimeFormat.relative(m.updatedAt()));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            if (r != null) folderName.setText(r.manifest().worldFolderName() != null
                    ? r.manifest().worldFolderName() : r.displayName());
        });

        String raw = service.database().getSetting(AddSessionDialog.SETTING_CUSTOM_DIRS, "");
        List<Path> custom = new ArrayList<>();
        Arrays.stream(raw.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).forEach(s -> custom.add(Paths.get(s)));
        destination.setItems(FXCollections.observableArrayList(detector.findSavesLocations(custom)));
        destination.setConverter(new StringConverter<>() {
            @Override
            public String toString(WorldDetector.SavesLocation l) {
                return l == null ? "" : l.label() + " — " + l.dir();
            }

            @Override
            public WorldDetector.SavesLocation fromString(String s) {
                return null;
            }
        });
        destination.setMaxWidth(Double.MAX_VALUE);
        if (!destination.getItems().isEmpty()) destination.getSelectionModel().select(0);

        Button browse = new Button("Parcourir…");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Dossier « saves » de destination");
            File f = dc.showDialog(getDialogPane().getScene().getWindow());
            if (f != null) {
                WorldDetector.SavesLocation loc = new WorldDetector.SavesLocation(f.toPath(), "Choisi à la main");
                destination.getItems().add(0, loc);
                destination.getSelectionModel().select(loc);
            }
        });

        warning.setWrapText(true);
        warning.setMinHeight(Region.USE_PREF_SIZE);
        warning.getStyleClass().add("msg-error");
        folderName.textProperty().addListener((o, a, b) -> updateWarning());
        destination.valueProperty().addListener((o, a, b) -> updateWarning());

        Label info = new Label("Choisissez le dossier « saves » du lanceur et de l'instance où vous jouerez ce monde "
                + "(même version de Minecraft et mêmes mods que sur l'autre PC).");
        info.setWrapText(true);
        info.setMinHeight(Region.USE_PREF_SIZE);
        info.getStyleClass().add("muted");

        GridPane form = Ui.form();
        HBox dest = new HBox(8, destination, browse);
        HBox.setHgrow(destination, Priority.ALWAYS);
        form.addRow(0, new Label("Dossier saves"), dest);
        form.addRow(1, new Label("Nom du dossier du monde"), folderName);
        GridPane.setHgrow(dest, Priority.ALWAYS);

        VBox content = new VBox(10, list, info, form, warning);
        content.setPadding(new Insets(10));
        content.setPrefWidth(620);
        getDialogPane().setContent(content);

        ButtonType add = new ButtonType("Ajouter à ce PC", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(add, Ui.CANCEL);
        getDialogPane().lookupButton(add).addEventFilter(ActionEvent.ACTION, e -> {
            String error = validate();
            if (error != null) {
                e.consume();
                Alert a = new Alert(Alert.AlertType.WARNING, error, Ui.OK);
                a.initOwner(getDialogPane().getScene().getWindow());
                a.showAndWait();
            }
        });
        setResultConverter(bt -> bt == add
                ? new Request(list.getSelectionModel().getSelectedItem(), destination.getValue().dir(),
                folderName.getText().trim())
                : null);

        if (!sessions.isEmpty()) list.getSelectionModel().select(0);
    }

    private void updateWarning() {
        WorldDetector.SavesLocation loc = destination.getValue();
        String name = folderName.getText().trim();
        if (loc == null || name.isEmpty()) {
            warning.setText("");
            return;
        }
        Path target = loc.dir().resolve(name);
        boolean nonEmpty = false;
        if (Files.isDirectory(target)) {
            try (Stream<Path> s = Files.list(target)) {
                nonEmpty = s.findAny().isPresent();
            } catch (Exception ignored) {
                nonEmpty = true;
            }
        }
        warning.setText(nonEmpty
                ? "Un dossier « " + name + " » existe déjà ici. Rien ne sera écrasé automatiquement : "
                + "si son contenu diffère, un conflit vous sera proposé (avec sauvegarde préalable)."
                : "");
    }

    private String validate() {
        if (list.getSelectionModel().getSelectedItem() == null) return "Sélectionnez une session.";
        if (destination.getValue() == null) return "Choisissez un dossier « saves » de destination.";
        String name = folderName.getText().trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".")) {
            return "Nom de dossier invalide.";
        }
        return null;
    }
}
