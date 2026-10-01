package com.minecraftsync.ui;

import com.minecraftsync.database.Database;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.Window;

import java.lang.reflect.Method;
import java.util.ArrayList;

final class Theme {

    enum Mode {
        SYSTEM("Système"), LIGHT("Clair"), DARK("Sombre");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    static final String SETTING = "ui_theme";
    private static final String DARK_CLASS = "theme-dark";

    private final Database db;
    private final String css;
    private Mode mode;
    private Runnable onChange = () -> { };

    Theme(Database db) {
        this.db = db;
        this.css = Theme.class.getResource("styles.css").toExternalForm();
        Mode m;
        try {
            m = Mode.valueOf(db.getSetting(SETTING, Mode.SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            m = Mode.SYSTEM;
        }
        this.mode = m;
    }

    /** Surveille l'ouverture des fenêtres et la préférence du système. */
    void install() {
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                for (Window w : c.getAddedSubList()) watch(w);
            }
        });
        new ArrayList<>(Window.getWindows()).forEach(this::watch);
        listenToSystem();
    }

    Mode mode() {
        return mode;
    }

    void setMode(Mode m) {
        mode = m;
        db.setSetting(SETTING, m.name());
        refresh();
    }

    void setOnChange(Runnable r) {
        onChange = r;
    }

    boolean isDark() {
        return mode == Mode.DARK || (mode == Mode.SYSTEM && systemIsDark());
    }

    /** Applique la feuille de style et le thème courant à une scène. */
    void apply(Scene scene) {
        if (scene == null) return;
        if (!scene.getStylesheets().contains(css)) scene.getStylesheets().add(css);
        Parent root = scene.getRoot();
        if (root == null) return;
        if (isDark()) {
            if (!root.getStyleClass().contains(DARK_CLASS)) root.getStyleClass().add(DARK_CLASS);
        } else {
            root.getStyleClass().remove(DARK_CLASS);
        }
    }

    private void refresh() {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (!(w instanceof PopupWindow)) apply(w.getScene());
        }
        onChange.run();
    }

    private void watch(Window w) {
        // Les menus déroulants héritent du style de leur propriétaire : inutile de les traiter.
        if (w instanceof PopupWindow) return;
        apply(w.getScene());
        w.sceneProperty().addListener((o, a, s) -> apply(s));
    }

    // ---- Préférence du système (API Platform.Preferences, JavaFX 22+), lue sans dépendance stricte ----

    private static Object preferences() {
        try {
            return Platform.class.getMethod("getPreferences").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Method preferencesMethod(String name) throws Exception {
        return Class.forName("javafx.application.Platform$Preferences").getMethod(name);
    }

    private static boolean systemIsDark() {
        Object prefs = preferences();
        if (prefs == null) return false;
        try {
            return "DARK".equals(String.valueOf(preferencesMethod("getColorScheme").invoke(prefs)));
        } catch (Exception e) {
            return false;
        }
    }

    private void listenToSystem() {
        Object prefs = preferences();
        if (prefs == null) return;
        try {
            Object prop = preferencesMethod("colorSchemeProperty").invoke(prefs);
            if (prop instanceof ObservableValue<?> ov) {
                ov.addListener((o, a, b) -> {
                    if (mode == Mode.SYSTEM) refresh();
                });
            }
        } catch (Exception ignored) {
            // JavaFX trop ancien : le mode « Système » reste en clair
        }
    }
}