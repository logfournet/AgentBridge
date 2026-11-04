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

    private final boolean enabled;
    private final List<String> whitelist;   // empty → allow every command
    private final List<String> blacklist;   // always rejected
    private final long defaultTimeoutMs;
    private final Dispatcher dispatcher;

    public ConsoleService(boolean enabled, List<String> whitelist, List<String> blacklist,
                          long defaultTimeoutMs, Dispatcher dispatcher) {
        this.enabled = enabled;
        this.whitelist = whitelist == null ? List.of() : whitelist;
        this.blacklist = blacklist == null ? List.of() : blacklist;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.dispatcher = dispatcher;
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
        List<String> lines = dispatcher.dispatch(cmdFinal, timeout);
        List<String> out = new ArrayList<>(lines == null ? List.of() : lines);

        m.exitCode = 0;
        m.stdout = String.join("\n", out);
        return m;
    }
}
