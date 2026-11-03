package me.sfclog.agentbridge.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny argument parser supporting {@code --key value} and {@code --key=value}.
 * Non-option tokens are positional.
 */
public class Args {

    public final List<String> positional = new ArrayList<>();
    public final Map<String, String> options = new LinkedHashMap<>();

    public Args(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    options.put(key.substring(0, eq), key.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    options.put(key, args[++i]);
                } else {
                    options.put(key, "true");
                }
            } else {
                positional.add(a);
            }
        }
    }

    public String opt(String key) { return options.get(key); }
    public String opt(String key, String def) { return options.getOrDefault(key, def); }
    public boolean flag(String key) { return options.containsKey(key); }
    public int intOpt(String key, int def) {
        String v = options.get(key);
        return v != null ? Integer.parseInt(v) : def;
    }
    public long longOpt(String key, long def) {
        String v = options.get(key);
        return v != null ? Long.parseLong(v) : def;
    }
}