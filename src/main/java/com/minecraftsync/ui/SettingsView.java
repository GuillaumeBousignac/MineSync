package com.minecraftsync.ui;

import com.minecraftsync.database.Database;
import com.minecraftsync.google.DriveConnection;
import com.minecraftsync.minecraft.MinecraftManager;
import com.minecraftsync.minecraft.WorldDetector;
import com.minecraftsync.sync.SyncService;
import com.minecraftsync.util.AppPaths;
import com.minecraftsync.util.Desktops;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.Arrays;

/** Réglages de l'application. */
public class SettingsView extends Dialog<ButtonType> {

    public SettingsView(Stage owner, SyncService service, DriveConnection drive, WorldDetector detector) {
        initOwner(owner);
        setTitle("Réglages");
        setHeaderText("Réglages de Minecraft Sync");
        Database db = service.database();

        TextField deviceName = new TextField(db.device().name());
        TextField rootName = new TextField(drive.rootName());
        Spinner<Integer> quiet = new Spinner<>(0, 600, db.getIntSetting(MinecraftManager.SETTING_QUIET_DELAY, 10));
        Spinner<Integer> interval = new Spinner<>(1, 120, db.getIntSetting(MinecraftManager.SETTING_CHECK_INTERVAL, 2));
        Spinner<Integer> backups = new Spinner<>(1, 50, db.getIntSetting(MinecraftSyncApp.SETTING_BACKUPS, 3));
        quiet.setEditable(true);
        interval.setEditable(true);
        backups.setEditable(true);

        ObservableList<String> dirs = FXCollections.observableArrayList();
        Arrays.stream(db.getSetting(AddSessionDialog.SETTING_CUSTOM_DIRS, "").split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).forEach(dirs::add);
        ListView<String> dirList = new ListView<>(dirs);
        dirList.setPrefHeight(90);
        dirList.setPlaceholder(new Label("Aucun"));
        Button addDir = new Button("Ajouter…");
        addDir.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Dossier « saves », dossier .minecraft ou dossier d'instances");
            File f = dc.showDialog(getDialogPane().getScene().getWindow());
            if (f != null && !dirs.contains(f.getAbsolutePath())) dirs.add(f.getAbsolutePath());
        });
        Button removeDir = new Button("Retirer");
        removeDir.setOnAction(e -> dirs.remove(dirList.getSelectionModel().getSelectedItem()));

        Button openData = new Button("Ouvrir le dossier de données");
        openData.setOnAction(e -> {
            try {
                Desktops.openFolder(AppPaths.dataDir());
            } catch (Exception ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });
        Button importCreds = new Button("Remplacer credentials.json…");
        importCreds.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Fichier JSON", "*.json"));
            File f = fc.showOpenDialog(getDialogPane().getScene().getWindow());
            if (f == null) return;
            try {
                drive.auth().importCredentials(f.toPath());
                alert(Alert.AlertType.INFORMATION, "Fichier importé. Déconnectez puis reconnectez Google Drive.");
            } catch (Exception ex) {
                alert(Alert.AlertType.ERROR, ex.getMessage());
            }
        });

        GridPane form = Ui.form();
        int r = 0;
        form.addRow(r++, new Label("Nom de ce PC"), deviceName);
        form.addRow(r++, new Label("Dossier racine Google Drive"), rootName);
        form.addRow(r++, new Label("Délai après fermeture du monde (s)"), quiet);
        form.addRow(r++, new Label("Vérification des nouveautés (min)"), interval);
        form.addRow(r++, new Label("Sauvegardes conservées par session"), backups);
        GridPane.setHgrow(deviceName, Priority.ALWAYS);

        Label dirsTitle = new Label("Emplacements de mondes supplémentaires (MultiMC portable, ATLauncher…)");
        dirsTitle.setWrapText(true);
        dirsTitle.setMinHeight(Region.USE_PREF_SIZE);
        HBox dirButtons = new HBox(8, addDir, removeDir);

        Label dataInfo = new Label("Données de l'application : " + AppPaths.dataDir());
        dataInfo.getStyleClass().add("muted");
        dataInfo.setWrapText(true);
        dataInfo.setMinHeight(Region.USE_PREF_SIZE);

        VBox content = new VBox(10, form, dirsTitle, dirList, dirButtons, new HBox(8, openData, importCreds), dataInfo);
        content.setPadding(new Insets(10));
        content.setPrefWidth(580);
        getDialogPane().setContent(content);

        ButtonType save = new ButtonType("Enregistrer", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, Ui.CANCEL);
        setResultConverter(bt -> {
            if (bt != save) return bt;
            String dn = deviceName.getText().trim();
            if (!dn.isEmpty() && !dn.equals(db.device().name())) {
                db.setSetting("device_name", dn);
                service.refreshDevice();
            }
            String rn = rootName.getText().trim();
            if (!rn.isEmpty() && !rn.equals(drive.rootName())) {
                drive.setRootName(rn);
                alert(Alert.AlertType.INFORMATION, "Le nouveau dossier racine sera utilisé à la prochaine connexion "
                        + "(déconnectez puis reconnectez Google Drive). Les sessions existantes restent liées à leur dossier.");
            }
            db.setSetting(MinecraftManager.SETTING_QUIET_DELAY, String.valueOf(value(quiet)));
            db.setSetting(MinecraftManager.SETTING_CHECK_INTERVAL, String.valueOf(value(interval)));
            db.setSetting(MinecraftSyncApp.SETTING_BACKUPS, String.valueOf(value(backups)));
            service.conflicts().setBackupsToKeep(value(backups));
            db.setSetting(AddSessionDialog.SETTING_CUSTOM_DIRS, String.join("\n", dirs));
            return bt;
        });
    }

    /** Valeur saisie au clavier, même sans validation par Entrée. */
    private static int value(Spinner<Integer> spinner) {
        try {
            int v = Integer.parseInt(spinner.getEditor().getText().trim());
            var f = (javafx.scene.control.SpinnerValueFactory.IntegerSpinnerValueFactory) spinner.getValueFactory();
            return Math.max(f.getMin(), Math.min(f.getMax(), v));
        } catch (RuntimeException e) {
            return spinner.getValue();
        }
    }

    private void alert(Alert.AlertType type, String text) {
        Alert a = new Alert(type, text, Ui.OK);
        a.initOwner(getDialogPane().getScene().getWindow());
        a.showAndWait();
    }
}
