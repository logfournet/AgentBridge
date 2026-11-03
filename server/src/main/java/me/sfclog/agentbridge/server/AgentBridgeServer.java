package me.sfclog.agentbridge.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import me.sfclog.agentbridge.common.Log;

import java.nio.file.Path;

/**
 * AgentBridgeServer — relays opaque frames between OpenCode clients and
 * Minecraft plugins. Runs two TCP listeners:
 * <ul>
 *   <li><b>pluginPort</b> – inbound connections from Minecraft plugins</li>
 *   <li><b>clientPort</b> – inbound connections from OpenCode MCP / CLI</li>
 * </ul>
 */
public class AgentBridgeServer {

    private final ServerConfig config;
    private final AgentRegistry registry = new AgentRegistry();
    private final Router router = new Router();

    public AgentBridgeServer(ServerConfig config) {
        this.config = config;
        Log.setDebug(config.debug);
    }

    public void start() throws InterruptedException {
        NioEventLoopGroup boss   = new NioEventLoopGroup(1);
        NioEventLoopGroup worker = new NioEventLoopGroup(4);

        try {
            Log.info("Starting AgentBridgeServer on pluginPort=" + config.pluginPort
                     + " clientPort=" + config.clientPort + " ...");

            // 1. Plugin listener
            ServerBootstrap pluginBoot = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.config().setWriteBufferHighWaterMark(4 << 20);
                            int maxFrame = config.maxFrameMB * 1024 * 1024;
                            ch.pipeline().addLast(
                                    new LengthFieldBasedFrameDecoder(maxFrame, 0, 4, 0, 4),
                                    new LengthFieldPrepender(4),
                                    new AgentServerHandler(config, registry, router));
                        }
                    });

            ChannelFuture pluginFut = pluginBoot.bind(config.bindHost, config.pluginPort).sync();
            Log.info("Plugin listener bound on " + config.bindHost + ":" + config.pluginPort);

            // 2. Client listener
            ServerBootstrap clientBoot = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.config().setWriteBufferHighWaterMark(4 << 20);
                            int maxFrame = config.maxFrameMB * 1024 * 1024;
                            ch.pipeline().addLast(
                                    new LengthFieldBasedFrameDecoder(maxFrame, 0, 4, 0, 4),
                                    new LengthFieldPrepender(4),
                                    new ClientServerHandler(config, registry, router));
                        }
                    });

            ChannelFuture clientFut = clientBoot.bind(config.bindHost, config.clientPort).sync();
            Log.info("Client listener bound on " + config.bindHost + ":" + config.clientPort);

            Log.info("AgentBridgeServer is running. Press Ctrl+C to stop.");

            // Wait for either to close
            pluginFut.channel().closeFuture().sync();
            clientFut.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
            Log.info("AgentBridgeServer stopped.");
        }
    }

    public static void main(String[] args) throws Exception {
        Path configPath = ServerConfig.resolveConfigPath(args);
        ServerConfig cfg = ServerConfig.loadOrCreate(configPath);
        new AgentBridgeServer(cfg).start();
    }
}