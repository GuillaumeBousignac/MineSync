package com.minecraftsync.model;

/** État affiché pour chaque session. */
public enum SyncState {
    IDLE("En attente", "neutral"),
    UP_TO_DATE("Synchronisation active", "ok"),
    SYNCING("Synchronisation en cours…", "busy"),
    WORLD_IN_USE("Monde actuellement utilisé — synchronisation en attente", "warn"),
    WAITING_AFTER_CLOSE("Minecraft fermé — synchronisation imminente", "busy"),
    REMOTE_IN_USE("Monde en cours d'utilisation sur un autre appareil", "warn"),
    CONFLICT("Conflit détecté", "error"),
    ERROR("Synchronisation échouée", "error"),
    DISABLED("Synchronisation désactivée", "neutral"),
    OFFLINE("Google Drive non connecté", "neutral");

    private final String label;
    private final String styleClass;

    SyncState(String label, String styleClass) {
        this.label = label;
        this.styleClass = styleClass;
    }

    public String label() {
        return label;
    }

    /** Suffixe de classe CSS : ok, warn, error, busy, neutral. */
    public String styleClass() {
        return styleClass;
    }
}
