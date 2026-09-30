package com.minecraftsync.sync;

import com.minecraftsync.util.MiniJson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Manifeste stocké dans chaque dossier de session sur le stockage distant.
 * <p>
 * Il décrit la dernière version complète du monde envoyée : c'est le « point de validation »
 * de la synchronisation. Tant qu'il n'est pas réécrit, les fichiers envoyés ne comptent pas.
 */
public final class Manifest {

    public static final String FILE_NAME = "manifest.json";
    public static final int FORMAT = 1;

    /** Fichier distant : taille, empreinte et identifiant du fichier sur le stockage. */
    public record Entry(long size, String hash, String fileId) {
    }

    private long revision;
    private String sessionName;
    private String worldFolderName;
    private String minecraftVersion;
    private Boolean modded;
    private String deviceId;
    private String deviceName;
    private Instant updatedAt;
    private final SortedMap<String, Entry> files = new TreeMap<>();
    private final SortedSet<String> dirs = new TreeSet<>();

    public long revision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }
    public String sessionName() { return sessionName; }
    public void setSessionName(String v) { this.sessionName = v; }
    public String worldFolderName() { return worldFolderName; }
    public void setWorldFolderName(String v) { this.worldFolderName = v; }
    public String minecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String v) { this.minecraftVersion = v; }
    public Boolean modded() { return modded; }
    public void setModded(Boolean v) { this.modded = v; }
    public String deviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public String deviceName() { return deviceName; }
    public void setDeviceName(String v) { this.deviceName = v; }
    public Instant updatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public SortedMap<String, Entry> files() { return files; }
    public SortedSet<String> dirs() { return dirs; }

    public long totalSize() {
        return files.values().stream().mapToLong(Entry::size).sum();
    }

    /** chemin → empreinte */
    public Map<String, String> hashes() {
        Map<String, String> m = new TreeMap<>();
        files.forEach((p, e) -> m.put(p, e.hash()));
        return m;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("revision", revision);
        root.put("sessionName", sessionName);
        root.put("worldFolderName", worldFolderName);
        root.put("minecraftVersion", minecraftVersion);
        root.put("modded", modded);
        root.put("deviceId", deviceId);
        root.put("deviceName", deviceName);
        root.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        root.put("dirs", new ArrayList<>(dirs));
        Map<String, Object> f = new LinkedHashMap<>();
        files.forEach((path, e) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("size", e.size());
            m.put("sha256", e.hash());
            m.put("id", e.fileId());
            f.put(path, m);
        });
        root.put("files", f);
        return MiniJson.write(root);
    }

    @SuppressWarnings("unchecked")
    public static Manifest fromJson(String json) {
        Map<String, Object> root = MiniJson.parseObject(json);
        Manifest m = new Manifest();
        long format = asLong(root.get("format"));
        if (format > FORMAT) {
            throw new IllegalArgumentException("Manifeste créé par une version plus récente de Minecraft Sync (format "
                    + format + "). Mettez l'application à jour.");
        }
        m.revision = asLong(root.get("revision"));
        m.sessionName = (String) root.get("sessionName");
        m.worldFolderName = (String) root.get("worldFolderName");
        m.minecraftVersion = (String) root.get("minecraftVersion");
        m.modded = (Boolean) root.get("modded");
        m.deviceId = (String) root.get("deviceId");
        m.deviceName = (String) root.get("deviceName");
        Object up = root.get("updatedAt");
        m.updatedAt = up == null ? null : Instant.parse((String) up);
        Object dirs = root.get("dirs");
        if (dirs instanceof List<?> list) {
            list.forEach(d -> m.dirs.add((String) d));
        }
        Object files = root.get("files");
        if (files instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                m.files.put((String) e.getKey(),
                        new Entry(asLong(v.get("size")), (String) v.get("sha256"), (String) v.get("id")));
            }
        }
        return m;
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
