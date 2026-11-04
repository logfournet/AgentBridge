package me.sfclog.agentbridge.plugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches Minecraft console commands on the server main thread and captures
 * the output lines produced while the command runs.
 *
 * <p>Paper only accepts its own console sender for {@code dispatchCommand}, so
 * we dispatch as the real console and tap the Log4j2 root logger for the
 * duration of the call to collect what the command printed.</p>
 */
public class BukkitConsoleDispatcher implements ConsoleService.Dispatcher {

    private final Plugin plugin;

    public BukkitConsoleDispatcher(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> dispatch(String command, long timeoutMs) throws Exception {
        CompletableFuture<List<String>> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            List<String> captured = Collections.synchronizedList(new ArrayList<>());
            Logger root = (Logger) LogManager.getRootLogger();

            AbstractAppender appender = new AbstractAppender(
                    "AgentBridgeConsoleCapture", null, null, false, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    String msg = event.getMessage().getFormattedMessage();
                    if (msg == null) return;
                    // Strip the leading "[HH:mm:ss] [Thread/LEVEL]: " noise
                    String clean = msg.replaceFirst("^\\[[^\\]]*]\\s*", "").trim();
                    clean = stripFormatting(clean);
                    if (!clean.isEmpty()) captured.add(clean);
                }
            };
            appender.start();
            root.addAppender(appender);

            boolean handled;
            try {
                handled = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Throwable t) {
                captured.add("[error] " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
                handled = false;
            } finally {
                root.removeAppender(appender);
                appender.stop();
            }

            if (!handled) captured.add("[error] unknown or unusable command: " + command);
            future.complete(new ArrayList<>(captured));
        });

        long wait = timeoutMs > 0 ? timeoutMs : 10_000L;
        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new Exception("Console command timed out after " + wait + "ms");
        }
    }

    /** Removes ANSI escape sequences and legacy {@code §} colour codes. */
    static String stripFormatting(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
        out = out.replaceAll("§[0-9a-fk-orx]", "");
        return out;
    }
}
