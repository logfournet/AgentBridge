package me.sfclog.agentbridge.plugin;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Plugin configuration. Deliberately free of Bukkit types so the file/exec
 * services and handler can be exercised in a standalone harness.
 */
public class PluginConfig {

    public String host = "127.0.0.1";
    public int port = 7000;
    public String apiKey = "change-me-plugin-key";
    public String agentId = "main";
    public Path sandboxRoot = defaultRoot();
    public boolean execEnabled = false;
    public long execTimeoutMs = 30000;
    public List<String> execWhitelist = List.of();
    public boolean consoleEnabled = false;
    public long consoleTimeoutMs = 10000;
    public List<String> consoleWhitelist = List.of();
    public static final List<String> DEFAULT_CONSOLE_BLACKLIST =
            List.of("stop", "restart", "reload", "op", "deop", "plugman");
    public List<String> consoleBlacklist = DEFAULT_CONSOLE_BLACKLIST;
    public int chunkSize = 262144;
    public boolean debug = false;

    private static Path defaultRoot() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public static Path resolveRoot(String rootStr) {
        if (rootStr == null || rootStr.isBlank()) return defaultRoot();
        Path root = Paths.get(rootStr);
        if (!root.isAbsolute()) {
            root = Paths.get(System.getProperty("user.dir")).resolve(root);
        }
        return root.toAbsolutePath().normalize();
    }
}