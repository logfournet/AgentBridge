package me.sfclog.agentbridge.server;

import io.netty.channel.Channel;
import me.sfclog.agentbridge.common.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks all connected plugin agents by their id.
 */
public class AgentRegistry {

    private final ConcurrentHashMap<String, AgentEntry> agents = new ConcurrentHashMap<>();

    public AgentEntry register(String id, Channel channel, String name, String mcVersion) {
        AgentEntry old = agents.put(id, new AgentEntry(id, channel, name, mcVersion));
        if (old != null) {
            Log.info("Replaced existing agent \"" + id + "\" (old channel " + old.channel.remoteAddress() + ")");
            old.channel.close();
        }
        Log.info("Agent registered: \"" + id + "\" (" + name + "/" + mcVersion + ") at " + channel.remoteAddress());
        return old;
    }

    public AgentEntry get(String id) {
        return agents.get(id);
    }

    public void remove(String id) {
        agents.remove(id);
    }

    /**
     * Remove an agent only if the registered channel is the one disconnecting.
     * Prevents a replaced (old) connection from evicting its replacement.
     */
    public boolean removeIfMatches(String id, io.netty.channel.Channel channel) {
        AgentEntry e = agents.get(id);
        if (e != null && e.channel() == channel) {
            agents.remove(id, e);
            return true;
        }
        return false;
    }

    public Map<String, String> listAll() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        agents.forEach((id, e) -> map.put(id, e.name));
        return map;
    }

    public record AgentEntry(String id, Channel channel, String name, String mcVersion) {}
}