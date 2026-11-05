/*
 * This file is a part of the Raknetify project, licensed under MIT.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ishland.raknetify.common.connection;

import com.ishland.raknetify.common.Constants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import network.ycc.raknet.frame.FrameData;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Maintains independent zlib streams for reliable ordered RakNet channels.
 * Stream dictionaries persist between packets and restart at explicit channel markers, so
 * unrelated ordering channels never depend on each other's compression history.
 */
public class MultiChannelingStreamingCompression extends ChannelDuplexHandler {

    /**
     * Pipeline name used to locate compression state and statistics.
     */
    public static final String NAME = "raknetify-multichannel-streaming-compression";

    /**
     * Control payload advertising support for channel-specific streaming compression.
     */
    public static final long SERVER_HANDSHAKE = 0x40000010;
    /**
     * Ordered control payload that starts a fresh dictionary for its channel.
     */
    public static final long CHANNEL_START = 0x40000012;
    /**
     * Inbound zlib dictionaries indexed by ordering channel, owned by the event loop.
     */
    private final Inflater[] inflaters = new Inflater[8];
    /**
     * Outbound zlib dictionaries indexed by ordering channel, owned by the event loop.
     */
    private final Deflater[] deflaters = new Deflater[8];

    /**
     * Reserved channel-exemption set, currently unused by dictionary restart handling.
     */
    private final IntOpenHashSet channelsToIgnoreWhenReinit = new IntOpenHashSet();

    /**
     * Reusable scratch space for draining inflated payload bytes without per-packet arrays.
     */
    private final byte[] inflateBuffer = new byte[256 * 1024];
    /**
     * Reusable scratch space for draining compressed output without per-packet arrays.
     */
    private final byte[] deflateBuffer = new byte[256 * 1024];

    /**
     * Frame identifier recognized before compression and restored after decompression.
     */
    private final int rawPacketId;
    /**
     * Frame identifier marking payloads that require the channel's inflater.
     */
    private final int compressedPacketId;

    /**
     * Cumulative outgoing payload bytes before compression, excluding frame identifiers.
     */
    private volatile long outBytesRaw = 0L;
    /**
     * Cumulative outgoing payload bytes after compression, including small payloads passed through raw.
     */
    private volatile long outBytesCompressed = 0L;
    /**
     * Cumulative compressed payload bytes consumed by inbound inflaters.
     */
    private volatile long inBytesCompressed = 0L;
    /**
     * Cumulative payload bytes produced by inbound inflaters.
     */
    private volatile long inBytesRaw = 0L;

    /**
     * Whether the peer has advertised compatible streaming compression.
     */
    private boolean active = false;

    /**
     * Creates inactive per-channel compression state awaiting the peer handshake.
     *
     * @param rawPacketId frame identifier for uncompressed Minecraft payloads
     * @param compressedPacketId frame identifier for compressed Minecraft payloads
     */
    public MultiChannelingStreamingCompression(int rawPacketId, int compressedPacketId) {
        this.rawPacketId = rawPacketId;
        this.compressedPacketId = compressedPacketId;
    }

    /**
     * Advertises streaming compression support and releases the temporary control payload.
     *
     * @param ctx outbound context used to allocate and send the handshake frame
     */
    private void doServerHandshake(ChannelHandlerContext ctx) {
        final ByteBuf buf = ctx.alloc().buffer().writeLong(SERVER_HANDSHAKE);
        try {
            final FrameData data = FrameData.create(ctx.alloc(), Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID,
                    buf);
            ctx.write(data);
        } finally {
            buf.release();
        }
    }

    /**
     * Sends a dictionary-reset marker on every ordering channel before creating fresh deflaters.
     * Does nothing until the peer has advertised compression support.
     *
     * @param ctx outbound context preserving each marker's order relative to later payloads
     */
    private void doChannelStart(ChannelHandlerContext ctx) {
        if (!active) return;
        ByteBuf buf = ctx.alloc().buffer().writeLong(CHANNEL_START);
        try {
            for (int i = 0; i < 8; i++) {
                final FrameData data = FrameData.create(ctx.alloc(), Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID,
                        buf);
                data.setOrderChannel(i);
                ctx.write(data);
                initDeflater(i);
            }
        } finally {
            buf.release();
        }
    }

