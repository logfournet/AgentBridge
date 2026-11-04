package me.sfclog.agentbridge.plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dispatches Minecraft console commands on the server main thread and captures
 * everything the command writes back (plugin messages included).
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
            CaptureSender sender = new CaptureSender();
            try {
                Bukkit.dispatchCommand(sender, command);
            } catch (Throwable t) {
                sender.error(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
            future.complete(sender.lines());
        });

        long wait = timeoutMs > 0 ? timeoutMs : 10_000L;
        try {
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new Exception("Console command timed out after " + wait + "ms");
        }
    }

    /** Console-like sender: full permissions, collects every message. */
    private static final class CaptureSender implements CommandSender {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        void error(String msg) {
            lines.add("[error] " + msg);
        }

        List<String> lines() {
            synchronized (lines) {
                return new ArrayList<>(lines);
            }
        }

        private void capture(String message) {
            if (message == null) return;
            for (String line : message.split("\r?\n")) {
                if (!line.isEmpty()) lines.add(line);
            }
        }

        // ---- messages ----
        @Override public void sendMessage(String message) { capture(message); }
        @Override public void sendMessage(String... messages) {
            for (String m : messages) capture(m);
        }
        @Override public void sendMessage(UUID sender, String message) { capture(message); }
        @Override public void sendMessage(UUID sender, String... messages) {
            for (String m : messages) capture(m);
        }
        @Override public void sendMessage(Component message) {
            capture(message == null ? null : PlainTextComponentSerializer.plainText().serialize(message));
        }

        // ---- identity / permissions ----
        @Override public String getName() { return "AgentBridge"; }
        @Override public Component name() { return Component.text("AgentBridge"); }
        @Override public Server getServer() { return Bukkit.getServer(); }
        @Override public boolean isPermissionSet(String name) { return true; }
        @Override public boolean isPermissionSet(Permission perm) { return true; }
        @Override public boolean hasPermission(String name) { return true; }
        @Override public boolean hasPermission(Permission perm) { return true; }
        @Override public PermissionAttachment addAttachment(Plugin p, String name, boolean value) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin p) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin p, String name, boolean value, int ticks) { return null; }
        @Override public PermissionAttachment addAttachment(Plugin p, int ticks) { return null; }
        @Override public void removeAttachment(PermissionAttachment attachment) { }
        @Override public void recalculatePermissions() { }
        @Override public Set<PermissionAttachmentInfo> getEffectivePermissions() { return Set.of(); }
        @Override public boolean isOp() { return true; }
        @Override public void setOp(boolean value) { }
        @Override public org.bukkit.command.CommandSender.Spigot spigot() {
            return Bukkit.getConsoleSender().spigot();
        }
    }
}
