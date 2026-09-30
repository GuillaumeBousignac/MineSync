package com.minecraftsync.google;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.minecraftsync.sync.CloudStorage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Stockage distant sur Google Drive (API v3). */
public class GoogleDriveService implements CloudStorage {

    public static final String DEFAULT_ROOT_NAME = "Minecraft Sync";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String LIST_FIELDS = "nextPageToken, files(id, name, mimeType, size, modifiedTime)";
    private static final long DIRECT_UPLOAD_LIMIT = 5L * 1024 * 1024;

    private final Drive drive;

    public GoogleDriveService(Credential credential) throws GeneralSecurityException, IOException {
        HttpRequestInitializer init = request -> {
            credential.initialize(request);
            request.setConnectTimeout(60_000);
            request.setReadTimeout(5 * 60_000);
        };
        this.drive = new Drive.Builder(GoogleNetHttpTransport.newTrustedTransport(), GoogleAuth.JSON, init)
                .setApplicationName("Minecraft Sync")
                .build();
    }

    /** Adresse du compte connecté (vérifie au passage que l'accès fonctionne). */
    public String accountEmail() throws IOException {
        var about = drive.about().get().setFields("user(emailAddress, displayName)").execute();
        return about.getUser() == null ? "?" : about.getUser().getEmailAddress();
    }

    /** Dossier racine « Minecraft Sync » à la racine du Drive (créé s'il n'existe pas). */
    public String findOrCreateRootFolder(String name) throws IOException {
        String q = "name = '" + escape(name) + "' and mimeType = '" + FOLDER_MIME
                + "' and 'root' in parents and trashed = false";
        FileList list = drive.files().list().setQ(q).setSpaces("drive").setFields(LIST_FIELDS).execute();
        if (list.getFiles() != null && !list.getFiles().isEmpty()) {
            return list.getFiles().get(0).getId();
        }
        return createFolder("root", name);
    }

    /** Vérifie qu'un dossier existe encore (et n'est pas dans la corbeille). */
    public boolean folderExists(String id) {
        try {
            File f = drive.files().get(id).setFields("id, trashed").execute();
            return f != null && !Boolean.TRUE.equals(f.getTrashed());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String createFolder(String parentId, String name) throws IOException {
        File meta = new File().setName(name).setMimeType(FOLDER_MIME).setParents(List.of(parentId));
        return drive.files().create(meta).setFields("id").execute().getId();
    }

    @Override
    public Optional<RemoteItem> findChild(String parentId, String name) throws IOException {
        return list("'" + escape(parentId) + "' in parents and name = '" + escape(name) + "' and trashed = false")
                .stream().findFirst();
    }

    @Override
    public List<RemoteItem> listChildren(String parentId) throws IOException {
        return list("'" + escape(parentId) + "' in parents and trashed = false");
    }

    private List<RemoteItem> list(String q) throws IOException {
        List<RemoteItem> items = new ArrayList<>();
        String token = null;
        do {
            FileList page = drive.files().list()
                    .setQ(q)
                    .setSpaces("drive")
                    .setPageSize(1000)
                    .setFields(LIST_FIELDS)
                    .setPageToken(token)
                    .execute();
            if (page.getFiles() != null) {
                for (File f : page.getFiles()) {
                    items.add(new RemoteItem(f.getId(), f.getName(), FOLDER_MIME.equals(f.getMimeType()),
                            f.getSize() == null ? 0 : f.getSize(),
                            f.getModifiedTime() == null ? null : Instant.ofEpochMilli(f.getModifiedTime().getValue())));
                }
            }
            token = page.getNextPageToken();
        } while (token != null);
        return items;
    }

    @Override
    public String uploadFile(String parentId, String name, Path source) throws IOException {
        File meta = new File().setName(name).setParents(List.of(parentId));
        FileContent content = new FileContent("application/octet-stream", source.toFile());
        Drive.Files.Create create = drive.files().create(meta, content).setFields("id");
        create.getMediaHttpUploader().setDirectUploadEnabled(Files.size(source) <= DIRECT_UPLOAD_LIMIT);
        return create.execute().getId();
    }

    @Override
    public void downloadFile(String fileId, Path target) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            drive.files().get(fileId).executeMediaAndDownloadTo(out);
        }
    }

    @Override
    public String writeText(String parentId, String name, String content, String existingId) throws IOException {
        ByteArrayContent body = new ByteArrayContent("application/json", content.getBytes(StandardCharsets.UTF_8));
        if (existingId != null) {
            return drive.files().update(existingId, new File(), body).setFields("id").execute().getId();
        }
        File meta = new File().setName(name).setParents(List.of(parentId));
        return drive.files().create(meta, body).setFields("id").execute().getId();
    }

    @Override
    public String readText(String fileId) throws IOException {
        try (InputStream in = drive.files().get(fileId).executeMediaAsInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void trash(String id) throws IOException {
        drive.files().update(id, new File().setTrashed(true)).setFields("id").execute();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
