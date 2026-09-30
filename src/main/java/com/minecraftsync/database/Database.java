package com.minecraftsync.database;

import com.minecraftsync.model.DeviceIdentity;
import com.minecraftsync.model.FileEntry;
import com.minecraftsync.model.Session;
import com.minecraftsync.model.SyncState;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Données locales : sessions, état de référence des fichiers (dernière synchro réussie), réglages.
 * Une seule connexion, accès synchronisé (utilisée depuis plusieurs fils d'exécution).
 */
public class Database implements AutoCloseable {

    private final Connection conn;

    public Database(Path file) {
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            local_path TEXT NOT NULL,
                            remote_folder_id TEXT,
                            minecraft_version TEXT,
                            modded INTEGER,
                            sync_enabled INTEGER NOT NULL DEFAULT 1,
                            last_sync INTEGER,
                            last_sync_device TEXT,
                            remote_revision INTEGER NOT NULL DEFAULT 0,
                            state TEXT,
                            status_message TEXT,
                            created_at INTEGER NOT NULL
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS files (
                            session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                            relative_path TEXT NOT NULL,
                            hash TEXT NOT NULL,
                            size INTEGER NOT NULL,
                            last_modified INTEGER NOT NULL,
                            PRIMARY KEY (session_id, relative_path)
                        )""");
                st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Impossible d'ouvrir la base " + file + " : " + e.getMessage(), e);
        }
    }

    // ---------------- Sessions ----------------

    public synchronized List<Session> listSessions() {
        List<Session> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM sessions ORDER BY created_at")) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw wrap(e);
        }
        return list;
    }

    public synchronized void saveSession(Session s) {
        String sql = """
                INSERT INTO sessions (id, name, local_path, remote_folder_id, minecraft_version, modded,
                    sync_enabled, last_sync, last_sync_device, remote_revision, state, status_message, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name, local_path=excluded.local_path, remote_folder_id=excluded.remote_folder_id,
                    minecraft_version=excluded.minecraft_version, modded=excluded.modded,
                    sync_enabled=excluded.sync_enabled, last_sync=excluded.last_sync,
                    last_sync_device=excluded.last_sync_device, remote_revision=excluded.remote_revision,
                    state=excluded.state, status_message=excluded.status_message""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, s.id());
            ps.setString(2, s.name());
            ps.setString(3, s.localPathString());
            ps.setString(4, s.remoteFolderId());
            ps.setString(5, s.minecraftVersion());
            if (s.modded() == null) ps.setNull(6, Types.INTEGER);
            else ps.setInt(6, s.modded() ? 1 : 0);
            ps.setInt(7, s.syncEnabled() ? 1 : 0);
            if (s.lastSync() == null) ps.setNull(8, Types.INTEGER);
            else ps.setLong(8, s.lastSync().toEpochMilli());
            ps.setString(9, s.lastSyncDevice());
            ps.setLong(10, s.remoteRevision());
            ps.setString(11, s.state() == null ? null : s.state().name());
            ps.setString(12, s.statusMessage());
            ps.setLong(13, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    /** Retire la session de l'application. Ne touche JAMAIS au monde local ni au stockage distant. */
    public synchronized void deleteSession(String id) {
        try (PreparedStatement f = conn.prepareStatement("DELETE FROM files WHERE session_id=?");
             PreparedStatement s = conn.prepareStatement("DELETE FROM sessions WHERE id=?")) {
            f.setString(1, id);
            f.executeUpdate();
            s.setString(1, id);
            s.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    private Session map(ResultSet rs) throws SQLException {
        Session s = new Session(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setLocalPath(rs.getString("local_path"));
        s.setRemoteFolderId(rs.getString("remote_folder_id"));
        s.setMinecraftVersion(rs.getString("minecraft_version"));
        int modded = rs.getInt("modded");
        s.setModded(rs.wasNull() ? null : modded == 1);
        s.setSyncEnabled(rs.getInt("sync_enabled") == 1);
        long last = rs.getLong("last_sync");
        s.setLastSync(rs.wasNull() ? null : Instant.ofEpochMilli(last));
        s.setLastSyncDevice(rs.getString("last_sync_device"));
        s.setRemoteRevision(rs.getLong("remote_revision"));
        String state = rs.getString("state");
        SyncState st = SyncState.IDLE;
        if (state != null) {
            try {
                st = SyncState.valueOf(state);
            } catch (IllegalArgumentException ignored) {
                // état inconnu (ancienne version) : on repart de IDLE
            }
        }
        // États transitoires : non pertinents après un redémarrage.
        if (st == SyncState.SYNCING || st == SyncState.WORLD_IN_USE || st == SyncState.WAITING_AFTER_CLOSE) {
            st = SyncState.IDLE;
        }
        s.setState(st);
        s.setStatusMessage(rs.getString("status_message"));
        return s;
    }

    // ---------------- État de référence ----------------

    public synchronized Map<String, FileEntry> loadBaseline(String sessionId) {
        Map<String, FileEntry> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT relative_path, hash, size, last_modified FROM files WHERE session_id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String p = rs.getString(1);
                    map.put(p, new FileEntry(p, rs.getLong(3), rs.getLong(4), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
        return map;
    }

    /** Remplace atomiquement l'état de référence d'une session. */
    public synchronized void replaceBaseline(String sessionId, Collection<FileEntry> entries) {
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM files WHERE session_id=?");
                 PreparedStatement ins = conn.prepareStatement(
                         "INSERT INTO files (session_id, relative_path, hash, size, last_modified) VALUES (?,?,?,?,?)")) {
                del.setString(1, sessionId);
                del.executeUpdate();
                for (FileEntry f : entries) {
                    ins.setString(1, sessionId);
                    ins.setString(2, f.path());
                    ins.setString(3, f.hash());
                    ins.setLong(4, f.size());
                    ins.setLong(5, f.lastModified());
                    ins.addBatch();
                }
                ins.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    // ---------------- Réglages ----------------

    public synchronized String getSetting(String key, String def) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM settings WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null ? rs.getString(1) : def;
            }
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    public synchronized void setSetting(String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO settings (key, value) VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw wrap(e);
        }
    }

    public int getIntSetting(String key, int def) {
        try {
            return Integer.parseInt(getSetting(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Identité de ce PC : générée une fois, nom modifiable dans les réglages. */
    public synchronized DeviceIdentity device() {
        String id = getSetting("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            setSetting("device_id", id);
        }
        String name = getSetting("device_name", null);
        if (name == null) {
            name = defaultDeviceName();
            setSetting("device_name", name);
        }
        return new DeviceIdentity(id, name);
    }

    private static String defaultDeviceName() {
        String env = System.getenv("COMPUTERNAME");
        if (env == null) env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "PC-" + UUID.randomUUID().toString().substring(0, 4);
        }
    }

    private static IllegalStateException wrap(SQLException e) {
        return new IllegalStateException("Erreur de base de données : " + e.getMessage(), e);
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
