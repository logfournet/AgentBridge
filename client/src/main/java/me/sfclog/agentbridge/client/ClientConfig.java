package me.sfclog.agentbridge.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.sfclog.agentbridge.common.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * OpenCode client configuration ({@code client.json}).
 */
public class ClientConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String host = "127.0.0.1";
    public int port = 7001;
    public String token = "change-me-client-token";
    public String defaultAgent = "main";
    public String downloadDir = ".";
    public int connectTimeoutMs = 10000;
    public int chunkSize = 262144;

    public static ClientConfig loadOrCreate(Path file) throws IOException {
        if (!Files.exists(file)) {
            ClientConfig def = new ClientConfig();
            write(file, def);
            Log.info("Created default config at " + file.toAbsolutePath());
            return def;
        }
        return GSON.fromJson(Files.readString(file), ClientConfig.class);
    }

    public static void write(Path file, ClientConfig cfg) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(cfg));
    }

    public Path resolveDownload(String p) {
        Path path = Paths.get(p);
        if (path.isAbsolute()) return path;
        return Paths.get(downloadDir).toAbsolutePath().resolve(path).normalize();
    }
}