    /**
     * Replaces one outbound zlib dictionary after releasing its native resources.
     *
     * @param channel ordering-channel index whose outgoing stream is restarting
     */
    private void initDeflater(int channel) {
        if (!active) return;
        if (deflaters[channel] != null) deflaters[channel].end();
        deflaters[channel] = new Deflater();
        if (Constants.DEBUG) System.out.println("Raknetify: Streaming compression deflater for ch%d is ready".formatted(channel));
    }

    /**
     * Replaces one inbound zlib dictionary after releasing its native resources.
     *
     * @param channel ordering-channel index whose incoming stream is restarting
     */
    private void initInflater(int channel) {
        if (!active) return;
        if (inflaters[channel] != null) inflaters[channel].end();
        inflaters[channel] = new Inflater();
        if (Constants.DEBUG) System.out.println("Raknetify: Streaming compression inflater for ch%d is ready".formatted(channel));
    }

    /**
     * Advertises compression support after forwarding channel activation.
     *
     * @param ctx newly active connection context
     * @throws Exception if activation forwarding fails
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        doServerHandshake(ctx);
    }

    /**
     * Consumes compression control frames and inflates eligible ordered payloads.
     * Frames without a matching active dictionary continue through the pipeline unchanged.
     *
     * @param ctx inbound context receiving decompressed replacement frames
     * @param msg incoming frame or unrelated pipeline message
     * @throws Exception if decompression or superclass inbound processing fails
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FrameData compressedFrameData) {
            compressedFrameData.touch();
            if (compressedFrameData.getPacketId() == Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID) {
                handleHandshake(ctx, compressedFrameData);
                return;
            } else if (compressedFrameData.getPacketId() == compressedPacketId && compressedFrameData.getReliability().isReliable && compressedFrameData.getReliability().isOrdered && !compressedFrameData.getReliability().isSequenced && inflaters[compressedFrameData.getOrderChannel()] != null) {
                inflateFrame(ctx, compressedFrameData);
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Compresses eligible ordered payloads and restarts dictionaries after synchronization requests.
     * Payloads shorter than sixteen bytes retain their raw representation.
     *
     * @param ctx outbound context receiving compressed or unchanged frames
     * @param msg outgoing frame, local synchronization request, or unrelated message
     * @param promise completion notification transferred to the emitted frame
     * @throws Exception if superclass outbound processing fails
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg == SynchronizationLayer.SYNC_REQUEST_OBJECT) {
            super.write(ctx, msg, promise);
            doChannelStart(ctx);
            return;
        } else if (msg instanceof FrameData rawFrameData) {
            rawFrameData.touch();
            if (rawFrameData.getPacketId() == rawPacketId && rawFrameData.getReliability().isReliable && rawFrameData.getReliability().isOrdered && !rawFrameData.getReliability().isSequenced && deflaters[rawFrameData.getOrderChannel()] != null) {
                if (rawFrameData.getDataSize() < 16 + 1) {
                    outBytesRaw += rawFrameData.getDataSize() - 1;
                    outBytesCompressed += rawFrameData.getDataSize() - 1;
                    ctx.write(rawFrameData, promise);
                    return;
                }

                deflateFrame(ctx, rawFrameData, promise);
                return;
            }
        }
        super.write(ctx, msg, promise);
    }


    /**
     * Consumes a compression control frame and starts the requested channel's stream.
     *
     * @param ctx pipeline context used to acknowledge the peer's handshake
     * @param compressedFrameData control frame whose reference is released on every exit
     */
    private void handleHandshake(ChannelHandlerContext ctx, FrameData compressedFrameData) {
        final int orderChannel = compressedFrameData.getOrderChannel();
        ByteBuf payload = null;
        try {
            payload = compressedFrameData.createData().skipBytes(1);
            if (payload.readableBytes() == 8) {
                final long l = payload.readLong();
                if (l == CHANNEL_START) {
                    initInflater(orderChannel);
                    return;
                } else if (l == SERVER_HANDSHAKE) {
                    active = true;
                    doChannelStart(ctx);
                    return;
                }
            }
        } finally {
            compressedFrameData.release();
            if (payload != null) payload.release();
        }
    }

