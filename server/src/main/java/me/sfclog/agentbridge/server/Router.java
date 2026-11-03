package me.sfclog.agentbridge.server;

import io.netty.channel.Channel;
import me.sfclog.agentbridge.common.FrameCodec;
import me.sfclog.agentbridge.common.Log;
import me.sfclog.agentbridge.common.Meta;
import me.sfclog.agentbridge.common.Ops;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps route tokens to client channels for relaying agent responses back.
 * Also tracks which agent a route belongs to for cleanup.
 */
public class Router {

    /** route → client channel. */
    private final ConcurrentHashMap<UUID, Channel> clients = new ConcurrentHashMap<>();

    /** route → agent id. Used for cleanup when agent disconnects. */
    private final ConcurrentHashMap<UUID, String> owners = new ConcurrentHashMap<>();

    /**
     * Register a route destined for an agent.
     */
    public UUID register(UUID route, Channel clientChannel, String agentId) {
        clients.put(route, clientChannel);
        owners.put(route, agentId);
        return route;
    }

    /**
     * Look up the client channel for an agent response.
     */
    public Channel findClient(UUID route) {
        return clients.get(route);
    }

    /**
     * Look up the agent that owns a route (used to route follow-up chunks
     * such as WRITE_CHUNK / WRITE_END that carry no meta).
     */
    public String ownerOf(UUID route) {
        return owners.get(route);
    }

    /**
     * Remove a single completed route.
     */
    public void remove(UUID route) {
        clients.remove(route);
        owners.remove(route);
    }

    /**
     * Close all routes belonging to an agent, sending ERROR to each client.
     */
    public void dropAgent(String agentId) {
        Log.warn("Dropping routes for agent \"" + agentId + "\"");
        owners.entrySet().removeIf(e -> {
            if (!agentId.equals(e.getValue())) return false;
            UUID route = e.getKey();
            Channel ch = clients.remove(route);
            if (ch != null && ch.isActive()) {
                var errorBuf = FrameCodec.write(
                        ch.alloc(), Ops.ERROR, route,
                        Meta.of("message", "Agent \"" + agentId + "\" disconnected", "code", 503));
                ch.writeAndFlush(errorBuf);
            }
            return true;
        });
    }

    /**
     * Remove all routes belonging to a client channel (when client disconnects).
     */
    public void dropClient(Channel clientChannel) {
        clients.entrySet().removeIf(e -> {
            if (e.getValue() != clientChannel) return false;
            owners.remove(e.getKey());
            return true;
        });
    }

    public int size() { return owners.size(); }
}