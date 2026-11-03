package me.sfclog.agentbridge.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.sfclog.agentbridge.common.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * AgentBridgeServer configuration, loaded from {@code server.json}.
 */
public class ServerConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String bindHost = "0.0.0.0";
    public int pluginPort = 7000;
    public int clientPort = 7001;
    public String pluginApiKey = "change-me-plugin-key";
    public String clientToken = "change-me-client-token";
    public List<String> allowedAgents = List.of();  // empty = allow all
    public int maxFrameMB = 256;
    public int chunkSize = 262144; // 256 KiB
    public boolean debug = false;

    public static ServerConfig loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            ServerConfig def = new ServerConfig();
            write(file, def);
            Log.info("Created default config at " + file.toAbsolutePath());
            return def;
        }
        return GSON.fromJson(Files.readString(file), ServerConfig.class);
    }

    public static void write(Path file, ServerConfig cfg) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(cfg));
    }

    public static Path resolveConfigPath(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i]) || "-c".equals(args[i]))
                return Paths.get(args[i + 1]);
        }
        return Paths.get("server.json");
    }
}