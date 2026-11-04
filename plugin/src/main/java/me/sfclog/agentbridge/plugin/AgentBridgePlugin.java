package me.sfclog.agentbridge.plugin;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import me.sfclog.agentbridge.common.Log;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AgentBridgePlugin extends JavaPlugin {

    private PluginConfig pluginCfg;
    private FileService fileService;
    private ExecService execService;
    private ConsoleService consoleService;
    private AgentHandler handler;
    private Channel channel;

    private NioEventLoopGroup group;
    private EventExecutorGroup businessGroup;
    private int reconnectAttempt = 0;

    private static final int MAX_RECONNECT_DELAY = 30; // seconds

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();
        pluginCfg = parseConfig();
        Log.setDebug(pluginCfg.debug);

        // Route common Log output to the Paper logger
        Logger plog = getLogger();
        Log.setSink((level, msg) -> {
            switch (level) {
                case "WARN"  -> plog.warning(msg);
                case "ERROR" -> plog.severe(msg);
                case "DEBUG" -> { if (Log.isDebug()) plog.fine(msg); }
                default      -> plog.info(msg);
            }
        });

        fileService = new FileService(pluginCfg.sandboxRoot);
        execService = new ExecService(pluginCfg.execEnabled, pluginCfg.execTimeoutMs,
                pluginCfg.execWhitelist, pluginCfg.sandboxRoot);
        consoleService = new ConsoleService(pluginCfg.consoleEnabled, pluginCfg.consoleWhitelist,
                pluginCfg.consoleBlacklist, pluginCfg.consoleTimeoutMs,
                new BukkitConsoleDispatcher(this));

        connect();
    }

    private PluginConfig parseConfig() {
        var yaml = getConfig();
        PluginConfig c = new PluginConfig();
        c.host = yaml.getString("host", c.host);
        c.port = yaml.getInt("port", c.port);
        c.apiKey = yaml.getString("apiKey", c.apiKey);
        c.agentId = yaml.getString("agentId", c.agentId);
        c.sandboxRoot = PluginConfig.resolveRoot(yaml.getString("sandboxRoot", "."));
        c.execEnabled = yaml.getBoolean("exec.enabled", c.execEnabled);
        c.execTimeoutMs = yaml.getLong("exec.timeoutMs", c.execTimeoutMs);
        c.execWhitelist = yaml.getStringList("exec.whitelist");
        c.consoleEnabled = yaml.getBoolean("console.enabled", c.consoleEnabled);
        c.consoleTimeoutMs = yaml.getLong("console.timeoutMs", c.consoleTimeoutMs);
        c.consoleWhitelist = yaml.getStringList("console.whitelist");
        c.consoleBlacklist = yaml.getStringList("console.blacklist");
        if (c.consoleBlacklist == null || c.consoleBlacklist.isEmpty()) c.consoleBlacklist = PluginConfig.DEFAULT_CONSOLE_BLACKLIST;
        c.chunkSize = yaml.getInt("chunkSize", c.chunkSize);
        c.debug = yaml.getBoolean("debug", c.debug);
        return c;
    }

    private void connect() {
        group = new NioEventLoopGroup(1);
        businessGroup = new DefaultEventExecutorGroup(4);

        handler = new AgentHandler(pluginCfg, fileService, execService, consoleService,
                this::onConnected,
                this::onDisconnected
        );

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.config().setWriteBufferHighWaterMark(4 << 20);
                        int maxFrame = Math.max(pluginCfg.chunkSize * 8, 64 * 1024 * 1024);
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(maxFrame, 0, 4, 0, 4),
                                new LengthFieldPrepender(4)
                        );
                        // Run handler on business thread to avoid blocking I/O
                        ch.pipeline().addLast(businessGroup, handler);
                    }
                });

        b.connect(pluginCfg.host, pluginCfg.port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                reconnectAttempt = 0;
            } else {
                Log.error("Failed to connect to " + pluginCfg.host + ":" + pluginCfg.port);
                if (isEnabled()) scheduleReconnect();
            }
        });
    }

    private void onConnected() {
        getLogger().info("Connected to AgentBridgeServer as \"" + pluginCfg.agentId + "\"");
    }

    private void onDisconnected() {
        channel = null;
        if (isEnabled()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        reconnectAttempt++;
        int delay = Math.min(reconnectAttempt * 2, MAX_RECONNECT_DELAY);
        getLogger().info("Reconnecting in " + delay + "s (attempt " + reconnectAttempt + ")...");
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
            if (!isEnabled()) return;  // plugin disabled while waiting
            shutdownNetty();
            connect();
        }, delay * 20L);
    }

    private void shutdownNetty() {
        if (channel != null && channel.isActive()) channel.close();
        if (businessGroup != null) businessGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS);
        if (group != null) group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        shutdownNetty();
    }
}