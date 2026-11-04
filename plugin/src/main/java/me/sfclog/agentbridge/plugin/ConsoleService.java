package me.sfclog.agentbridge.plugin;

import me.sfclog.agentbridge.common.Meta;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes Minecraft server console commands (via {@link Dispatcher}, which the
 * Bukkit layer implements) with optional whitelist/blacklist filtering.
 *
 * <p>Deliberately free of Bukkit types so it can be unit-tested without a server.</p>
 */
public class ConsoleService {

    /** Runs a command on the server's main thread and returns captured output lines. */
    @FunctionalInterface
    public interface Dispatcher {
        List<String> dispatch(String command, long timeoutMs) throws Exception;
    }

    /** Command labels that can load/unload plugins (and therefore this plugin). */
    private static final List<String> PLUGIN_MANAGER_LABELS =
            List.of("plugman", "plugmanx", "pluginmanager", "pm", "pl", "rl");

    private final boolean enabled;
    private final List<String> whitelist;   // empty → allow every command
    private final List<String> blacklist;   // always rejected
    private final long defaultTimeoutMs;
    private final Dispatcher dispatcher;
    /** This plugin's own name, used to detect reload/unload of ourselves. */
    private final String selfName;

    /**
     * Console output is captured by tapping the JVM-wide Log4j2 root logger, so
     * two concurrent commands would see each other's lines. Serialise them.
     */
    private final Object consoleLock = new Object();

    public ConsoleService(boolean enabled, List<String> whitelist, List<String> blacklist,
                          long defaultTimeoutMs, Dispatcher dispatcher, String selfName) {
        this.enabled = enabled;
        this.whitelist = whitelist == null ? List.of() : whitelist;
        this.blacklist = blacklist == null ? List.of() : blacklist;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.dispatcher = dispatcher;
        this.selfName = selfName == null ? "" : selfName;
    }

    public Meta execute(String command, long overrideTimeoutMs) throws Exception {
        Meta m = new Meta();
        if (!enabled) {
            m.exitCode = -1;
            m.stderr = "Console commands are disabled on this agent (console.enabled: false)";
            return m;
        }
        if (dispatcher == null) {
            m.exitCode = -1;
            m.stderr = "Console is not available (no server attached)";
            return m;
        }

        String cmd = command == null ? "" : command.trim();
        // Strip a leading slash ("/say hi" == "say hi").
        while (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        final String cmdFinal = cmd;
        if (cmd.isEmpty()) {
            m.exitCode = -1;
            m.stderr = "Empty console command";
            return m;
        }

        String label = cmd.split("\\s+")[0].toLowerCase();
        // Namespaced commands: "minecraft:say" → "say"
        int colon = label.indexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) label = label.substring(colon + 1);
        final String labelFinal = label;

        if (blacklist.stream().anyMatch(b -> b.equalsIgnoreCase(labelFinal))) {
            m.exitCode = -1;
            m.stderr = "Console command \"" + labelFinal + "\" is blacklisted";
            return m;
        }
        if (!whitelist.isEmpty() && whitelist.stream().noneMatch(w -> w.equalsIgnoreCase(labelFinal))) {
            m.exitCode = -1;
            m.stderr = "Console command \"" + labelFinal + "\" is not in the console whitelist";
            return m;
        }

        long timeout = overrideTimeoutMs > 0 ? overrideTimeoutMs : defaultTimeoutMs;

        // Commands that reload/unload THIS plugin would kill the connection that
        // is waiting for the answer. Answer immediately, then run the command a
        // moment later on a detached thread.
        boolean affectsSelf = affectsSelf(cmdFinal);
        if (affectsSelf) {
            long delayMs = 1500L;
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    return;
                }
                try {
                    synchronized (consoleLock) {
                        dispatcher.dispatch(cmdFinal, timeout);
                    }
                } catch (Throwable ignored) {
                    // the plugin is gone by now - nothing to report to
                }
            }, "agentbridge-deferred-console");
            t.setDaemon(true);
            t.start();
            m.exitCode = 0;
            m.stdout = "Dispatched \"" + cmdFinal + "\" in " + delayMs
                    + "ms and returned early, because it reloads " + selfName
                    + " itself (output cannot be captured).";
            return m;
        }

        List<String> lines;
        synchronized (consoleLock) {
            lines = dispatcher.dispatch(cmdFinal, timeout);
        }
        List<String> out = new ArrayList<>(lines == null ? List.of() : lines);

        m.exitCode = 0;
        m.stdout = String.join("\n", out);
        return m;
    }

    /**
     * True when {@code cmd} would unload/reload this very plugin, i.e.
     * {@code plugman reload AgentBridge}, {@code plugman reload all},
     * {@code agentbridge reload}.
     */
    private boolean affectsSelf(String cmd) {
        if (selfName.isEmpty()) return false;
        String[] parts = cmd.trim().split("\\s+");
        String label = parts[0].toLowerCase(java.util.Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) label = label.substring(colon + 1);

        boolean pluginManager = PLUGIN_MANAGER_LABELS.contains(label);
        if (!pluginManager && !label.equalsIgnoreCase(selfName)) return false;
        if (parts.length < 2) return false;
        if (!isPluginLifecycle(parts[1].toLowerCase(java.util.Locale.ROOT))) return false;

        // `plugman reload AgentBridge` / `plugman reload all` / `plugman reload *`
        for (int i = 2; i < parts.length; i++) {
            String target = parts[i].toLowerCase(java.util.Locale.ROOT);
            if (target.equals("all") || target.equals("*") || target.contains(selfName.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        // Bare `/agentbridge reload`
        return !pluginManager && parts.length >= 2;
    }

    private static boolean isPluginLifecycle(String sub) {
        return switch (sub) {
            case "reload", "load", "unload", "disable", "enable", "restart", "rl", "reloadall" -> true;
            default -> false;
        };
    }
}
