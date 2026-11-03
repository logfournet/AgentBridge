package me.sfclog.agentbridge.plugin;

import me.sfclog.agentbridge.common.Meta;
import me.sfclog.agentbridge.common.PathUtil;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands on the host with optional whitelisting. The working
 * directory is sandboxed to {@code root}.
 */
public class ExecService {

    private final boolean enabled;
    private final long timeoutMs;
    private final List<String> whitelist;
    private final Path root;

    public ExecService(boolean enabled, long timeoutMs, List<String> whitelist, Path root) {
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
        this.whitelist = whitelist;
        this.root = root.toAbsolutePath().normalize();
    }

    public Meta execute(String command, String cwd, long overrideTimeout) throws Exception {
        if (!enabled) {
            Meta m = new Meta();
            m.exitCode = -1;
            m.stderr = "Execution is disabled on this agent";
            return m;
        }

        // Parse command to check whitelist
        String trimmed = command.trim();
        String program = trimmed.split("\\s+")[0];

        if (!whitelist.isEmpty()) {
            boolean allowed = whitelist.stream().anyMatch(w -> program.equalsIgnoreCase(w));
            if (!allowed) {
                Meta m = new Meta();
                m.exitCode = -1;
                m.stderr = "Command \"" + program + "\" is not in the exec whitelist";
                return m;
            }
        }

        long timeout = overrideTimeout > 0 ? overrideTimeout : timeoutMs;

        // Determine working directory (sandboxed)
        File workDir;
        try {
            Path dir = PathUtil.resolve(root, (cwd == null || cwd.isBlank()) ? "" : cwd);
            workDir = dir.toFile();
            if (!workDir.isDirectory()) workDir = root.toFile();
        } catch (Exception e) {
            Meta m = new Meta();
            m.exitCode = -1;
            m.stderr = "Working directory escapes sandbox: " + cwd;
            return m;
        }

        // On Windows, wrap in cmd.exe /c if not already
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String[] cmdArray;
        if (isWindows) {
            cmdArray = new String[]{"cmd.exe", "/c", command};
        } else {
            cmdArray = new String[]{"sh", "-c", command};
        }

        ProcessBuilder pb = new ProcessBuilder(cmdArray)
                .directory(workDir)
                .redirectErrorStream(false);
        Process p = pb.start();

        // Read stdout and stderr in background threads
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outThread = new Thread(() -> copy(p.getInputStream(), out));
        Thread errThread = new Thread(() -> copy(p.getErrorStream(), err));
        outThread.start();
        errThread.start();

        boolean finished;
        if (timeout > 0) {
            finished = p.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                Meta m = new Meta();
                m.exitCode = -1;
                m.stderr = "Process timed out after " + timeout + "ms";
                return m;
            }
        } else {
            finished = true;
            p.waitFor();
        }

        outThread.join(5000);
        errThread.join(5000);

        Meta m = new Meta();
        m.exitCode = p.exitValue();
        m.stdout = out.toString();
        m.stderr = err.toString();
        return m;
    }

    private void copy(InputStream is, StringBuilder sb) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        } catch (IOException ignored) {}
    }
}