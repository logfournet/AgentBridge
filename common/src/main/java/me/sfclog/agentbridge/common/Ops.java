package me.sfclog.agentbridge.common;

public final class Ops {
    private Ops() {}

    public static final int HELLO       = 1;
    public static final int HELLO_OK    = 2;
    public static final int ERROR       = 3;

    // --- One-shot file ops ---
    public static final int LIST        = 10;
    public static final int LIST_RES    = 11;
    public static final int STAT        = 12;
    public static final int STAT_RES    = 13;
    public static final int DELETE      = 14;
    public static final int DELETE_RES  = 15;
    public static final int MOVE        = 16;
    public static final int MOVE_RES    = 17;
    public static final int EXISTS      = 18;
    public static final int EXISTS_RES  = 19;
    public static final int MKDIR       = 20;
    public static final int MKDIR_RES   = 21;

    // --- Streaming READ (client → agent) payload = file bytes ---
    public static final int READ_START  = 40;
    public static final int READ_CHUNK  = 41;
    public static final int READ_END    = 42;

    // --- Streaming WRITE (client → agent) payload = file bytes ---
    public static final int WRITE_START = 50;
    public static final int WRITE_CHUNK = 51;
    public static final int WRITE_END   = 52;

    // --- Execution ---
    public static final int EXEC        = 60;
    public static final int EXEC_RES    = 61;

    // --- Minecraft console command (dispatched by the plugin) ---
    public static final int CONSOLE     = 62;
    public static final int CONSOLE_RES = 63;

    // --- Heartbeat ---
    public static final int PING        = 70;
    public static final int PONG        = 71;

    // --- Agent list from server ---
    public static final int AGENT_LIST      = 80;
    public static final int AGENT_LIST_RES  = 81;

    public static boolean isOneShotResponse(int op) {
        return op == LIST_RES || op == STAT_RES || op == DELETE_RES
            || op == MOVE_RES || op == EXISTS_RES || op == MKDIR_RES
            || op == EXEC_RES || op == ERROR || op == HELLO_OK
            || op == CONSOLE_RES
            || op == AGENT_LIST_RES;
    }

    public static boolean isStreamTerminal(int op) {
        return op == READ_END || op == WRITE_END || op == ERROR;
    }
}
