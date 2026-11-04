package me.sfclog.agentbridge.client;

import com.google.gson.*;
import me.sfclog.agentbridge.common.Entry;
import me.sfclog.agentbridge.common.Log;
import me.sfclog.agentbridge.common.Meta;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP (Model Context Protocol) stdio server.
 *
 * <p>Reads newline-delimited JSON-RPC 2.0 on stdin and writes responses on
 * stdout. All diagnostics go to stderr so they never corrupt the channel.</p>
 */
public class BridgeMcpServer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final ClientConfig cfg;
    private final String defaultAgent;
    private volatile RemoteBridge bridge;

    /** Maximum bytes returned as UTF-8 text (larger files must use base64 / download). */
    private static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;

    public BridgeMcpServer(ClientConfig cfg) {
        this.cfg = cfg;
        this.defaultAgent = cfg.defaultAgent;
    }

    public static void main(String[] argv) {
        // Never write diagnostics to stdout — it is the MCP channel.
        Log.setSink((level, msg) -> System.err.println("[AgentBridge:" + level + "] " + msg));

        Args args = new Args(stripCommand(argv, "mcp"));
        ClientConfig cfg;
        try {
            Path cfgPath = Paths.get(args.opt("config", "client.json"));
            cfg = ClientConfig.loadOrCreate(cfgPath);
            if (args.opt("host") != null) cfg.host = args.opt("host");
            if (args.opt("port") != null) cfg.port = args.intOpt("port", cfg.port);
            if (args.opt("token") != null) cfg.token = args.opt("token");
            if (args.opt("agent") != null) cfg.defaultAgent = args.opt("agent");
            if (args.opt("debug") != null) Log.setDebug(true);
        } catch (Exception e) {
            System.err.println("Failed to load client config: " + e.getMessage());
            cfg = new ClientConfig();
        }

        new BridgeMcpServer(cfg).serve();
    }

    /** Remove the leading "mcp" token from argv so Args parsing stays clean. */
    private static String[] stripCommand(String[] argv, String command) {
        for (int i = 0; i < argv.length; i++) {
            if (command.equalsIgnoreCase(argv[i])) {
                String[] out = new String[argv.length - 1];
                System.arraycopy(argv, 0, out, 0, i);
                System.arraycopy(argv, i + 1, out, i, argv.length - i - 1);
                return out;
            }
        }
        return argv;
    }

    // ------------------------------------------------------------------
    // Main loop
    // ------------------------------------------------------------------

    public void serve() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Writer out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        Log.info("MCP server ready (host=" + cfg.host + ":" + cfg.port + ", agent=" + defaultAgent + ")");
        tryConnect();
        startParentWatchdog();

        // Handle requests concurrently: a slow tool call (big upload, console
        // command, slow host) must never freeze the whole MCP session.
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "mcp-worker");
            t.setDaemon(true);
            return t;
        });
        Object writeLock = new Object();

        String line;
        try {
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                final String payload = line;
                pool.submit(() -> {
                    JsonObject response;
                    try {
                        JsonObject request = JsonParser.parseString(payload).getAsJsonObject();
                        response = handleRequest(request);
                    } catch (Exception e) {
                        Log.error("MCP request error: " + e.getMessage());
                        response = errorResponse(null, -32700, "Parse error: " + e.getMessage());
                    }
                    if (response == null) return;
                    try {
                        synchronized (writeLock) {
                            out.write(GSON.toJson(response));
                            out.write('\n');
                            out.flush();
                        }
                    } catch (IOException e) {
                        Log.error("MCP stdout write failed: " + e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            Log.error("MCP stdin read failed", e);
        } finally {
            pool.shutdown();
            if (bridge != null) bridge.close();
        }
    }

    // ------------------------------------------------------------------
    // JSON-RPC dispatch
    // ------------------------------------------------------------------

    private JsonObject handleRequest(JsonObject req) {
        String method = req.has("method") ? req.get("method").getAsString() : null;
        JsonElement idEl = req.get("id");
        boolean isNotification = idEl == null || idEl.isJsonNull();

        if (method == null) return errorResponse(idEl, -32600, "Missing method");

        switch (method) {
            case "initialize":
                return success(idEl, initializeResult(req));
            case "notifications/initialized":
                return null; // notification, no response
            case "ping":
                return success(idEl, new JsonObject());
            case "tools/list":
                return success(idEl, toolsList());
            case "tools/call":
                return handleToolCall(idEl, req);
            case "shutdown":
                return success(idEl, new JsonObject());
            case "exit":
                System.err.println("MCP exit");
                if (bridge != null) bridge.close();
                return null;
            default:
                if (isNotification) return null;
                return errorResponse(idEl, -32601, "Method not found: " + method);
        }
    }

    private JsonObject initializeResult(JsonObject req) {
        String clientVersion = "2024-11-05";
        try {
            if (req.has("params") && req.getAsJsonObject("params").has("protocolVersion")) {
                clientVersion = req.getAsJsonObject("params").get("protocolVersion").getAsString();
            }
        } catch (Exception ignored) {}

        JsonObject caps = new JsonObject();
        JsonObject tools = new JsonObject();
        caps.add("tools", tools);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "agentbridge");
        serverInfo.addProperty("version", "1.0.0");

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", clientVersion);
        result.add("capabilities", caps);
        result.add("serverInfo", serverInfo);
        return result;
    }

    // ------------------------------------------------------------------
    // Tools
    // ------------------------------------------------------------------

    private static final String TOOLS_JSON = """
            {
              "tools": [
                {
                  "name": "agents",
                  "description": "List connected AgentBridge agents (Minecraft servers).",
                  "inputSchema": { "type": "object", "properties": {}, "additionalProperties": false }
                },
                {
                  "name": "read_file",
                  "description": "Read a file from the Minecraft host. Returns UTF-8 text by default; use encoding=base64 for binary.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string", "description": "Target agent id (defaults to configured agent)" },
                      "path": { "type": "string", "description": "Path relative to the agent sandbox root" },
                      "offset": { "type": "integer", "description": "Byte offset to start reading" },
                      "length": { "type": "integer", "description": "Number of bytes to read (0 = to EOF)" },
                      "encoding": { "type": "string", "enum": ["utf8", "base64"], "description": "Output encoding (default utf8)" }
                    },
                    "required": ["path"]
                  }
                },
                {
                  "name": "write_file",
                  "description": "Write text or base64 content to a file on the Minecraft host, replacing or appending.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "path": { "type": "string" },
                      "content": { "type": "string", "description": "File content" },
                      "encoding": { "type": "string", "enum": ["utf8", "base64"], "description": "Content encoding (default utf8)" },
                      "mode": { "type": "string", "enum": ["truncate", "append"], "description": "Write mode (default truncate)" },
                      "createDirs": { "type": "boolean", "description": "Create parent directories" }
                    },
                    "required": ["path", "content"]
                  }
                },
                {
                  "name": "edit_file",
                  "description": "Replace exact text occurrences in a remote file (read-modify-write).",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "path": { "type": "string" },
                      "old_string": { "type": "string" },
                      "new_string": { "type": "string" },
                      "replace_all": { "type": "boolean", "description": "Replace every occurrence (default false)" }
                    },
                    "required": ["path", "old_string", "new_string"]
                  }
                },
                {
                  "name": "list_dir",
                  "description": "List directory entries on the Minecraft host.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "path": { "type": "string", "description": "Directory path (default root)" }
                    }
                  }
                },
                {
                  "name": "stat",
                  "description": "Get type, size and modification time of a remote path.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "path": { "type": "string" }
                    },
                    "required": ["path"]
                  }
                },
                {
                  "name": "delete",
                  "description": "Delete a file or directory on the Minecraft host.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "path": { "type": "string" },
                      "recursive": { "type": "boolean" }
                    },
                    "required": ["path"]
                  }
                },
                {
                  "name": "move",
                  "description": "Move/rename a remote file or directory.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "src": { "type": "string" },
                      "dst": { "type": "string" }
                    },
                    "required": ["src", "dst"]
                  }
                },
                {
                  "name": "mkdir",
                  "description": "Create a directory on the Minecraft host.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "path": { "type": "string" },
                      "parents": { "type": "boolean", "description": "Create missing parents" }
                    },
                    "required": ["path"]
                  }
                },
                {
                  "name": "exec",
                  "description": "Run a shell command on the Minecraft host (if enabled + whitelisted).",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "command": { "type": "string" },
                      "cwd": { "type": "string" },
                      "timeoutMs": { "type": "integer" }
                    },
                    "required": ["command"]
                  }
                },
                {
                  "name": "console",
                  "description": "Run a Minecraft server console command (e.g. 'say hi', 'list', 'tps') and capture its output. Requires console.enabled on the plugin.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "command": { "type": "string" },
                      "timeoutMs": { "type": "integer" }
                    },
                    "required": ["command"]
                  }
                },
                {
                  "name": "download",
                  "description": "Download a remote file to a local path where the MCP server runs.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "remotePath": { "type": "string" },
                      "localPath": { "type": "string" }
                    },
                    "required": ["remotePath", "localPath"]
                  }
                },
                {
                  "name": "upload",
                  "description": "Upload a local file (where MCP runs) to the Minecraft host.",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "agent": { "type": "string" },
                      "localPath": { "type": "string" },
                      "remotePath": { "type": "string" },
                      "mode": { "type": "string", "enum": ["truncate", "append"] },
                      "createDirs": { "type": "boolean" }
                    },
                    "required": ["localPath", "remotePath"]
                  }
                }
              ]
            }
            """;

    private JsonObject toolsList() {
        return JsonParser.parseString(TOOLS_JSON).getAsJsonObject();
    }

    private JsonObject handleToolCall(JsonElement id, JsonObject req) {
        String name = null;
        JsonObject arguments = new JsonObject();
        try {
            JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
            name = params.has("name") ? params.get("name").getAsString() : null;
            if (params.has("arguments") && params.get("arguments").isJsonObject()) {
                arguments = params.getAsJsonObject("arguments");
            }
            if (name == null) return errorResponse(id, -32602, "Missing tool name");

            ensureConnected();
            String text = callTool(name, arguments);
            return success(id, toolResult(text, false));
        } catch (BridgeException e) {
            return success(id, toolResult("Error [" + e.code + "]: " + e.getMessage(), true));
        } catch (Exception e) {
            return success(id, toolResult("Error: " + e.getMessage(), true));
        }
    }

    private String callTool(String name, JsonObject a) throws Exception {
        String agent = optStr(a, "agent", defaultAgent);
        switch (name) {
            case "agents": {
                Meta m = bridge.agents();
                if (m.agents == null || m.agents.isEmpty()) return "(no agents connected)";
                StringBuilder sb = new StringBuilder();
                m.agents.forEach((id, n) -> sb.append(id).append("  ->  ").append(n).append('\n'));
                return sb.toString();
            }
            case "read_file": {
                String path = reqStr(a, "path");
                long offset = optLong(a, "offset", 0);
                long length = optLong(a, "length", 0);
                String encoding = optStr(a, "encoding", "utf8");

                Path tmp = Files.createTempFile("bridge-read-", ".bin");
                try {
                    Meta m = bridge.readToFile(agent, path, offset, length, tmp);
                    long size = Files.size(tmp);
                    if ("base64".equalsIgnoreCase(encoding)) {
                        byte[] data = Files.readAllBytes(tmp);
                        return Base64.getEncoder().encodeToString(data);
                    }
                    if (size > MAX_TEXT_BYTES)
                        throw new IOException("File is " + size + " bytes; use encoding=base64 or download instead");
                    return Files.readString(tmp, StandardCharsets.UTF_8);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            case "write_file": {
                String path = reqStr(a, "path");
                String content = reqStr(a, "content");
                String encoding = optStr(a, "encoding", "utf8");
                String mode = optStr(a, "mode", "truncate");
                boolean mkdirs = optBool(a, "createDirs", true);

                byte[] data = "base64".equalsIgnoreCase(encoding)
                        ? Base64.getDecoder().decode(content)
                        : content.getBytes(StandardCharsets.UTF_8);

                Meta m = bridge.writeBytes(agent, path, data, mode, mkdirs);
                return "Wrote " + data.length + " bytes to " + path +
                        (m.written > 0 ? " (confirmed " + m.written + ")" : "");
            }
            case "edit_file": {
                String path = reqStr(a, "path");
                String oldStr = reqStr(a, "old_string");
                String newStr = reqStr(a, "new_string");
                boolean all = optBool(a, "replace_all", false);

                int n = bridge.editFile(agent, path, oldStr, newStr, all);
                return "Edited " + path + ": " + n + " replacement(s)";
            }
            case "list_dir": {
                String path = optStr(a, "path", "");
                Meta m = bridge.list(agent, path);
                if (m.entries == null || m.entries.isEmpty()) return "(empty)";
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                StringBuilder sb = new StringBuilder();
                for (Entry e : m.entries) {
                    sb.append(e.dir ? "DIR  " : "FILE ")
                      .append(String.format("%12d", e.dir ? 0 : e.size))
                      .append("  ")
                      .append(fmt.format(new Date(e.modified)))
                      .append("  ")
                      .append(e.name)
                      .append(e.dir ? "/" : "")
                      .append('\n');
                }
                return sb.toString();
            }
            case "stat": {
                String path = reqStr(a, "path");
                Meta m = bridge.stat(agent, path);
                if (!m.exists) return "not found: " + path;
                return "type: " + (m.isDir ? "directory" : "file") +
                        "\nsize: " + m.size +
                        "\nmodified: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(m.modified));
            }
            case "delete": {
                String path = reqStr(a, "path");
                boolean recursive = optBool(a, "recursive", false);
                bridge.delete(agent, path, recursive);
                return "Deleted " + path;
            }
            case "move": {
                String src = reqStr(a, "src");
                String dst = reqStr(a, "dst");
                bridge.move(agent, src, dst);
                return "Moved " + src + " -> " + dst;
            }
            case "mkdir": {
                String path = reqStr(a, "path");
                boolean parents = optBool(a, "parents", true);
                bridge.mkdir(agent, path, parents);
                return "Created " + path;
            }
            case "exec": {
                String command = reqStr(a, "command");
                String cwd = optStr(a, "cwd", null);
                long timeout = optLong(a, "timeoutMs", 0);
                Meta m = bridge.exec(agent, command, cwd, timeout);
                StringBuilder sb = new StringBuilder();
                sb.append("exit: ").append(m.exitCode).append('\n');
                if (m.stdout != null && !m.stdout.isEmpty()) sb.append("--- stdout ---\n").append(m.stdout).append('\n');
                if (m.stderr != null && !m.stderr.isEmpty()) sb.append("--- stderr ---\n").append(m.stderr).append('\n');
                return sb.toString();
            }
            case "console": {
                String command = reqStr(a, "command");
                long timeout = optLong(a, "timeoutMs", 0);
                Meta m = bridge.console(agent, command, timeout);
                StringBuilder sb = new StringBuilder();
                sb.append("exit: ").append(m.exitCode).append('\n');
                if (m.stdout != null && !m.stdout.isEmpty()) sb.append(m.stdout).append('\n');
                if (m.stderr != null && !m.stderr.isEmpty()) sb.append("[error] ").append(m.stderr).append('\n');
                return sb.toString();
            }
            case "download": {
                String remote = reqStr(a, "remotePath");
                String local = reqStr(a, "localPath");
                Path localPath = cfg.resolveDownload(local);
                bridge.readToFile(agent, remote, 0, 0, localPath);
                return "Downloaded " + remote + " -> " + localPath + " (" + Files.size(localPath) + " bytes)";
            }
            case "upload": {
                String local = reqStr(a, "localPath");
                String remote = reqStr(a, "remotePath");
                String mode = optStr(a, "mode", "truncate");
                boolean mkdirs = optBool(a, "createDirs", true);
                Meta m = bridge.writeFromFile(agent, remote, Paths.get(local), mode, mkdirs);
                return "Uploaded " + local + " -> " + remote + " (" + Files.size(Paths.get(local)) + " bytes)";
            }
            default:
                throw new IOException("Unknown tool: " + name);
        }
    }

    // ------------------------------------------------------------------
    // Connection management
    // ------------------------------------------------------------------

    private synchronized void ensureConnected() throws Exception {
        if (bridge != null && bridge.isActive()) return;
        if (bridge != null) {
            try { bridge.close(); } catch (Exception ignored) {}
        }
        bridge = new RemoteBridge(cfg);
        bridge.connect();
    }

    /**
     * Exits when the parent process (the editor that spawned us) disappears, so
     * closed OpenCode windows don't leave orphan MCP servers behind holding the
     * jar file and RAM. Two consecutive checks are required to avoid races.
     */
    private void startParentWatchdog() {
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        if (parent == null) return;
        Thread t = new Thread(() -> {
            int misses = 0;
            while (true) {
                try {
                    Thread.sleep(15_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (parent.isAlive()) {
                    misses = 0;
                    continue;
                }
                if (++misses >= 2) {
                    Log.warn("Parent process is gone; shutting down MCP server");
                    System.exit(0);
                }
            }
        }, "agentbridge-parent-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private void tryConnect() {        try {
            ensureConnected();
            Log.info("Connected to AgentBridgeServer at " + cfg.host + ":" + cfg.port);
        } catch (Exception e) {
            Log.warn("Not connected yet (" + e.getMessage() + "); will retry on demand");
        }
    }

    // ------------------------------------------------------------------
    // Result helpers
    // ------------------------------------------------------------------

    private static JsonObject toolResult(String text, boolean isError) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);

        JsonArray arr = new JsonArray();
        arr.add(content);

        JsonObject result = new JsonObject();
        result.add("content", arr);
        result.addProperty("isError", isError);
        return result;
    }

    private static JsonObject success(JsonElement id, JsonElement result) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null) resp.add("id", id);
        resp.add("result", result);
        return resp;
    }

    private static JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);

        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");
        if (id != null) resp.add("id", id);
        resp.add("error", err);
        return resp;
    }

    private static String reqStr(JsonObject o, String key) throws IOException {
        if (!o.has(key) || o.get(key).isJsonNull())
            throw new IOException("Missing required argument: " + key);
        return o.get(key).getAsString();
    }

    private static String optStr(JsonObject o, String key, String def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsString();
    }

    private static long optLong(JsonObject o, String key, long def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsLong();
    }

    private static boolean optBool(JsonObject o, String key, boolean def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsBoolean();
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}