package me.sfclog.agentbridge.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import me.sfclog.agentbridge.common.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Netty-based client for AgentBridgeServer.
 * <p>
 * Provides synchronous blocking methods for CLI and MCP use.
 * Each request gets a UUID route; the agent echoes it back for correlation.
 */
public class RemoteBridge implements AutoCloseable {

    private final ClientConfig cfg;
    private final NioEventLoopGroup ioGroup;
    private final DefaultEventExecutorGroup business;
    private volatile Channel channel;
    private final Map<UUID, PendingRequest> requests = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RemoteBridge(ClientConfig cfg) {
        this.cfg = cfg;
        this.ioGroup = new NioEventLoopGroup(1);
        this.business = new DefaultEventExecutorGroup(4);
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    public synchronized void connect() throws Exception {
        CompletableFuture<Channel> ready = new CompletableFuture<>();

        Bootstrap b = new Bootstrap()
                .group(ioGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, cfg.connectTimeoutMs)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.config().setWriteBufferHighWaterMark(4 << 20);
                        ch.pipeline().addLast(
                                new LengthFieldBasedFrameDecoder(256 << 20, 0, 4, 0, 4),
                                new LengthFieldPrepender(4)
                        );
                        ch.pipeline().addLast(business, new InnerHandler(ready));
                    }
                });

        b.connect(cfg.host, cfg.port).addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) ready.completeExceptionally(new IOException(f.cause()));
        });

        channel = ready.get(cfg.connectTimeoutMs + 5000, TimeUnit.MILLISECONDS);
    }

    public boolean isActive() { return channel != null && channel.isActive(); }

    // ------------------------------------------------------------------
    // Inner Netty handler
    // ------------------------------------------------------------------

    private class InnerHandler extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<Channel> ready;

        InnerHandler(CompletableFuture<Channel> ready) { this.ready = ready; }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // Send HELLO
            Meta hello = Meta.of("role", "client", "key", cfg.token);
            ByteBuf b = FrameCodec.write(ctx.alloc(), Ops.HELLO, UUID.randomUUID(), hello);
            ctx.writeAndFlush(b);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                int op = FrameCodec.getOp(buf);
                UUID route = FrameCodec.getRoute(buf);
                Meta meta = FrameCodec.readMeta(buf);

                // HELLO_OK completes connect
                if (op == Ops.HELLO_OK) {
                    if (!ready.isDone()) ready.complete(ctx.channel());
                    return;
                }

                PendingRequest pr = requests.get(route);
                if (pr == null) return; // stale response, ignore

                if (op == Ops.ERROR) {
                    requests.remove(route);
                    pr.completeExceptionally(new BridgeException(
                            meta != null ? meta.code : 500,
                            meta != null ? meta.message : "unknown error"));
                    return;
                }

                try {
                    if (pr.onFrame(op, meta, buf) == FrameResult.DONE) {
                        requests.remove(route);
                    }
                } catch (Throwable t) {
                    requests.remove(route);
                    pr.completeExceptionally(t);
                }
            } finally {
                buf.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Log.error("Client error", cause);
            ctx.close();
        }
    }

    // ------------------------------------------------------------------
    // Pending requests
    // ------------------------------------------------------------------

    enum FrameResult { MORE, DONE }

    private interface PendingRequest {
        /** @return DONE when the request/stream is finished */
        FrameResult onFrame(int op, Meta meta, ByteBuf payload) throws Exception;
        void completeExceptionally(Throwable t);
    }

    /** One-shot response */
    private static class OneShot implements PendingRequest {
        final CompletableFuture<Meta> future = new CompletableFuture<>();

        @Override
        public FrameResult onFrame(int op, Meta meta, ByteBuf payload) {
            future.complete(meta != null ? meta : new Meta());
            return FrameResult.DONE;
        }

        @Override
        public void completeExceptionally(Throwable t) {
            future.completeExceptionally(t);
        }
    }

    /** Streamed READ -> local file */
    private static class ReadStream implements PendingRequest {
        final FileChannel fc;
        final CompletableFuture<Meta> future = new CompletableFuture<>();

        ReadStream(FileChannel fc) { this.fc = fc; }

        @Override
        public FrameResult onFrame(int op, Meta meta, ByteBuf payload) throws IOException {
            switch (op) {
                case Ops.READ_START -> { return FrameResult.MORE; }
                case Ops.READ_CHUNK -> {
                    int pl = payload.readableBytes() - FrameCodec.HEADER_SIZE - FrameCodec.getMetaLen(payload);
                    if (pl <= 0) return FrameResult.MORE;
                    byte[] data = new byte[pl];
                    payload.getBytes(FrameCodec.HEADER_SIZE + FrameCodec.getMetaLen(payload), data);
                    fc.write(ByteBuffer.wrap(data));
                    return FrameResult.MORE;
                }
                case Ops.READ_END -> {
                    fc.force(false);
                    fc.close();
                    future.complete(meta != null ? meta : new Meta());
                    return FrameResult.DONE;
                }
                default -> throw new IOException("Unexpected op " + op + " in read stream");
            }
        }

        @Override
        public void completeExceptionally(Throwable t) {
            try { fc.close(); } catch (IOException ignored) {}
            future.completeExceptionally(t);
        }
    }

    /** Write-stream pending (awaits WRITE_END from agent) */
    private static class WritePending implements PendingRequest {
        final CompletableFuture<Meta> future = new CompletableFuture<>();

        @Override
        public FrameResult onFrame(int op, Meta meta, ByteBuf payload) {
            future.complete(meta != null ? meta : new Meta());
            return FrameResult.DONE;
        }

        @Override
        public void completeExceptionally(Throwable t) {
            future.completeExceptionally(t);
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private void requireOpen() {
        if (channel == null || !channel.isActive())
            throw new IllegalStateException("Not connected to AgentBridgeServer");
    }

    /** Send a one-shot request and block for the response. */
    public Meta request(int op, Meta meta, byte[] payload, long timeoutMs) throws Exception {
        requireOpen();
        UUID route = UUID.randomUUID();
        OneShot ps = new OneShot();
        requests.put(route, ps);
        try {
            ByteBuf pl = payload != null ? Unpooled.wrappedBuffer(payload) : null;
            ByteBuf frame = FrameCodec.write(channel.alloc(), op, route, meta, pl);
            channel.writeAndFlush(frame);
            if (pl != null) pl.release();

            return await(ps.future, timeoutMs);
        } finally {
            requests.remove(route);
        }
    }

    /** Read remote file → local file (streamed) */
    public Meta readToFile(String agent, String remotePath, long offset, long length, Path localPath) throws Exception {
        requireOpen();
        Path abs = localPath.toAbsolutePath();
        if (abs.getParent() != null) Files.createDirectories(abs.getParent());
        FileChannel fc = FileChannel.open(abs, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        UUID route = UUID.randomUUID();
        ReadStream rs = new ReadStream(fc);
        requests.put(route, rs);
        try {
            Meta meta = Meta.of("agent", agent, "path", remotePath, "offset", offset, "length", length);
            ByteBuf frame = FrameCodec.write(channel.alloc(), Ops.READ_START, route, meta);
            channel.writeAndFlush(frame);

            return await(rs.future, streamTimeout(offset, length));
        } finally {
            requests.remove(route);
        }
    }

    /** Upload local file → remote (streamed) */
    public Meta writeFromFile(String agent, String remotePath, Path localPath, String mode, boolean createDirs) throws Exception {
        requireOpen();
        if (!Files.exists(localPath)) throw new IOException("Local file not found: " + localPath);

        long fileSize = Files.size(localPath);
        UUID route = UUID.randomUUID();
        WritePending wp = new WritePending();
        requests.put(route, wp);
        try {
            // WRITE_START
            Meta startMeta = Meta.of("agent", agent, "path", remotePath, "mode", mode, "length", fileSize, "createDirs", createDirs);
            ByteBuf startFrame = FrameCodec.write(channel.alloc(), Ops.WRITE_START, route, startMeta);
            channel.writeAndFlush(startFrame);

            // Chunks
            int chunk = cfg.chunkSize;
            try (FileChannel fc = FileChannel.open(localPath, StandardOpenOption.READ)) {
                ByteBuffer tmp = ByteBuffer.allocate(chunk);
                long remaining = fileSize;
                while (remaining > 0) {
                    int want = (int) Math.min(chunk, remaining);
                    tmp.clear();
                    tmp.limit(want);

                    int got = 0;
                    while (got < want) {
                        int n = fc.read(tmp);
                        if (n < 0) break;
                        got += n;
                    }
                    if (got == 0) break;
                    tmp.flip();

                    ByteBuf payload = Unpooled.wrappedBuffer(tmp.array(), 0, got);
                    ByteBuf chunkFrame = FrameCodec.write(channel.alloc(), Ops.WRITE_CHUNK, route, payload);
                    channel.write(chunkFrame);
                    payload.release();
                    remaining -= got;

                    // Backpressure: don't let the outbound buffer grow unbounded
                    if (!channel.isWritable()) {
                        channel.flush();
                        while (!channel.isWritable()) Thread.sleep(1);
                    }
                }
            }

            // WRITE_END
            ByteBuf endFrame = FrameCodec.write(channel.alloc(), Ops.WRITE_END, route, (Meta) null);
            channel.writeAndFlush(endFrame);

            return await(wp.future, streamTimeout(0, fileSize));
        } finally {
            requests.remove(route);
        }
    }

    /** Upload raw bytes */
    public Meta writeBytes(String agent, String remotePath, byte[] content, String mode, boolean createDirs) throws Exception {
        // Single frame for small payloads
        if (content.length <= cfg.chunkSize) {
            return request(Ops.WRITE_START, Meta.of("agent", agent, "path", remotePath, "mode", mode,
                            "length", (long) content.length, "createDirs", createDirs),
                    content, timeout());
        }

        // Chunked for large payloads
        requireOpen();
        UUID route = UUID.randomUUID();
        WritePending wp = new WritePending();
        requests.put(route, wp);
        try {
            Meta startMeta = Meta.of("agent", agent, "path", remotePath, "mode", mode,
                    "length", (long) content.length, "createDirs", createDirs);
            channel.writeAndFlush(FrameCodec.write(channel.alloc(), Ops.WRITE_START, route, startMeta));

            int off = 0;
            while (off < content.length) {
                int n = Math.min(cfg.chunkSize, content.length - off);
                ByteBuf payload = Unpooled.wrappedBuffer(content, off, n);
                channel.write(FrameCodec.write(channel.alloc(), Ops.WRITE_CHUNK, route, payload));
                payload.release();
                off += n;

                if (!channel.isWritable()) {
                    channel.flush();
                    while (!channel.isWritable()) Thread.sleep(1);
                }
            }

            channel.writeAndFlush(FrameCodec.write(channel.alloc(), Ops.WRITE_END, route, (Meta) null));
            return await(wp.future, streamTimeout(0, content.length));
        } finally {
            requests.remove(route);
        }
    }

    /**
     * Read-modify-write exact-text replacement on a remote file.
     *
     * @return number of replacements performed
     */
    public int editFile(String agent, String remotePath, String oldStr, String newStr, boolean replaceAll) throws Exception {
        if (oldStr == null || oldStr.isEmpty()) {
            throw new BridgeException(400, "old_string must not be empty");
        }
        Path tmp = Files.createTempFile("bridge-edit-", ".txt");
        try {
            readToFile(agent, remotePath, 0, 0, tmp);
            String content = Files.readString(tmp, java.nio.charset.StandardCharsets.UTF_8);

            int count = 0;
            int idx = 0;
            while ((idx = content.indexOf(oldStr, idx)) >= 0) {
                count++;
                idx += oldStr.length();
                if (!replaceAll) break;
            }
            if (count == 0) {
                throw new BridgeException(404, "old_string not found in " + remotePath);
            }

            String updated;
            if (replaceAll) {
                updated = content.replace(oldStr, newStr);
            } else {
                int at = content.indexOf(oldStr);
                updated = content.substring(0, at) + newStr + content.substring(at + oldStr.length());
            }

            byte[] data = updated.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeBytes(agent, remotePath, data, "truncate", false);
            return replaceAll ? count : 1;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ------------------------------------------------------------------
    // Convenience one-shot methods
    // ------------------------------------------------------------------

    public Meta list(String agent, String path) throws Exception {
        return request(Ops.LIST, Meta.of("agent", agent, "path", path), null, timeout());
    }
    public Meta stat(String agent, String path) throws Exception {
        return request(Ops.STAT, Meta.of("agent", agent, "path", path), null, timeout());
    }
    public Meta exists(String agent, String path) throws Exception {
        return request(Ops.EXISTS, Meta.of("agent", agent, "path", path), null, timeout());
    }
    public Meta delete(String agent, String path, boolean recursive) throws Exception {
        return request(Ops.DELETE, Meta.of("agent", agent, "path", path, "recursive", recursive), null, timeout());
    }
    public Meta move(String agent, String src, String dst) throws Exception {
        return request(Ops.MOVE, Meta.of("agent", agent, "path", src, "dest", dst), null, timeout());
    }
    public Meta mkdir(String agent, String path, boolean parents) throws Exception {
        return request(Ops.MKDIR, Meta.of("agent", agent, "path", path, "createDirs", parents), null, timeout());
    }
    public Meta exec(String agent, String command, String cwd, long timeoutMs) throws Exception {
        return request(Ops.EXEC, Meta.of("agent", agent, "command", command, "cwd", cwd, "timeoutMs", timeoutMs),
                null, timeoutMs > 0 ? timeoutMs + 5000 : timeout());
    }
    public Meta console(String agent, String command, long timeoutMs) throws Exception {
        return request(Ops.CONSOLE, Meta.of("agent", agent, "command", command, "timeoutMs", timeoutMs),
                null, timeoutMs > 0 ? timeoutMs + 5000 : timeout());
    }
    public Meta agents() throws Exception {
        return request(Ops.AGENT_LIST, Meta.of(), null, timeout());
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

    private static <T> T await(CompletableFuture<T> f, long timeoutMs) throws Exception {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } catch (TimeoutException e) {
            throw new IOException("Timed out after " + timeoutMs + "ms");
        }
    }

    private long timeout() { return 60_000; }

    private long streamTimeout(long offset, long length) {
        long est = length > 0 ? (length / 5_000_000) * 1000 : 0;
        return Math.min(Math.max(est, 60_000), 3_600_000);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (channel != null) {
            channel.close().awaitUninterruptibly(1000);
        }
        // Drain pending handler events (channelInactive etc.) before terminating
        business.shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS).syncUninterruptibly();
        ioGroup.shutdownGracefully(100, 1000, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }
}