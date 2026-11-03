package me.sfclog.agentbridge.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.sfclog.agentbridge.common.FrameCodec;
import me.sfclog.agentbridge.common.Log;
import me.sfclog.agentbridge.common.Meta;
import me.sfclog.agentbridge.common.Ops;

import java.util.UUID;

/**
 * Handles inbound frames from a Minecraft plugin (agent) connection.
 *
 * First frame MUST be HELLO. Subsequent frames are responses
 * (or unsolicited PING) that are forwarded to the correct client via Router.
 */
public class AgentServerHandler extends ChannelInboundHandlerAdapter {

    private final ServerConfig config;
    private final AgentRegistry registry;
    private final Router router;
    private String agentId = null;
    private boolean authenticated = false;

    public AgentServerHandler(ServerConfig config, AgentRegistry registry, Router router) {
        this.config = config;
        this.registry = registry;
        this.router = router;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            if (!authenticated) {
                handleHello(ctx, buf);
                return;
            }

            // Normal relay frame — forward to client
            UUID route = FrameCodec.getRoute(buf);
            if (isAllZero(route)) {
                Log.warn("[" + agentId + "] agent frame with zero route, dropping");
                return;
            }
            io.netty.channel.Channel client = router.findClient(route);
            if (client == null || !client.isActive()) {
                // Client gone, terminal op → clean route
                if (Ops.isStreamTerminal(buf.getInt(1)) || Ops.isOneShotResponse(buf.getInt(1))) {
                    router.remove(route);
                }
                return;
            }
            // Forward the raw body buffer (retained, decoder releases ours)
            client.writeAndFlush(buf.retain());

            // If terminal response, clean route
            int op = FrameCodec.getOp(buf);
            if (Ops.isStreamTerminal(op) || Ops.isOneShotResponse(op)) {
                router.remove(route);
            }
        } finally {
            buf.release();
        }
    }

    private void handleHello(ChannelHandlerContext ctx, ByteBuf buf) {
        Meta meta = FrameCodec.readMeta(buf);
        if (meta == null || !"agent".equals(meta.role)) {
            sendError(ctx, 400, "HELLO with role 'agent' expected", buf.alloc());
            ctx.close();
            return;
        }

        // Check api key
        if (!constantTimeEq(config.pluginApiKey, meta.key)) {
            sendError(ctx, 401, "Invalid plugin api key", buf.alloc());
            ctx.close();
            return;
        }

        String id = meta.agent; // agent id is from the HELLO
        if (id == null || id.isBlank()) {
            sendError(ctx, 400, "HELLO must include 'agent' id", buf.alloc());
            ctx.close();
            return;
        }

        // Check allowed list
        if (!config.allowedAgents.isEmpty() && !config.allowedAgents.contains(id)) {
            sendError(ctx, 403, "Agent id \"" + id + "\" not in allowedAgents", buf.alloc());
            ctx.close();
            return;
        }

        agentId = id;
        authenticated = true;

        String name = meta.name != null ? meta.name : id;
        String mcVersion = meta.mcVersion != null ? meta.mcVersion : "?";
        registry.register(id, ctx.channel(), name, mcVersion);

        // Send HELLO_OK
        Meta ok = Meta.of("role", "server", "ok", true);
        ByteBuf okBuf = FrameCodec.write(ctx.alloc(), Ops.HELLO_OK, UUID.randomUUID(), ok);
        ctx.writeAndFlush(okBuf);

        Log.info("Agent \"" + id + "\" authenticated from " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (agentId != null) {
            // Only clean up if this channel is still the registered one
            if (registry.removeIfMatches(agentId, ctx.channel())) {
                Log.warn("Agent \"" + agentId + "\" disconnected");
                router.dropAgent(agentId);
            }
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.error("[" + (agentId != null ? agentId : "?") + "] handler error", cause);
        ctx.close();
    }

    private static void sendError(ChannelHandlerContext ctx, int code, String msg, ByteBufAllocator alloc) {
        Meta m = Meta.of("code", code, "message", msg);
        ByteBuf buf = FrameCodec.write(alloc, Ops.ERROR, UUID.randomUUID(), m);
        ctx.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
    }

    private static boolean isAllZero(UUID uuid) {
        return uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0;
    }

    private static boolean constantTimeEq(String a, String b) {
        if (a == null || b == null) return a == b;
        // Use native isEqual for constant-time comparison
        char[] ca = a.toCharArray();
        char[] cb = b.toCharArray();
        if (ca.length != cb.length) return false;
        int result = 0;
        for (int i = 0; i < ca.length; i++) result |= ca[i] ^ cb[i];
        return result == 0;
    }
}