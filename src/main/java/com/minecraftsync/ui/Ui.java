package com.minecraftsync.ui;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Éléments d'interface communs (boutons en français quelle que soit la langue du système). */
final class Ui {

    static final ButtonType OK = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
    static final ButtonType CANCEL = new ButtonType("Annuler", ButtonBar.ButtonData.CANCEL_CLOSE);
    static final ButtonType YES = new ButtonType("Oui", ButtonBar.ButtonData.YES);
    static final ButtonType NO = new ButtonType("Non", ButtonBar.ButtonData.NO);

    private Ui() {
    }

    /** Formulaire à deux colonnes : libellés jamais tronqués, champs extensibles. */
    static GridPane form() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        g.getColumnConstraints().addAll(labels, fields);
        return g;
    }
}
