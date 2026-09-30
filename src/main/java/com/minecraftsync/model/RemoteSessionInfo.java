package com.minecraftsync.model;

import com.minecraftsync.sync.Manifest;

/** Session trouvée sur Google Drive, pas encore ajoutée à ce PC. */
public record RemoteSessionInfo(String folderId, String folderName, Manifest manifest) {

    public String displayName() {
        return manifest.sessionName() != null ? manifest.sessionName() : folderName;
    }
}
