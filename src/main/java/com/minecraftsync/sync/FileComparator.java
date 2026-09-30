package com.minecraftsync.sync;

import com.minecraftsync.model.FileEntry;
import com.minecraftsync.util.Hashing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Analyse récursive d'un monde et comparaison avec l'état de référence.
 * <p>
 * Le SHA-256 n'est recalculé que si la taille ou la date de modification d'un fichier
 * a changé depuis la dernière synchronisation : une analyse sans changement est quasi instantanée.
 */
public final class FileComparator {

    /** Dossier technique interne (téléchargements temporaires, retour arrière). Jamais synchronisé. */
    public static final String TMP_DIR = ".minecraftsync-tmp";
    /** Verrou posé par Minecraft sur le monde ouvert. Jamais synchronisé. */
    public static final String SESSION_LOCK = "session.lock";

    private FileComparator() {
    }

    /** Résultat d'une analyse locale. */
    public record Scan(SortedMap<String, FileEntry> files, SortedSet<String> dirs, int hashed) {

        public long totalSize() {
            return files.values().stream().mapToLong(FileEntry::size).sum();
        }

        public Instant lastModified() {
            return files.values().stream().map(f -> Instant.ofEpochMilli(f.lastModified()))
                    .max(Instant::compareTo).orElse(null);
        }

        public Map<String, String> hashes() {
            Map<String, String> m = new TreeMap<>();
            files.forEach((p, f) -> m.put(p, f.hash()));
            return m;
        }
    }

    public static Scan scan(Path world, Map<String, FileEntry> baseline) throws IOException {
        SortedMap<String, FileEntry> files = new TreeMap<>();
        SortedSet<String> dirs = new TreeSet<>();
        int[] hashed = {0};
        if (!Files.isDirectory(world)) {
            return new Scan(files, dirs, 0);
        }
        Files.walkFileTree(world, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(world)) return FileVisitResult.CONTINUE;
                String rel = relative(world, dir);
                if (rel.equals(TMP_DIR)) return FileVisitResult.SKIP_SUBTREE;
                dirs.add(rel);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE; // liens symboliques ignorés
                String rel = relative(world, file);
                if (rel.equals(SESSION_LOCK)) return FileVisitResult.CONTINUE;
                long size = attrs.size();
                long mtime = attrs.lastModifiedTime().toMillis();
                FileEntry known = baseline.get(rel);
                String hash;
                if (known != null && known.size() == size && known.lastModified() == mtime) {
                    hash = known.hash();
                } else {
                    hash = Hashing.sha256(file);
                    hashed[0]++;
                }
                files.put(rel, new FileEntry(rel, size, mtime, hash));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw new IOException("Lecture impossible : " + file + " (" + exc.getMessage() + ")", exc);
            }
        });
        return new Scan(files, dirs, hashed[0]);
    }

    public static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    /** Même ensemble de chemins avec les mêmes empreintes. */
    public static boolean sameContent(Map<String, String> a, Map<String, String> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, String> e : a.entrySet()) {
            if (!Objects.equals(e.getValue(), b.get(e.getKey()))) return false;
        }
        return true;
    }

    /** Nombre de chemins dont le contenu diffère (ajoutés, supprimés ou modifiés). */
    public static int countDifferences(Map<String, String> a, Map<String, String> b) {
        TreeSet<String> all = new TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        int n = 0;
        for (String p : all) {
            if (!Objects.equals(a.get(p), b.get(p))) n++;
        }
        return n;
    }

    /** Le fichier est-il encore identique à ce qui a été analysé ? */
    public static boolean unchangedSinceScan(Path file, FileEntry scanned) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.size(file) == scanned.size()
                && Files.getLastModifiedTime(file).toMillis() == scanned.lastModified();
    }
}
