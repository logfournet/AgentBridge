package me.sfclog.agentbridge.plugin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import me.sfclog.agentbridge.common.*;
import me.sfclog.agentbridge.common.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty handler for the plugin's connection to AgentBridgeServer.
 * Processes incoming frames (file I/O and exec) and sends responses back.
 */
public class AgentHandler extends ChannelInboundHandlerAdapter {

    private final PluginConfig pluginCfg;
    private final FileService fileService;
    private final ExecService execService;
    private final ConsoleService consoleService;
    private final Runnable onConnected;
    private final Runnable onDisconnected;

    /** Active read streams: route → ReadState. */
    private final ConcurrentHashMap<UUID, ReadState> readStates = new ConcurrentHashMap<>();
    /** Active write streams: route → WriteState. */
    private final ConcurrentHashMap<UUID, WriteState> writeStates = new ConcurrentHashMap<>();

    private volatile Channel channel;
    private volatile boolean authenticated = false;
    private volatile boolean connected = false;

    public AgentHandler(PluginConfig cfg, FileService fileService, ExecService execService,
                        ConsoleService consoleService,
                        Runnable onConnected, Runnable onDisconnected) {
        this.pluginCfg = cfg;
        this.fileService = fileService;
        this.execService = execService;
        this.consoleService = consoleService;
        this.onConnected = onConnected;
        this.onDisconnected = onDisconnected;
    }

