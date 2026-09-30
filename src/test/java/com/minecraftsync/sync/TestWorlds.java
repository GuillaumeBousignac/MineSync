package com.minecraftsync.sync;

import com.minecraftsync.util.Hashing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Création de mondes factices et comparaison de dossiers. */
public final class TestWorlds {

    private TestWorlds() {
    }

    /** Monde « moddé » réaliste : régions, joueurs, dimensions, données de mod, dossier vide. */
    public static void createWorld(Path world, String version, List<String> brands, long seed) throws IOException {
        Files.createDirectories(world);
        writeLevelDat(world.resolve("level.dat"), world.getFileName().toString(), version, brands);
        Random r = new Random(seed);
        writeRandom(world.resolve("region/r.0.0.mca"), 300_000, r);
        writeRandom(world.resolve("region/r.0.1.mca"), 150_000, r);
        writeRandom(world.resolve("region/r.-1.0.mca"), 0, r);
        writeRandom(world.resolve("playerdata/0f3a-uuid.dat"), 2_000, r);
        writeRandom(world.resolve("DIM-1/region/r.0.0.mca"), 50_000, r);
        writeRandom(world.resolve("data/mymod/custom_machines.dat"), 4_000, r);
        writeRandom(world.resolve("serverconfig/mymod-server.toml"), 500, r);
        Files.createDirectories(world.resolve("datapacks"));
        Files.writeString(world.resolve("session.lock"), "☃");
    }

    public static void writeRandom(Path file, int size, Random r) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] b = new byte[size];
        r.nextBytes(b);
        Files.write(file, b);
    }

    /** Modifie un fichier (contenu et date). */
    public static void touch(Path file, String extra) throws IOException {
        Files.write(file, extra.getBytes(), java.nio.file.StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));
    }

    /** chemin relatif → SHA-256, hors session.lock et dossier technique. */
    public static Map<String, String> tree(Path root) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (!Files.exists(root)) return m;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.equals("session.lock") || rel.startsWith(FileComparator.TMP_DIR)) continue;
                m.put(rel, Hashing.sha256(p));
            }
        }
        return m;
    }

    // ---------- Écriture NBT minimale ----------

    public static void writeLevelDat(Path file, String levelName, String version, List<String> brands) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(10);
            out.writeUTF("");
            // Data {
            out.writeByte(10);
            out.writeUTF("Data");
            tagString(out, "LevelName", levelName);
            out.writeByte(4);
            out.writeUTF("LastPlayed");
            out.writeLong(System.currentTimeMillis());
            out.writeByte(1);
            out.writeUTF("WasModded");
            out.writeByte(0);
            if (version != null) {
                out.writeByte(10);
                out.writeUTF("Version");
                tagString(out, "Name", version);
                out.writeByte(3);
                out.writeUTF("Id");
                out.writeInt(4556);
                out.writeByte(0);
            }
            if (brands != null) {
                out.writeByte(9);
                out.writeUTF("ServerBrands");
                out.writeByte(8);
                out.writeInt(brands.size());
                for (String b : brands) out.writeUTF(b);
            }
            out.writeByte(0); // fin Data
            out.writeByte(0); // fin racine
        }
        Files.createDirectories(file.getParent());
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(file))) {
            o.write(bytes.toByteArray());
        }
    }

    private static void tagString(DataOutputStream out, String name, String value) throws IOException {
        out.writeByte(8);
        out.writeUTF(name);
        out.writeUTF(value);
    }
}
