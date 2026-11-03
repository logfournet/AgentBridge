package me.sfclog.agentbridge.common;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiny pluggable logger (no external deps). Server/client print to stdout;
 * the Minecraft plugin installs a sink that forwards to the Paper logger.
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Sink interface — the plugin overrides this to use the Paper logger. */
    public interface Sink {
        void log(String level, String message);
    }

    private static volatile boolean debug = false;
    private static volatile Sink sink = (level, msg) ->
            System.out.println("[" + LocalTime.now().format(TS) + "] [" + level + "] " + msg);

    private Log() {}

    public static void setDebug(boolean enabled) { debug = enabled; }
    public static boolean isDebug() { return debug; }
    public static void setSink(Sink s) { if (s != null) sink = s; }

    public static void info(String msg)  { sink.log("INFO", msg); }
    public static void warn(String msg)  { sink.log("WARN", msg); }
    public static void error(String msg) { sink.log("ERROR", msg); }

    public static void debug(String msg) {
        if (debug) sink.log("DEBUG", msg);
    }

    public static void error(String msg, Throwable t) {
        sink.log("ERROR", msg + ": " + t);
        if (debug) t.printStackTrace(System.err);
    }
}