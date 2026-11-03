package me.sfclog.agentbridge.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves client-supplied paths inside a sandboxed root directory.
 * Rejects any path that would escape the root via "..", absolute paths,
 * or symbolic links pointing outside the root.
 */
public final class PathUtil {

    private PathUtil() {}

    /**
     * Resolve {@code request} relative to {@code root}, normalising and
     * verifying the result stays under {@code root}.
     *
     * @throws IOException if the path escapes the sandbox
     */
    public static Path resolve(Path root, String request) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();

        if (request == null || request.isBlank()) {
            return normalizedRoot;
        }

        String cleaned = request.replace('\\', '/');
        // Strip leading slashes so absolute client paths are treated as root-relative
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }

        Path resolved = normalizedRoot.resolve(cleaned).normalize();

        if (!resolved.startsWith(normalizedRoot)) {
            throw new SandboxViolationException(request);
        }

        // Follow symlinks: a link inside the sandbox must not point outside it
        try {
            if (Files.exists(resolved)) {
                Path realRoot = Files.exists(normalizedRoot)
                        ? normalizedRoot.toRealPath()
                        : normalizedRoot;
                Path realResolved = resolved.toRealPath();
                if (!realResolved.startsWith(realRoot)) {
                    throw new SandboxViolationException(request);
                }
            }
        } catch (IOException e) {
            if (e instanceof SandboxViolationException sve) throw sve;
            // Unreadable links etc. — fall back to the lexical check above
        }

        return resolved;
    }

    /**
     * Convert an absolute path back to a root-relative, forward-slash path.
     */
    public static String relativize(Path root, Path absolute) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedAbs = absolute.toAbsolutePath().normalize();
        try {
            Path rel = normalizedRoot.relativize(normalizedAbs);
            return rel.toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return absolute.toString().replace('\\', '/');
        }
    }

    /** Thrown when a request tries to reach outside the sandbox root. */
    public static class SandboxViolationException extends IOException {
        public SandboxViolationException(String request) {
            super("Path escapes sandbox: " + request);
        }
    }
}
