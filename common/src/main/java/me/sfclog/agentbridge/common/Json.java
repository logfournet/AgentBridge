package me.sfclog.agentbridge.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson instance with no HTML escaping (efficient for binary-friendly JSON).
 */
public final class Json {
    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private Json() {}
}