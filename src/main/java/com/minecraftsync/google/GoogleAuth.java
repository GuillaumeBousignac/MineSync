package com.minecraftsync.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.minecraftsync.sync.ConflictManager;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Connexion OAuth 2.0 « application de bureau ».
 * <p>
 * Portée demandée : {@code drive.file} uniquement. L'application ne voit que les fichiers
 * qu'elle a elle-même créés : le reste du Google Drive lui est inaccessible.
 * Les jetons sont stockés dans le dossier de données de l'application.
 */
public class GoogleAuth {

    static final JsonFactory JSON = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = List.of(DriveScopes.DRIVE_FILE);
    private static final String USER = "user";

    private final Path credentialsFile;
    private final Path tokensDir;
    private volatile LocalServerReceiver pendingReceiver;
    private volatile boolean cancelled;

    /** Connexion abandonnée par l'utilisateur. */
    public static class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Connexion annulée.");
        }
    }

    public GoogleAuth(Path credentialsFile, Path tokensDir) {
        this.credentialsFile = credentialsFile;
        this.tokensDir = tokensDir;
    }

    public boolean hasCredentialsFile() {
        return Files.isRegularFile(credentialsFile);
    }

    /** Copie le fichier client OAuth téléchargé depuis Google Cloud, après vérification. */
    public void importCredentials(Path source) throws IOException {
        String json = Files.readString(source, StandardCharsets.UTF_8);
        if (!json.contains("\"installed\"")) {
            throw new IOException("Ce fichier n'est pas un identifiant OAuth de type « Application de bureau ». "
                    + "Voir le README, section Google Cloud.");
        }
        Files.createDirectories(credentialsFile.getParent());
        Files.copy(source, credentialsFile, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Jeton déjà enregistré ? (connexion sans navigateur possible) */
    public boolean hasStoredToken() {
        return Files.isRegularFile(tokensDir.resolve("StoredCredential"));
    }

    /**
     * Renvoie une autorisation valide. Si aucun jeton n'est enregistré et que {@code openBrowser}
     * n'est pas null, ouvre la page de consentement Google et attend la réponse (bloquant).
     */
    public Credential authorize(Consumer<String> openBrowser) throws IOException, GeneralSecurityException {
        if (!hasCredentialsFile()) {
            throw new IOException("Fichier credentials.json absent. Voir le README, section Google Cloud.");
        }
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        GoogleClientSecrets secrets;
        try (Reader r = Files.newBufferedReader(credentialsFile, StandardCharsets.UTF_8)) {
            secrets = GoogleClientSecrets.load(JSON, r);
        }
        GoogleAuthorizationCodeFlow.Builder builder = new GoogleAuthorizationCodeFlow.Builder(transport, JSON, secrets, SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokensDir.toFile()))
                .setAccessType("offline");
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow(builder) {
            @Override
            public GoogleAuthorizationCodeRequestUrl newAuthorizationUrl() {
                GoogleAuthorizationCodeRequestUrl url = super.newAuthorizationUrl();
                url.set("prompt", "consent"); // garantit l'obtention d'un jeton de renouvellement
                return url;
            }
        };

        Credential stored = flow.loadCredential(USER);
        if (stored != null && (stored.getRefreshToken() != null
                || (stored.getExpiresInSeconds() != null && stored.getExpiresInSeconds() > 60))) {
            return stored;
        }
        if (openBrowser == null) {
            return null;
        }
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setHost("localhost").build();
        pendingReceiver = receiver;
        cancelled = false;
        try {
            Credential c = new AuthorizationCodeInstalledApp(flow, receiver, openBrowser::accept).authorize(USER);
            if (cancelled) throw new CancelledException();
            return c;
        } catch (IOException | RuntimeException e) {
            if (cancelled) throw new CancelledException();
            throw e;
        } finally {
            pendingReceiver = null;
        }
    }

    /** Abandonne une connexion en attente dans le navigateur. */
    public void cancelPending() {
        LocalServerReceiver r = pendingReceiver;
        if (r != null) {
            cancelled = true;
            try {
                r.stop();
            } catch (IOException ignored) {
            }
        }
    }

    /** Oublie le jeton local (les données sur Google Drive ne sont pas touchées). */
    public void signOut() throws IOException {
        ConflictManager.deleteRecursively(tokensDir);
    }
}