    /**
     * Inflates one ordered frame with that channel's retained dictionary and forwards its payload.
     *
     * @param ctx pipeline context that allocates and receives the replacement frame
     * @param compressedFrameData owned compressed frame, released after decoding or failure
     * @throws DataFormatException if the peer's compressed stream is invalid
     */
    private void inflateFrame(ChannelHandlerContext ctx, FrameData compressedFrameData) throws DataFormatException {
        final int orderChannel = compressedFrameData.getOrderChannel();
        final Inflater inflater = inflaters[orderChannel];
        final ByteBuf data = compressedFrameData.createData().skipBytes(1);

        ByteBuf out = null;
        FrameData rawFrameData = null;
        try {
            inflater.setInput(data.nioBuffer());

            inBytesCompressed += data.readableBytes();

            out = ctx.alloc().buffer();
            {
                int inflatedBytes;
                while ((inflatedBytes = inflater.inflate(inflateBuffer)) != 0) {
                    out.writeBytes(inflateBuffer, 0, inflatedBytes);
                }
            }

            inBytesRaw += out.writerIndex();

            rawFrameData = FrameData.create(ctx.alloc(), rawPacketId, out);
            rawFrameData.setReliability(compressedFrameData.getReliability());
            rawFrameData.setOrderChannel(orderChannel);
            ctx.fireChannelRead(rawFrameData);
            rawFrameData = null;
            return;
        } finally {
            data.release();
            compressedFrameData.release();
            if (out != null) out.release();
            if (rawFrameData != null) rawFrameData.release();
        }
    }

    /**
     * Compresses one frame without resetting its channel's dictionary between packets.
     *
     * @param ctx outbound pipeline context that receives the compressed replacement
     * @param rawFrameData owned raw frame, released after encoding or failure
     * @param promise completion notification transferred to the replacement frame
     */
    private void deflateFrame(ChannelHandlerContext ctx, FrameData rawFrameData, ChannelPromise promise) {
        final int orderChannel = rawFrameData.getOrderChannel();
        final Deflater deflater = deflaters[orderChannel];
        final ByteBuf data = rawFrameData.createData().skipBytes(1);

        ByteBuf out = null;
        FrameData compressedFrameData = null;
        try {
            deflater.setInput(data.nioBuffer());

            outBytesRaw += data.readableBytes();

            out = ctx.alloc().buffer();
            {
                int deflatedBytes;
                while ((deflatedBytes = deflater.deflate(deflateBuffer, 0, deflateBuffer.length, Deflater.SYNC_FLUSH)) != 0) {
                    out.writeBytes(deflateBuffer, 0, deflatedBytes);
                }
            }

            outBytesCompressed += out.writerIndex();

            compressedFrameData = FrameData.create(ctx.alloc(), compressedPacketId, out);
            compressedFrameData.setReliability(rawFrameData.getReliability());
            compressedFrameData.setOrderChannel(orderChannel);
            ctx.write(compressedFrameData, promise);
            compressedFrameData = null;
            return;
        } finally {
            data.release();
            rawFrameData.release();
            if (out != null) out.release();
            if (compressedFrameData != null) compressedFrameData.release();
        }
    }

    /**
     * Reports payload bytes consumed from compressed inbound frames.
     *
     * @return cumulative compressed bytes, excluding RakNet frame identifiers
     */
    public long getInBytesCompressed() {
        return inBytesCompressed;
    }

    /**
     * Reports payload bytes produced by decompression.
     *
     * @return cumulative decompressed inbound bytes
     */
    public long getInBytesRaw() {
        return inBytesRaw;
    }

    /**
     * Reports payload bytes emitted through the compression path.
     *
     * @return cumulative compressed bytes plus small eligible payloads sent without compression
     */
    public long getOutBytesCompressed() {
        return outBytesCompressed;
    }