    public boolean isConnected() { return connected; }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        channel = ctx.channel();
        // Send HELLO
        Meta hello = Meta.of(
                "role", "agent",
                "key", pluginCfg.apiKey,
                "agent", pluginCfg.agentId,
                "name", "AgentBridge",
                "mcVersion", "1.21"
        );
        ByteBuf buf = FrameCodec.write(ctx.alloc(), Ops.HELLO, UUID.randomUUID(), hello);
        ctx.writeAndFlush(buf);
        Log.info("Sent HELLO to server");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        try {
            if (!buf.isReadable()) return;
            int op = FrameCodec.getOp(buf);
            UUID route = FrameCodec.getRoute(buf);
            Meta meta = FrameCodec.readMeta(buf);

            Log.debug("Agent recv op=" + op + " route=" + route + " path=" + (meta != null ? meta.path : ""));

            switch (op) {
                case Ops.HELLO_OK -> {
                    authenticated = true;
                    connected = true;
                    if (onConnected != null) onConnected.run();
                    Log.info("Authenticated with server as \"" + pluginCfg.agentId + "\"");
                }
                case Ops.PING -> {
                    ByteBuf pong = FrameCodec.write(ctx.alloc(), Ops.PONG, route, (Meta) null);
                    ctx.writeAndFlush(pong);
                }
                case Ops.ERROR -> {
                    Log.error("Server error: " + (meta != null ? meta.message : "?"));
                    // Clean any stream for this route
                    cleanup(route);
                }

                // ---- One-shot file ops ----
                case Ops.LIST       -> respond(ctx, Ops.LIST_RES,       route, safe(() -> fileService.list(meta.path)));
                case Ops.STAT       -> respond(ctx, Ops.STAT_RES,       route, safe(() -> fileService.stat(meta.path)));
                case Ops.DELETE     -> respond(ctx, Ops.DELETE_RES,     route, safe(() -> fileService.delete(meta.path, meta.recursive)));
                case Ops.MOVE       -> respond(ctx, Ops.MOVE_RES,       route, safe(() -> fileService.move(meta.path, meta.dest)));
                case Ops.EXISTS     -> respond(ctx, Ops.EXISTS_RES,     route, safe(() -> fileService.exists(meta.path)));
                case Ops.MKDIR      -> respond(ctx, Ops.MKDIR_RES,      route, safe(() -> fileService.mkdir(meta.path, meta.createDirs)));
                case Ops.EXEC       -> respond(ctx, Ops.EXEC_RES,       route, safe(() -> execService.execute(meta.command, meta.cwd, meta.timeoutMs)));
                case Ops.CONSOLE    -> respond(ctx, Ops.CONSOLE_RES,    route, safe(() -> {
                    if (consoleService == null) {
                        Meta m = new Meta();
                        m.exitCode = -1;
                        m.stderr = "Console is not available on this agent";
                        return m;
                    }
                    return consoleService.execute(meta.command, meta.timeoutMs);
                }));

                // ---- READ stream (agent streams data back to server) ----
                case Ops.READ_START -> {
                    try {
                        handleReadStart(ctx, route, meta);
                    } catch (Exception e) {
                        respond(ctx, Ops.ERROR, route, errMeta(e));
                    }
                }
                case Ops.READ_CHUNK -> { /* client doesn't send chunks for read */ }
                case Ops.READ_END   -> cleanup(route);

                // ---- WRITE stream (agent receives data from server) ----
                case Ops.WRITE_START -> {
                    try {
                        handleWriteStart(ctx, route, meta, buf);
                    } catch (Exception e) {
                        cleanup(route);
                        respond(ctx, Ops.ERROR, route, errMeta(e));
                    }
                }
                case Ops.WRITE_CHUNK -> {
                    try {
                        handleWriteChunk(route, buf);
                    } catch (Exception e) {
                        cleanup(route);
                        respond(ctx, Ops.ERROR, route, errMeta(e));
                    }
                }
                case Ops.WRITE_END -> {
                    try {
                        handleWriteEnd(ctx, route);
                    } catch (Exception e) {
                        cleanup(route);
                        respond(ctx, Ops.ERROR, route, errMeta(e));
                    }
                }

                default -> Log.warn("Unknown op " + op);
            }
        } catch (Exception e) {
            Log.error("Handler error processing frame", e);
        } finally {
            buf.release();
        }
    }

    // ---------- READ streaming ----------

    private void handleReadStart(ChannelHandlerContext ctx, UUID route, Meta meta) throws Exception {
        Path filePath = fileService.resolveFile(meta.path);
        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            respond(ctx, Ops.ERROR, route, Meta.of("code", 404, "message", "File not found or is directory"));
            return;
        }
        FileChannel fc = FileChannel.open(filePath, StandardOpenOption.READ);
        long fileSize = fc.size();

        long offset = Math.max(0, meta.offset);
        if (offset > fileSize) offset = fileSize;
        long available = fileSize - offset;
        long length = meta.length <= 0 ? available : Math.min(meta.length, available);
        fc.position(offset);

        ReadState state = new ReadState(fc, fileSize, length);
        readStates.put(route, state);

        // Send start confirmation
        Meta startMeta = Meta.of("ok", true, "fileSize", fileSize, "length", length);
        ByteBuf startBuf = FrameCodec.write(ctx.alloc(), Ops.READ_START, route, startMeta);
        ctx.write(startBuf); // don't flush yet

        // Stream chunks synchronously (on executor group thread, not I/O thread)
        streamRead(ctx, route, state);

        // Final flush
        ctx.flush();
    }

    private void streamRead(ChannelHandlerContext ctx, UUID route, ReadState state) throws Exception {
        int chunkSize = pluginCfg.chunkSize;
        long remaining = state.remaining;
        int sinceFlush = 0;

        while (remaining > 0) {
            // Backpressure: wait until the outbound buffer drains
            if (!ctx.channel().isWritable()) {
                ctx.flush();
                while (!ctx.channel().isWritable()) Thread.sleep(1);
            }

            int len = (int) Math.min(chunkSize, remaining);
            ByteBuf payload = ctx.alloc().directBuffer(len);
            ByteBuffer nio = payload.internalNioBuffer(0, len);
            int n = state.channel.read(nio);
            if (n <= 0) { payload.release(); break; }
            payload.writerIndex(n);

            ByteBuf chunkFrame = FrameCodec.write(ctx.alloc(), Ops.READ_CHUNK, route, payload);
            ctx.write(chunkFrame);
            payload.release();

            remaining -= n;
            state.remaining = remaining;

            if (++sinceFlush >= 8) {
                ctx.flush();
                sinceFlush = 0;
            }
        }

        // Send READ_END
        Meta endMeta = Meta.of("ok", true, "written", state.initialRemaining - remaining);
        ByteBuf endFrame = FrameCodec.write(ctx.alloc(), Ops.READ_END, route, endMeta);
        ctx.writeAndFlush(endFrame);
        readStates.remove(route);
        state.channel.close();
    }

    // ---------- WRITE streaming ----------

    private void handleWriteStart(ChannelHandlerContext ctx, UUID route, Meta meta, ByteBuf buf) throws IOException {
        Path filePath = fileService.resolveFile(meta.path);

        // Create parent dirs if requested
        if (meta.createDirs && filePath.getParent() != null) Files.createDirectories(filePath.getParent());

        StandardOpenOption[] options;
        if ("append".equalsIgnoreCase(meta.mode)) {
            options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE};
        } else {
            options = new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};
        }
        FileChannel fc = FileChannel.open(filePath, options);

        // Fast path: payload present, or a zero-length file (no chunks follow)
        int payloadOffset = FrameCodec.HEADER_SIZE + FrameCodec.getMetaLen(buf);
        int payloadLen = buf.readableBytes() - payloadOffset;
        if (payloadLen > 0 || meta.length == 0) {
            if (payloadLen > 0) {
                byte[] data = new byte[payloadLen];
                buf.getBytes(payloadOffset, data);
                fc.write(ByteBuffer.wrap(data));
            }
            fc.force(false);
            fc.close();
            Meta res = Meta.of("ok", true, "written", (long) payloadLen);
            ctx.writeAndFlush(FrameCodec.write(ctx.alloc(), Ops.WRITE_END, route, res));
            return;
        }

        writeStates.put(route, new WriteState(fc));
    }

    private void handleWriteChunk(UUID route, ByteBuf buf) throws IOException {
        WriteState state = writeStates.get(route);
        if (state == null) return; // error already sent

        int metaLen = FrameCodec.getMetaLen(buf);
        int payloadOffset = FrameCodec.HEADER_SIZE + metaLen;
        int payloadLen = buf.readableBytes() - payloadOffset;

        if (payloadLen <= 0) return;

        // Write payload bytes to file channel
        ByteBuffer nio;
        if (buf.nioBufferCount() > 0) {
            nio = buf.nioBuffer(payloadOffset, payloadLen);
        } else {
            byte[] copy = new byte[payloadLen];
            buf.getBytes(payloadOffset, copy);
            nio = ByteBuffer.wrap(copy);
        }
        while (nio.hasRemaining()) {
            state.channel.write(nio);
        }
        state.written += payloadLen;
    }

    private void handleWriteEnd(ChannelHandlerContext ctx, UUID route) throws IOException {
        WriteState state = writeStates.remove(route);
        if (state == null) return;
        state.channel.force(false);
        state.channel.close();

        Meta res = Meta.of("ok", true, "written", state.written);
        ByteBuf resBuf = FrameCodec.write(ctx.alloc(), Ops.WRITE_END, route, res);
        ctx.writeAndFlush(resBuf);
    }

    // ---------- Helpers ----------

    private void cleanup(UUID route) {
        ReadState rs = readStates.remove(route);
        if (rs != null) {
            try { rs.channel.close(); } catch (IOException ignored) {}
        }
        WriteState ws = writeStates.remove(route);
        if (ws != null) {
            try { ws.channel.close(); } catch (IOException ignored) {}
        }
    }

    /** Wrap a ThrowingSupplier into a Meta (catches exceptions, returns ERROR Meta). */
    private static Meta safe(ThrowingSupplier<Meta> supplier) {
        try {
            Meta m = supplier.get();
            if (m == null) m = new Meta();
            m.ok = true;
            return m;
        } catch (Exception e) {
            return errMeta(e);
        }
    }

    /** Map an exception to an error Meta with a meaningful status code. */
    private static Meta errMeta(Throwable e) {
        int code;
        if (e instanceof PathUtil.SandboxViolationException) {
            code = 403;
        } else if (e instanceof NoSuchFileException) {
            code = 404;
        } else if (e instanceof FileAlreadyExistsException) {
            code = 409;
        } else if (e instanceof AccessDeniedException) {
            code = 403;
        } else if (e instanceof java.nio.file.DirectoryNotEmptyException) {
            code = 409;
        } else {
            code = 500;
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return Meta.of("ok", false, "code", code, "message", msg);
    }

    private void respond(ChannelHandlerContext ctx, int responseOp, UUID route, Meta meta) {
        // If the operation failed, send an ERROR frame so clients can throw properly
        int actualOp = (meta != null && !meta.ok && meta.code != 0) ? Ops.ERROR : responseOp;
        ByteBuf buf = FrameCodec.write(ctx.alloc(), actualOp, route, meta);
        ctx.writeAndFlush(buf);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        connected = false;
        authenticated = false;
        // Close all open streams
        readStates.forEach((r, s) -> { try { s.channel.close(); } catch (IOException ignored) {} });
        writeStates.forEach((r, s) -> { try { s.channel.close(); } catch (IOException ignored) {} });
        readStates.clear();
        writeStates.clear();
        if (onDisconnected != null) onDisconnected.run();
        Log.warn("Disconnected from server");
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Log.error("Connection error", cause);
        ctx.close();
    }

    // ---------- Stream state holders ----------

    private static class ReadState {
        final FileChannel channel;
        final long fileSize;
        final long initialRemaining;
        volatile long remaining;
        ReadState(FileChannel ch, long fs, long initialRemaining) {
            this.channel = ch;
            this.fileSize = fs;
            this.initialRemaining = initialRemaining;
            this.remaining = initialRemaining;
        }
    }

    private static class WriteState {
        final FileChannel channel;
        long written;
        WriteState(FileChannel channel) {
            this.channel = channel;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}