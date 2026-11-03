package me.sfclog.agentbridge.plugin;

import me.sfclog.agentbridge.common.Entry;
import me.sfclog.agentbridge.common.Meta;
import me.sfclog.agentbridge.common.PathUtil;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Sandboxed file operations. All paths are resolved and verified
 * against the configured sandbox root.
 */
public class FileService {

    private final Path root;

    public FileService(Path root) {
        this.root = root;
    }

    /** List directory entries. */
    public Meta list(String requestPath) throws IOException {
        Path dir = resolve(requestPath);
        if (!Files.exists(dir)) throw new IOException("Not found: " + requestPath);
        if (!Files.isDirectory(dir)) throw new IOException("Not a directory: " + requestPath);

        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(a -> a.getFileName().toString()))
                  .forEach(p -> {
                      try {
                          BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                          entries.add(new Entry(
                                  p.getFileName().toString(),
                                  attrs.isDirectory(),
                                  attrs.size(),
                                  attrs.lastModifiedTime().toMillis()
                          ));
                      } catch (IOException ignored) {}
                  });
        }
        Meta m = new Meta();
        m.ok = true;
        m.entries = entries;
        return m;
    }

    /** Stat a single path. */
    public Meta stat(String requestPath) throws IOException {
        Path p = resolve(requestPath);
        Meta m = new Meta();
        m.exists = Files.exists(p);
        if (!m.exists) return m;
        if (Files.isDirectory(p)) {
            m.isDir = true;
            m.size = 0;
        } else {
            m.size = Files.size(p);
            m.isDir = false;
        }
        m.modified = Files.getLastModifiedTime(p).toMillis();
        m.ok = true;
        return m;
    }

    /** Check existence. */
    public Meta exists(String requestPath) throws IOException {
        Path p = resolve(requestPath);
        Meta m = new Meta();
        m.exists = Files.exists(p);
        return m;
    }

    /** Delete file or empty directory. */
    public Meta delete(String requestPath, boolean recursive) throws IOException {
        Path p = resolve(requestPath);
        if (!Files.exists(p)) throw new IOException("Not found: " + requestPath);
        if (recursive && Files.isDirectory(p)) {
            try (var walk = Files.walk(p)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                });
            }
        } else {
            Files.delete(p);
        }
        Meta m = new Meta();
        m.ok = true;
        return m;
    }

    /** Move / rename. */
    public Meta move(String srcPath, String destPath) throws IOException {
        Path src = resolve(srcPath);
        Path dst = resolve(destPath);
        if (!Files.exists(src)) throw new IOException("Source not found: " + srcPath);
        if (Files.exists(dst)) throw new IOException("Destination exists: " + destPath);
        if (dst.getParent() != null) Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        Meta m = new Meta();
        m.ok = true;
        return m;
    }

    /** Create directory (and parents if createDirs). */
    public Meta mkdir(String requestPath, boolean createDirs) throws IOException {
        Path dir = resolve(requestPath);
        if (createDirs) {
            Files.createDirectories(dir);
        } else {
            Files.createDirectory(dir);
        }
        Meta m = new Meta();
        m.ok = true;
        return m;
    }

    /** Open a file for reading (returns the file channel). */
    public Path resolveFile(String requestPath) throws IOException {
        return resolve(requestPath);
    }

    // ---------- internal ----------

    private Path resolve(String request) throws IOException {
        return PathUtil.resolve(root, request);
    }
}