    /**
     * Reports the original size of outgoing payloads eligible for compression.
     *
     * @return cumulative outgoing bytes before compression or small-payload bypass
     */
    public long getOutBytesRaw() {
        return outBytesRaw;
    }

    /**
     * Reports whether the peer has completed compression capability negotiation.
     *
     * @return true after a compatible server-handshake marker has been received
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Once-per-second event-loop task that updates compression ratios.
     */
    private ScheduledFuture<?> future;

    /**
     * Starts periodic compression sampling on the connection's event loop.
     *
     * @param ctx installed handler context supplying the scheduling event loop
     */
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.future = ctx.channel().eventLoop().scheduleAtFixedRate(this::tickMetrics, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels metric sampling and releases all native zlib resources.
     *
     * @param ctx removed handler context, unused because resources belong to this instance
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (future != null) future.cancel(false);
        for (int i = 0; i < 8; i++) {
            if (inflaters[i] != null) inflaters[i].end();
            if (deflaters[i] != null) deflaters[i].end();
        }
    }

    /**
     * Previous sample's compressed inbound byte total used to calculate an interval delta.
     */
    private long lastInBytesCompressed;
    /**
     * Previous sample's decompressed inbound byte total used to calculate an interval delta.
     */
    private long lastInBytesRaw;
    /**
     * Previous sample's original outgoing byte total used to calculate an interval delta.
     */
    private long lastOutBytesRaw;
    /**
     * Previous sample's transmitted outgoing byte total used to calculate an interval delta.
     */
    private long lastOutBytesCompressed;
    /**
     * Rolling sixteen-sample history of compressed-to-raw inbound byte ratios.
     */
    private final DescriptiveStatistics inCompressionRatioStats = new DescriptiveStatistics(16);
    /**
     * Rolling sixteen-sample history of compressed-to-raw outbound byte ratios.
     */
    private final DescriptiveStatistics outCompressionRatioStats = new DescriptiveStatistics(16);
    /**
     * Published mean inbound compressed-to-raw ratio, possibly NaN before a nonempty sample.
     */
    private volatile double inCompressionRatio;
    /**
     * Published mean outbound compressed-to-raw ratio, possibly NaN before a nonempty sample.
     */
    private volatile double outCompressionRatio;

    /**
     * Samples interval byte ratios, skips idle directions, and publishes rolling means for the HUD.
     */
    private void tickMetrics() {
        long deltaInBytesCompressed = this.inBytesCompressed - this.lastInBytesCompressed;
        long deltaInBytesRaw = this.inBytesRaw - this.lastInBytesRaw;
        long deltaOutBytesCompressed = this.outBytesCompressed - this.lastOutBytesCompressed;
        long deltaOutBytesRaw = this.outBytesRaw - this.lastOutBytesRaw;

        if (deltaInBytesRaw != 0) {
            double currentInCompressionRatio = deltaInBytesCompressed / (double) deltaInBytesRaw;
            inCompressionRatioStats.addValue(currentInCompressionRatio);
        }

        if (deltaOutBytesRaw != 0) {
            double currentOutCompressionRatio = deltaOutBytesCompressed / (double) deltaOutBytesRaw;
            outCompressionRatioStats.addValue(currentOutCompressionRatio);
        }

        this.inCompressionRatio = inCompressionRatioStats.getMean();
        this.outCompressionRatio = outCompressionRatioStats.getMean();

        this.lastInBytesCompressed = this.inBytesCompressed;
        this.lastInBytesRaw = this.inBytesRaw;
        this.lastOutBytesCompressed = this.outBytesCompressed;
        this.lastOutBytesRaw = this.outBytesRaw;


    }

    /**
     * Reports the rolling mean size ratio for inbound compressed traffic.
     *
     * @return compressed bytes divided by raw bytes, or NaN before a nonempty sample
     */
    public double getInCompressionRatio() {
        return inCompressionRatio;
    }

    /**
     * Reports the rolling mean size ratio for outbound traffic eligible for compression.
     *
     * @return transmitted bytes divided by original bytes, or NaN before a nonempty sample
     */
    public double getOutCompressionRatio() {
        return outCompressionRatio;
    }
}
