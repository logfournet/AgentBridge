package me.sfclog.agentbridge.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reads/writes the binary frame header from/to a {@link ByteBuf}.
 * <p>
 * Wire format (frame body, after length is stripped by LengthFieldBasedFrameDecoder):
 * <pre>
 *   byte  version  (1)
 *   int   op
 *   long  routeMsb
 *   long  routeLsb
 *   int   metaLen
 *   byte[] metaJson (UTF-8)
 *   byte[] payload
 * </pre>
 */
public final class FrameCodec {

    public static final int VERSION = 1;

    /** Fixed header size: version(1) + op(4) + route(16) + metaLen(4) = 25 */
    public static final int HEADER_SIZE = 25;

    private FrameCodec() {}

    // ---------- Write ----------

    /**
     * Allocate and write a frame body (excluding the final length prefix,
     * which is added by {@link io.netty.handler.codec.LengthFieldPrepender}).
     */
    public static ByteBuf write(ByteBufAllocator alloc,
                                 int op,
                                 UUID route,
                                 Meta meta,
                                 ByteBuf payload) {
        byte[] metaJson = (meta != null) ? Json.GSON.toJson(meta).getBytes(StandardCharsets.UTF_8) : new byte[0];
        int metaLen = metaJson.length;
        int payloadLen = (payload != null) ? payload.readableBytes() : 0;

        ByteBuf buf = alloc.buffer(HEADER_SIZE + metaLen + payloadLen);
        buf.writeByte(VERSION);
        buf.writeInt(op);
        buf.writeLong(route.getMostSignificantBits());
        buf.writeLong(route.getLeastSignificantBits());
        buf.writeInt(metaLen);
        if (metaLen > 0) buf.writeBytes(metaJson);
        if (payload != null && payloadLen > 0) buf.writeBytes(payload);
        return buf;
    }

    /** Convenience: write without a Meta. */
    public static ByteBuf write(ByteBufAllocator alloc, int op, UUID route, ByteBuf payload) {
        return write(alloc, op, route, null, payload);
    }

    /** Convenience: write with only Meta, no payload. */
    public static ByteBuf write(ByteBufAllocator alloc, int op, UUID route, Meta meta) {
        return write(alloc, op, route, meta, null);
    }

    // ---------- Read helpers ----------

    /** Offset of the `op` field. */
    public static int opOffset() { return 1; }

    /** Offset of the `route` field (16 bytes). */
    public static int routeOffset()   { return 5; }
    public static int routeSize()     { return 16; }

    /** Offset of the `metaLen` field. */
    public static int metaLenOffset() { return 21; }

    /** Offset of the meta JSON. */
    public static int metaOffset()    { return HEADER_SIZE; }

    /**
     * Get the op code from a raw frame body buffer (reader index unchanged).
     */
    public static int getOp(ByteBuf buf) {
        return buf.getInt(1);
    }

    /**
     * Get the route UUID from a raw frame body buffer (reader index unchanged).
     */
    public static UUID getRoute(ByteBuf buf) {
        return new UUID(buf.getLong(5), buf.getLong(13));
    }

    /**
     * Get meta JSON length from a raw frame body buffer.
     */
    public static int getMetaLen(ByteBuf buf) {
        return buf.getInt(21);
    }

    /**
     * Read Meta from a sub-section of the buffer.
     */
    public static Meta readMeta(ByteBuf buf) {
        int metaLen = getMetaLen(buf);
        if (metaLen <= 0) return null;
        byte[] json = new byte[metaLen];
        buf.getBytes(HEADER_SIZE, json);
        return Json.GSON.fromJson(new String(json, StandardCharsets.UTF_8), Meta.class);
    }

    /**
     * Set the route bytes in an existing frame body (mutates at position 5–20).
     */
    public static void setRoute(ByteBuf buf, UUID route) {
        buf.setLong(5, route.getMostSignificantBits());
        buf.setLong(13, route.getLeastSignificantBits());
    }

    /**
     * Copy payload bytes from a frame body into a byte array (returns length).
     */
    public static int extractPayload(ByteBuf buf, byte[] dest, int destOffset) {
        int metaLen = getMetaLen(buf);
        int payloadLen = buf.readableBytes() - HEADER_SIZE - metaLen;
        if (payloadLen <= 0) return 0;
        buf.getBytes(HEADER_SIZE + metaLen, dest, destOffset, payloadLen);
        return payloadLen;
    }

    /**
     * Check if buf contains a zeroed-out route (no route assigned yet).
     */
    public static boolean isRouteZero(ByteBuf buf) {
        return buf.getLong(5) == 0 && buf.getLong(13) == 0;
    }
}
