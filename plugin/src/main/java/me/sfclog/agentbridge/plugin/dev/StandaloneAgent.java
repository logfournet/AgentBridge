package me.sfclog.agentbridge.plugin.dev;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import me.sfclog.agentbridge.common.Log;
import me.sfclog.agentbridge.plugin.AgentHandler;
import me.sfclog.agentbridge.plugin.ExecService;
import me.sfclog.agentbridge.plugin.FileService;
import me.sfclog.agentbridge.plugin.PluginConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Development harness: runs the real {@link AgentHandler} against a real
 * AgentBridgeServer without Paper. Excluded from the shipped plugin jar.
 *
 * <pre>
 *   java -cp "plugin\target\classes;&lt;deps&gt;" me.sfclog.agentbridge.plugin.dev.StandaloneAgent [host] [port] [root]
 * </pre>
 */
public final class StandaloneAgent {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7000;
        Path root = Paths.get(args.length > 2 ? args[2] : "run/testroot").toAbsolutePath().normalize();
        Files.createDirectories(root);

        PluginConfig cfg = new PluginConfig();
        cfg.host = host;
        cfg.port = port;
        cfg.apiKey = "change-me-plugin-key";
        cfg.agentId = "test";
        cfg.sandboxRoot = root;
        cfg.execEnabled = true;
        cfg.execWhitelist = List.of("echo", "cmd", "dir", "type", "java", "git");
        cfg.chunkSize = 262144;
        cfg.debug = false;

        FileService fs = new FileService(root);
        ExecService es = new ExecService(true, 15000, cfg.execWhitelist, root);

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        DefaultEventExecutorGroup business = new DefaultEventExecutorGroup(4);

        AgentHandler handler = new AgentHandler(cfg, fs, es,
                () -> Log.info("[StandaloneAgent] connected"),
                () -> Log.info("[StandaloneAgent] disconnected"));

        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.config().setWriteBufferHighWaterMark(4 << 20);
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(64 << 20, 0, 4, 0, 4),
                                new LengthFieldPrepender(4));
                        ch.pipeline().addLast(business, handler);
                    }
                });

        Log.info("StandaloneAgent connecting to " + host + ":" + port + " root=" + root);
        b.connect(host, port).sync().channel().closeFuture().sync();
        group.shutdownGracefully();
        business.shutdownGracefully();
    }

    private StandaloneAgent() {}
}
