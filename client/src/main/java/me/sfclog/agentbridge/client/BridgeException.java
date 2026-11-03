package me.sfclog.agentbridge.client;

import java.io.IOException;

/**
 * Exception carrying a server-side error code when an agent returns ERROR.
 */
public class BridgeException extends IOException {
    public final int code;

    public BridgeException(int code, String message) {
        super(message);
        this.code = code;
    }
}