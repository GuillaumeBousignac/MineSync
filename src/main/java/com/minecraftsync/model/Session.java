package com.minecraftsync.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

/**
 * Une session = un monde Minecraft précis, lié à un dossier Google Drive.
 */
public class Session {

    private final String id;
    private String name;
    private String localPath;
    private String remoteFolderId;
    private String minecraftVersion;
    /** true = moddé, false = vanilla, null = inconnu. */
    private Boolean modded;
    private boolean syncEnabled = true;
    private Instant lastSync;
    private String lastSyncDevice;
    /** Révision du manifeste distant lors de la dernière synchronisation réussie. */
    private long remoteRevision;
    private SyncState state = SyncState.IDLE;
    private String statusMessage;

    public Session(String id) {
        this.id = id;
    }

    public static Session create(String name, Path localPath) {
        Session s = new Session(UUID.randomUUID().toString());
        s.name = name;
        s.localPath = localPath.toAbsolutePath().normalize().toString();
        return s;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Path localPath() {
        return Paths.get(localPath);
    }

    public String localPathString() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public String remoteFolderId() {
        return remoteFolderId;
    }

    public void setRemoteFolderId(String remoteFolderId) {
        this.remoteFolderId = remoteFolderId;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public void setMinecraftVersion(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
    }

    public Boolean modded() {
        return modded;
    }

    public void setModded(Boolean modded) {
        this.modded = modded;
    }

    public boolean syncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public Instant lastSync() {
        return lastSync;
    }

    public void setLastSync(Instant lastSync) {
        this.lastSync = lastSync;
    }

    public String lastSyncDevice() {
        return lastSyncDevice;
    }

    public void setLastSyncDevice(String lastSyncDevice) {
        this.lastSyncDevice = lastSyncDevice;
    }

    public long remoteRevision() {
        return remoteRevision;
    }

    public void setRemoteRevision(long remoteRevision) {
        this.remoteRevision = remoteRevision;
    }

    public SyncState state() {
        return state;
    }

    public void setState(SyncState state) {
        this.state = state;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        this.statusMessage = statusMessage;
    }

    /** « Minecraft 1.21.10 · Moddé » */
    public String describeVersion() {
        String v = minecraftVersion == null || minecraftVersion.isBlank()
                ? "Version inconnue" : "Minecraft " + minecraftVersion;
        String m = modded == null ? "Type inconnu" : (modded ? "Moddé" : "Vanilla");
        return v + " · " + m;
    }

    @Override
    public String toString() {
        return name + " (" + localPath + ")";
    }
}
