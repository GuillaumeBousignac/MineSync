package com.minecraftsync;

import com.minecraftsync.ui.MinecraftSyncApp;
import javafx.application.Application;

/**
 * Point d'entrée. Classe distincte de l'Application JavaFX pour que le JAR autonome
 * (« -all.jar ») puisse démarrer sans configuration de modules.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(MinecraftSyncApp.class, args);
    }
}
