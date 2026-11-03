package me.sfclog.agentbridge.common;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.util.List;
import java.util.Map;

/**
 * JSON metadata carried in the fixed header of every frame.
 * Only relevant fields are set per operation (Gson omits nulls).
 */
public class Meta {

    // ---- Auth / identity ----
    @SerializedName("role")
    public String role;                    // "agent" | "client" in HELLO
    @SerializedName("key")
    public String key;                     // api key / client token
    @SerializedName("agent")
    public String agent;                   // target agent id (client requests)
    @SerializedName("name")
    public String name;                    // agent display name
    @SerializedName("mcVersion")
    public String mcVersion;

    // ---- File ops ----
    @SerializedName("path")
    public String path;
    @SerializedName("dest")
    public String dest;
    @SerializedName("offset")
    public long offset;
    @SerializedName("length")
    public long length;                    // READ length, WRITE total length
    @SerializedName("mode")
    public String mode;                    // "append" | "truncate" | "create"
    @SerializedName("recursive")
    public boolean recursive;
    @SerializedName("createDirs")
    public boolean createDirs;

    // ---- Exec ----
    @SerializedName("command")
    public String command;
    @SerializedName("cwd")
    public String cwd;
    @SerializedName("timeoutMs")
    public long timeoutMs;

    // ---- Response: list entries ----
    @SerializedName("entries")
    public List<Entry> entries;

    // ---- Response: stat / exists ----
    @SerializedName("exists")
    public boolean exists;
    @SerializedName("isDir")
    public boolean isDir;
    @SerializedName("size")
    public long size;
    @SerializedName("modified")
    public long modified;                  // epoch ms

    // ---- Response: exec ----
    @SerializedName("stdout")
    public String stdout;
    @SerializedName("stderr")
    public String stderr;
    @SerializedName("exitCode")
    public int exitCode;

    // ---- Response: agent list ----
    @SerializedName("agents")
    public Map<String, String> agents;     // id → name

    // ---- Confirm ----
    @SerializedName("ok")
    public boolean ok;
    @SerializedName("written")
    public long written;

    // ---- Error ----
    @SerializedName("code")
    public int code;
    @SerializedName("message")
    public String message;

    // ---- READ_START response meta ----
    @SerializedName("fileSize")
    public long fileSize;
    @SerializedName("served")
    public long served;

    // ---- WRITE_START meta echo ----
    // uses ok, written

    // ---------- factory helpers ----------

    public static Meta of(Object... fields) {
        Meta m = new Meta();
        for (int i = 0; i < fields.length - 1; i += 2) {
            String k = (String) fields[i];
            Object v = fields[i + 1];
            switch (k) {
                case "role" -> m.role = (String) v;
                case "key" -> m.key = (String) v;
                case "agent" -> m.agent = (String) v;
                case "name" -> m.name = (String) v;
                case "path" -> m.path = (String) v;
                case "dest" -> m.dest = (String) v;
                case "mode" -> m.mode = (String) v;
                case "command" -> m.command = (String) v;
                case "cwd" -> m.cwd = (String) v;
                case "message" -> m.message = (String) v;
                case "offset" -> m.offset = ((Number) v).longValue();
                case "length" -> m.length = ((Number) v).longValue();
                case "recursive" -> m.recursive = (Boolean) v;
                case "mkdirs", "createDirs" -> m.createDirs = (Boolean) v;
                case "ok" -> m.ok = (Boolean) v;
                case "code" -> m.code = ((Number) v).intValue();
                case "timeoutMs" -> m.timeoutMs = ((Number) v).longValue();
            }
        }
        return m;
    }
}