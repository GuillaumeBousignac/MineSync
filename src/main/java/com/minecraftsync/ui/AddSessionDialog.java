package com.minecraftsync.ui;

import com.minecraftsync.minecraft.WorldDetector;
import com.minecraftsync.minecraft.WorldInfo;
import com.minecraftsync.sync.SyncService;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
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

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Ajout d'une session : choix du monde, nom, version, type, activation. */
public class AddSessionDialog extends Dialog<AddSessionDialog.Request> {

    public static final String SETTING_CUSTOM_DIRS = "custom_saves_dirs";

    public record Request(WorldInfo world, String name, String version, Boolean modded, boolean enabled) {
    }

    private final SyncService service;
    private final WorldDetector detector;
    private final ObservableList<WorldInfo> worlds = FXCollections.observableArrayList();
    private final ListView<WorldInfo> list = new ListView<>(worlds);
    private final TextField nameField = new TextField();
    private final TextField versionField = new TextField();
    private final ComboBox<String> typeBox = new ComboBox<>(FXCollections.observableArrayList("Vanilla", "Moddé", "Inconnu"));
    private final CheckBox enabled = new CheckBox("Activer la synchronisation automatique");

    public AddSessionDialog(Stage owner, SyncService service, WorldDetector detector) {
        this.service = service;
        this.detector = detector;
        initOwner(owner);
        setTitle("Ajouter une session");
        setHeaderText("Choisissez le monde à synchroniser");
        setResizable(true);

        list.setPrefHeight(240);
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(WorldInfo w, boolean empty) {
                super.updateItem(w, empty);
                if (empty || w == null) {
                    setText(null);
                    setDisable(false);
                    return;
                }
                boolean linked = service.findByPath(w.dir()).isPresent();
                setText(w.folderName() + (w.levelName().equals(w.folderName()) ? "" : " « " + w.levelName() + " »")
                        + "\n" + w.source() + " · " + (w.version() == null ? "version inconnue" : w.version())
                        + (linked ? " · déjà synchronisé" : ""));
                setDisable(linked);
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, w) -> fill(w));

        Button browse = new Button("Parcourir…");
        browse.setMinWidth(Region.USE_PREF_SIZE);
        browse.setOnAction(e -> browse(owner));
        Label hint = new Label("Mondes trouvés dans les lanceurs officiel, Prism, CurseForge et Modrinth. "
                + "Autre emplacement : « Parcourir… ».");
        hint.setWrapText(true);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        hint.getStyleClass().add("muted");
        HBox.setHgrow(hint, Priority.ALWAYS);

        typeBox.getSelectionModel().select("Inconnu");
        enabled.setSelected(true);
        versionField.setPromptText("ex. 1.21.10");

        GridPane form = Ui.form();
        form.addRow(0, new Label("Nom de la session"), nameField);
        form.addRow(1, new Label("Version Minecraft"), versionField);
        form.addRow(2, new Label("Type"), typeBox);
        form.add(enabled, 1, 3);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        VBox content = new VBox(10, list, new HBox(10, hint, browse), form);
        content.setPadding(new Insets(10));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);

        ButtonType create = new ButtonType("Créer la session", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(create, Ui.CANCEL);
        Node createButton = getDialogPane().lookupButton(create);
        createButton.addEventFilter(ActionEvent.ACTION, e -> {
            String error = validate();
            if (error != null) {
                e.consume();
                Alert a = new Alert(Alert.AlertType.WARNING, error, Ui.OK);
                a.initOwner(getDialogPane().getScene().getWindow());
                a.showAndWait();
            }
        });

        setResultConverter(bt -> bt == create
                ? new Request(list.getSelectionModel().getSelectedItem(), nameField.getText().trim(),
                versionField.getText().trim(), modded(), enabled.isSelected())
                : null);

        reload();
    }

    private void reload() {
        worlds.setAll(detector.findWorlds(detector.findSavesLocations(customDirs())));
        if (worlds.isEmpty()) {
            list.setPlaceholder(new Label("Aucun monde trouvé automatiquement : utilisez « Parcourir… »."));
        }
    }

    private List<Path> customDirs() {
        String raw = service.database().getSetting(SETTING_CUSTOM_DIRS, "");
        List<Path> dirs = new ArrayList<>();
        Arrays.stream(raw.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).forEach(s -> dirs.add(Paths.get(s)));
        return dirs;
    }

    private void browse(Stage owner) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choisir un monde ou un dossier « saves »");
        File f = dc.showDialog(getDialogPane().getScene().getWindow());
        if (f == null) return;
        Path p = f.toPath();
        if (WorldInfo.isWorld(p)) {
            WorldInfo w = WorldInfo.read(p, "Choisi à la main");
            if (worlds.stream().noneMatch(x -> x.dir().equals(w.dir()))) worlds.add(0, w);
            list.getSelectionModel().select(w);
            list.scrollTo(w);
            return;
        }
        // Dossier contenant des mondes : on le mémorise comme emplacement personnalisé
        List<Path> dirs = customDirs();
        if (!dirs.contains(p)) {
            dirs.add(p);
            StringBuilder sb = new StringBuilder();
            dirs.forEach(d -> sb.append(d).append('\n'));
            service.database().setSetting(SETTING_CUSTOM_DIRS, sb.toString());
        }
        int before = worlds.size();
        reload();
        if (worlds.size() == before) {
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "Aucun monde trouvé dans ce dossier. Un monde est un dossier contenant un fichier level.dat.",
                    Ui.OK);
            a.initOwner(owner);
            a.showAndWait();
        }
    }

    private void fill(WorldInfo w) {
        if (w == null) return;
        nameField.setText(w.levelName());
        versionField.setText(w.version() == null ? "" : w.version());
        typeBox.getSelectionModel().select(w.modded() == null ? "Inconnu" : (w.modded() ? "Moddé" : "Vanilla"));
    }

    private Boolean modded() {
        String t = typeBox.getSelectionModel().getSelectedItem();
        return "Moddé".equals(t) ? Boolean.TRUE : "Vanilla".equals(t) ? Boolean.FALSE : null;
    }

    private String validate() {
        WorldInfo w = list.getSelectionModel().getSelectedItem();
        if (w == null) return "Sélectionnez un monde.";
        if (service.findByPath(w.dir()).isPresent()) return "Ce monde est déjà synchronisé.";
        if (nameField.getText().isBlank()) return "Donnez un nom à la session.";
        return null;
    }
}
