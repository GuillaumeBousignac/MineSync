package com.minecraftsync.google;

import com.google.api.client.auth.oauth2.Credential;
import com.minecraftsync.database.Database;
import com.minecraftsync.sync.SyncService;

import java.util.function.Consumer;

/**
 * Relie l'authentification Google, le service Drive et le service de synchronisation.
 * Choisit (ou crée) le dossier racine dédié à Minecraft Sync.
 */
public class DriveConnection {

    public static final String SETTING_ROOT_ID = "root_folder_id";
    public static final String SETTING_ROOT_NAME = "root_folder_name";

    private final GoogleAuth auth;
    private final SyncService service;
    private final Database db;
    private volatile String email;

    public DriveConnection(GoogleAuth auth, SyncService service, Database db) {
        this.auth = auth;
        this.service = service;
        this.db = db;
    }

    public GoogleAuth auth() {
        return auth;
    }

    public String email() {
        return email;
    }

    public boolean isConnected() {
        return service.isConnected();
    }

    public boolean canConnectSilently() {
        return auth.hasCredentialsFile() && auth.hasStoredToken();
    }

    public boolean rootNameChosen() {
        return db.getSetting(SETTING_ROOT_NAME, null) != null;
    }

    public String rootName() {
        return db.getSetting(SETTING_ROOT_NAME, GoogleDriveService.DEFAULT_ROOT_NAME);
    }

    /** Change de dossier racine : la connexion suivante recherchera (ou créera) ce dossier. */
    public void setRootName(String name) {
        db.setSetting(SETTING_ROOT_NAME, name.trim());
        db.setSetting(SETTING_ROOT_ID, "");
    }

    /**
     * Connexion (bloquante). {@code openBrowser} = null : uniquement avec un jeton déjà enregistré.
     *
     * @return l'adresse du compte, ou null si aucune connexion silencieuse n'était possible
     */
    public String connect(Consumer<String> openBrowser) throws Exception {
        Credential credential = auth.authorize(openBrowser);
        if (credential == null) return null;
        GoogleDriveService drive = new GoogleDriveService(credential);
        String account = drive.accountEmail();
        String rootId = db.getSetting(SETTING_ROOT_ID, "");
        if (rootId.isBlank() || !drive.folderExists(rootId)) {
            rootId = drive.findOrCreateRootFolder(rootName());
            db.setSetting(SETTING_ROOT_ID, rootId);
        }
        service.connect(drive, rootId);
        this.email = account;
        return account;
    }

    public void disconnect() throws Exception {
        service.disconnect();
        email = null;
        auth.signOut();
    }
}
