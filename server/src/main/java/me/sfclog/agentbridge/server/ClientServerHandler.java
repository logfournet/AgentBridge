package me.sfclog.agentbridge.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.sfclog.agentbridge.common.FrameCodec;
import me.sfclog.agentbridge.common.Log;
import me.sfclog.agentbridge.common.Meta;
import me.sfclog.agentbridge.common.Ops;

import java.util.UUID;

/**
 * Handles inbound frames from an OpenCode client (MCP or CLI).
 *
 * First frame MUST be HELLO with role=client and key=clientToken.
 * Subsequent frames are requests: server decodes the meta, looks up the
 * target agent, assigns/reuses a route, and forwards the raw body to the agent.
 */
public class ClientServerHandler extends ChannelInboundHandlerAdapter {

    private final ServerConfig config;
    private final AgentRegistry registry;
    private final Router router;
    private boolean authenticated = false;

    public ClientServerHandler(ServerConfig config, AgentRegistry registry, Router router) {
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

            int op = FrameCodec.getOp(buf);

            // Server-local ops
            if (op == Ops.PING) {
                ByteBuf pong = FrameCodec.write(ctx.alloc(), Ops.PONG, UUID.randomUUID(), (Meta) null);
                ctx.writeAndFlush(pong);
                return;
            }
            if (op == Ops.AGENT_LIST) {
                UUID route = ensureRoute(buf);
                var agents = registry.listAll();
                Meta m = new Meta();
                m.agents = agents;
                m.ok = true;
                ByteBuf res = FrameCodec.write(ctx.alloc(), Ops.AGENT_LIST_RES, route, m);
                ctx.writeAndFlush(res);
                return;
            }

            // Need an agent target (meta for the first frame; route lookup for follow-ups)
            Meta meta = FrameCodec.readMeta(buf);
            UUID route = FrameCodec.getRoute(buf);
            if (route.getMostSignificantBits() == 0 && route.getLeastSignificantBits() == 0) {
                route = UUID.randomUUID();
                FrameCodec.setRoute(buf, route);
            }

            String targetAgent = (meta != null && meta.agent != null && !meta.agent.isBlank())
                    ? meta.agent
                    : router.ownerOf(route);

            if (targetAgent == null) {
                sendError(ctx, buf.alloc(), 400, "Missing 'agent' field", route);
                return;
            }

            var entry = registry.get(targetAgent);
            if (entry == null) {
                sendError(ctx, buf.alloc(), 503, "Agent \"" + targetAgent + "\" is offline", route);
                return;
            }

            router.register(route, ctx.channel(), targetAgent);

            // Forward raw body (retained) to the agent channel
            entry.channel().writeAndFlush(buf.retain());
        } finally {
            buf.release();
        }
    }

    private void handleHello(ChannelHandlerContext ctx, ByteBuf buf) {
        UUID route = FrameCodec.getRoute(buf);
        Meta meta = FrameCodec.readMeta(buf);
        if (meta == null || !"client".equals(meta.role)) {
            sendError(ctx, buf.alloc(), 400, "HELLO with role 'client' expected", route);
            ctx.close();
            return;
        }
        if (!constantTimeEq(config.clientToken, meta.key)) {
            sendError(ctx, buf.alloc(), 401, "Invalid client token", route);
            ctx.close();
            return;
        }
        authenticated = true;
        Log.info("Client authenticated from " + ctx.channel().remoteAddress());

        Meta ok = Meta.of("ok", true);
        ByteBuf okBuf = FrameCodec.write(ctx.alloc(), Ops.HELLO_OK, UUID.randomUUID(), ok);
        ctx.writeAndFlush(okBuf);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        router.dropClient(ctx.channel());
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.error("Client handler error", cause);
        ctx.close();
    }

    private static UUID ensureRoute(ByteBuf buf) {
        UUID current = FrameCodec.getRoute(buf);
        if (current.getMostSignificantBits() != 0 || current.getLeastSignificantBits() != 0) {
            return current;
        }
        UUID newRoute = UUID.randomUUID();
        FrameCodec.setRoute(buf, newRoute);
        return newRoute;
    }

    private static void sendError(ChannelHandlerContext ctx, io.netty.buffer.ByteBufAllocator alloc,
                                  int code, String msg, UUID route) {
        Meta m = Meta.of("code", code, "message", msg);
        ByteBuf buf = FrameCodec.write(alloc, Ops.ERROR, route, m);
        ctx.writeAndFlush(buf);
    }

    private static boolean constantTimeEq(String a, String b) {
        if (a == null || b == null) return a == b;
        char[] ca = a.toCharArray();
        char[] cb = b.toCharArray();
        if (ca.length != cb.length) return false;
        int result = 0;
        for (int i = 0; i < ca.length; i++) result |= ca[i] ^ cb[i];
        return result == 0;
    }
}