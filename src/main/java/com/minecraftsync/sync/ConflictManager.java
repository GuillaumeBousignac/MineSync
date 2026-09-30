package com.minecraftsync.sync;

import com.minecraftsync.model.Session;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Conflits : description pour l'utilisateur et copies de sauvegarde avant toute opération destructive.
 */
public class ConflictManager {

    public enum Resolution { KEEP_LOCAL, KEEP_REMOTE }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final Path backupsRoot;
    private volatile int backupsToKeep;

    public ConflictManager(Path backupsRoot, int backupsToKeep) {
        this.backupsRoot = backupsRoot;
        this.backupsToKeep = Math.max(1, backupsToKeep);
    }

    public void setBackupsToKeep(int n) {
        this.backupsToKeep = Math.max(1, n);
    }

    public Path backupsDirFor(Session s) {
        String safe = s.name().replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
        return backupsRoot.resolve(safe + "_" + s.id().substring(0, 8));
    }

    public static SyncResult.ConflictInfo describe(FileComparator.Scan local, Manifest remote) {
        return new SyncResult.ConflictInfo(
                local.files().size(), local.totalSize(), local.lastModified(),
                remote == null ? 0 : remote.files().size(),
                remote == null ? 0 : remote.totalSize(),
                remote == null ? null : remote.updatedAt(),
                remote == null ? "?" : remote.deviceName(),
                FileComparator.countDifferences(local.hashes(), remote == null ? java.util.Map.of() : remote.hashes()));
    }

    /**
     * Copie intégrale du monde local. Renvoie null si le monde est vide ou absent (rien à protéger).
     */
    public Path backupWorld(Session s, Path world, String reason) throws IOException {
        if (!Files.isDirectory(world)) return null;
        try (Stream<Path> content = Files.list(world)) {
            if (content.findAny().isEmpty()) return null;
        }
        Path target = backupsDirFor(s).resolve(LocalDateTime.now().format(STAMP) + "_" + reason)
                .resolve(world.getFileName().toString());
        Files.createDirectories(target);
        Files.walkFileTree(world, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String rel = FileComparator.relative(world, dir);
                if (rel.equals(FileComparator.TMP_DIR)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(world.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || file.getFileName().toString().equals(FileComparator.SESSION_LOCK)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.copy(file, target.resolve(world.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
        prune(backupsDirFor(s));
        return target;
    }

    private void prune(Path dir) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.list(dir)) {
            all = s.filter(Files::isDirectory).sorted(Comparator.comparing(Path::getFileName).reversed()).toList();
        }
        for (int i = backupsToKeep; i < all.size(); i++) {
            deleteRecursively(all.get(i));
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
