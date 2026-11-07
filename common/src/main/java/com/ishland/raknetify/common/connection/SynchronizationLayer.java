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
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ReferenceCountUtil;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;
import network.ycc.raknet.pipeline.FrameJoiner;
import network.ycc.raknet.pipeline.FrameOrderIn;
import network.ycc.raknet.pipeline.FrameOrderOut;
import network.ycc.raknet.pipeline.ReliabilityHandler;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Abandons obsolete ordered traffic when Minecraft changes worlds or protocol phases. A reliable
 * marker advances peer cursors before held traffic resumes. All queue operations run on the
 * connection event loop and every retained write has a terminal cleanup path.
 */
public class SynchronizationLayer extends ChannelDuplexHandler {
    /** Local outbound request and inbound notification, never serialized as application data. */
    public static final Object SYNC_REQUEST_OBJECT = SyncRequest.INSTANCE;

    /** Typed local control event that cannot be confused with a payload's value equality. */
    public enum SyncRequest {
        /** Requests abandonment of obsolete ordered traffic before the next protocol phase. */
        INSTANCE
    }

    /** Lanes whose pending traffic must survive a phase transition. */
    private final IntSet channelsToIgnore = new IntOpenHashSet();

    /** Application writes retained until the synchronization marker is acknowledged. */
    private final Deque<PendingWrite> writes = new ArrayDeque<>();

    /** Owned transport references detached until the same acknowledgement. */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** Bounds per-write bookkeeping while the peer withholds the marker acknowledgement. */
    private static final int MAX_PENDING_WRITES = 8192;

    /**
     * Conservative payload and header bytes owned by application writes waiting for
     * acknowledgement.
     */
    private long pendingWriteBytes;

    /** Whether new writes must wait for the outstanding marker. */
    private boolean waiting;

    /**
     * Selects ordering lanes whose traffic remains meaningful across a phase transition.
     *
     * @param ignoredChannels lane indices preserved by both outbound and inbound resets
     * @throws IllegalArgumentException if a lane lies outside the eight supported ordering lanes
     */
    public SynchronizationLayer(final int... ignoredChannels) {
        for (final int channel : ignoredChannels) {
            if (channel < 0 || channel >= SynchronizationPacket.CHANNEL_COUNT) {
                throw new IllegalArgumentException("Invalid synchronization exclusion");
            }
            channelsToIgnore.add(channel);
        }
    }

    /**
     * Validates an entire marker before notifying downstream handlers or changing transport state.
     * Both the frame and its derived view are released even when decoding fails.
     *
     * @param ctx receiving connection context
     * @param msg owned input frame or unrelated message transferred onward
     * @throws Exception if ordinary pipeline processing or marker validation fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (!(msg instanceof FrameData packet) || !isSynchronizationPacket(packet)) {
            super.channelRead(ctx, msg);
            return;
        }
        try {
            final ByteBuf bytes = packet.createData();
            try {
                bytes.skipBytes(1);
                final SynchronizationPacket marker =
                        SynchronizationPacket.read(bytes, channelsToIgnore);
                final Transport transport = Transport.capture(ctx);
                final ChannelHandlerContext orderingContext =
                        Objects.requireNonNull(ctx.pipeline().context(transport.in()));
                marker.apply(transport.in(), transport.joiner(), transport.reliability());
                ctx.fireUserEventTriggered(SYNC_REQUEST_OBJECT);
                transport.in().drainReadyFrames(orderingContext::fireChannelRead);
            } finally {
                bytes.release();
            }
        } finally {
            packet.release();
        }
    }

    /**
     * Rejects empty frames before reading their identifier and releases the rejected input
     * reference.
     *
     * @param packet owned frame inspected without changing valid payload ownership
     * @return whether this frame carries a synchronization marker
     * @throws CorruptedFrameException if the frame has no packet identifier
     */
    private static boolean isSynchronizationPacket(final FrameData packet) {
        if (packet.getDataSize() == 0) {
            packet.release();
            throw new CorruptedFrameException("Empty framed packet");
        }
        return packet.getPacketId() == Constants.RAKNET_SYNC_PACKET_ID;
    }

    /**
     * Starts a reset or retains later writes until its marker is acknowledged. A repeated request
     * shares the active reset and does not discard newly queued traffic.
     *
     * @param ctx connection event loop and outbound pipeline
     * @param msg reset event or owned application payload
     * @param promise completion of request acceptance or application delivery
     * @throws Exception if ordinary outbound forwarding fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        if (msg instanceof SyncRequest) {
            if (!waiting) {
                startReset(ctx);
            }
            promise.trySuccess();
        } else if (waiting) {
            retainWrite(ctx, msg, promise);
        } else {
            super.write(ctx, msg, promise);
        }
    }

    /**
     * Retains a later write only while both byte and entry limits permit it. Overflow fails the
     * connection because silently dropping application traffic would break protocol ordering.
     *
     * @param ctx connection whose transport configuration sets the byte ceiling
     * @param message owned application payload waiting behind the synchronization marker
     * @param promise caller completion failed if the queue cannot accept this payload
     */
    private void retainWrite(
            final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
        final long bytes = retainedBytes(message);
        if (writes.size() >= MAX_PENDING_WRITES
                || pendingWriteBytes + bytes > RakNet.config(ctx).getMaxQueuedBytes()) {
            final TooLongFrameException failure =
                    new TooLongFrameException("Synchronization queue limit exceeded");
            ReferenceCountUtil.release(message);
            promise.tryFailure(failure);
            failPending(failure);
            ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            return;
        }
        writes.addLast(new PendingWrite(promise, message, bytes));
        pendingWriteBytes += bytes;
    }

    /**
     * Includes a frame-header allowance so even empty writes consume a finite byte reservation.
     *
     * @param message application buffer, framed payload, or control object awaiting delivery
     * @return measurable payload bytes plus one maximum frame header
     */
    private static long retainedBytes(final Object message) {
        if (message instanceof FrameData frame) {
            return (long) frame.getDataSize() + Frame.HEADER_SIZE;
        }
        if (message instanceof ByteBuf bytes) {
            return (long) bytes.readableBytes() + Frame.HEADER_SIZE;
        }
        if (message instanceof ByteBufHolder holder) {
            return (long) holder.content().readableBytes() + Frame.HEADER_SIZE;
        }
        return Frame.HEADER_SIZE;
    }

    /**
     * Detaches obsolete transport traffic and writes the marker before any surviving data.
     *
     * @param ctx connection owning every detached frame and completion callback
     */
    private void startReset(final ChannelHandlerContext ctx) {
        final Transport transport = Transport.capture(ctx);
        final FrameData marker = createMarker(ctx, transport);
        try {
            waiting = true;
            retainSurvivors(transport.reliability());
        } catch (RuntimeException exception) {
            marker.release();
            failPending(exception);
            throw exception;
        }
        ctx.write(marker)
                .addListener(future -> {
                    if (future.isSuccess()) {
                        resume(ctx, transport.reliability());
                    } else {
                        failPending(Objects.requireNonNull(future.cause()));
                        ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
                    }
                });
    }

    /**
     * Encodes a cursor snapshot while balancing the temporary payload buffer's ownership.
     *
     * @param ctx allocator for the control frame
     * @param transport typed transport stages whose cursors are read on their event loop
     * @return owned reliable marker ready for an outbound write
     */
    private FrameData createMarker(final ChannelHandlerContext ctx, final Transport transport) {
        final ByteBuf bytes = ctx.alloc().buffer(45);
        try {
            bytes.writeByte(transport.out().channelCount() - channelsToIgnore.size());
            for (int channel = 0; channel < transport.out().channelCount(); channel++) {
                if (!channelsToIgnore.contains(channel)) {
                    bytes.writeByte(channel).writeInt(transport.out().lastOrderIndex(channel));
                }
            }
            bytes.writeInt(transport.reliability().nextSendSequence());
            final FrameData marker =
                    FrameData.create(ctx.alloc(), Constants.RAKNET_SYNC_PACKET_ID, bytes);
            marker.setReliability(FramedPacket.Reliability.RELIABLE);
            return marker;
        } finally {
            bytes.release();
        }
    }

    /**
     * Completes superseded writes and retains exempt or unordered frames without exposing transport
     * collections.
     *
     * @param reliability scheduler transferring all pending frame ownership to this reset
     */
    private void retainSurvivors(final ReliabilityHandler reliability) {
        for (final Frame frame : reliability.takePendingFrames()) {
            if (frame.getReliability().isOrdered
                    && !channelsToIgnore.contains(frame.getOrderChannel())) {
                final ChannelPromise promise = frame.getPromise();
                if (promise != null) {
                    promise.trySuccess();
                }
                frame.release();
            } else {
                frames.addLast(frame);
            }
        }
    }

    /**
     * Restores surviving frames and forwards held writes only after successful marker delivery.
     * Late callbacks after removal cannot revive released frames or closed writes.
     *
     * @param ctx outbound pipeline for application writes and flushing
     * @param reliability scheduler accepting the preserved frame references
     */
    private void resume(final ChannelHandlerContext ctx, final ReliabilityHandler reliability) {
        if (!waiting) {
            return;
        }
        waiting = false;
        try {
            while (!frames.isEmpty()) {
                reliability.restorePendingFrame(frames.getFirst());
                frames.removeFirst();
            }
            while (!writes.isEmpty()) {
                final PendingWrite write = writes.removeFirst();
                pendingWriteBytes -= write.bytes();
                ctx.write(write.message(), write.promise())
                        .addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
            }
            ctx.flush();
        } catch (RuntimeException exception) {
            failPending(exception);
            ctx.fireExceptionCaught(exception);
            ctx.close().addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }
    }

    /**
     * Fails and releases every locally retained reference, including writes whose promises were
     * cancelled.
     *
     * @param cause terminal transport or lifecycle failure reported to pending writers
     */
    private void failPending(final Throwable cause) {
        waiting = false;
        while (!frames.isEmpty()) {
            final Frame frame = frames.removeFirst();
            final ChannelPromise promise = frame.getPromise();
            if (promise != null) {
                promise.tryFailure(cause);
            }
            frame.release();
        }
        while (!writes.isEmpty()) {
            final PendingWrite write = writes.removeFirst();
            pendingWriteBytes -= write.bytes();
            write.promise().tryFailure(cause);
            ReferenceCountUtil.release(write.message());
        }
    }

    /**
     * Releases paused traffic when the underlying connection closes before marker acknowledgement.
     *
     * @param ctx closed connection whose lifecycle event continues downstream
     * @throws Exception if a downstream inactivity handler fails
     */
    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        failPending(new ClosedChannelException());
        super.channelInactive(ctx);
    }

    /**
     * Cleans retained traffic on explicit removal even if the surrounding channel stays open.
     *
     * @param ctx context being detached from the pipeline
     * @throws Exception if superclass removal fails
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) throws Exception {
        failPending(new ClosedChannelException());
        super.handlerRemoved(ctx);
    }

    /**
     * Keeps a retained message paired with the promise supplied by its caller.
     *
     * @param promise eventual delivery or terminal failure notification
     * @param message owned payload reference transferred onward or released on failure
     * @param bytes retained payload and header reservation returned on delivery or release
     */
    private record PendingWrite(ChannelPromise promise, Object message, long bytes) {}

    /**
     * Holds typed operations rather than mutable implementation fields from the transport.
     *
     * @param in inbound ordering stage
     * @param out outbound ordering stage
     * @param joiner fragment ownership stage
     * @param reliability datagram scheduling stage
     */
    private record Transport(
            FrameOrderIn in,
            FrameOrderOut out,
            FrameJoiner joiner,
            ReliabilityHandler reliability) {
        /**
         * Resolves required handlers only when synchronization is requested.
         *
         * @param ctx pipeline containing the connection's transport stages
         * @return complete typed transport view with no nullable fields
         * @throws NullPointerException if synchronization is installed without a required transport
         *     stage
         */
        static Transport capture(final ChannelHandlerContext ctx) {
            return new Transport(
                    Objects.requireNonNull(
                            ctx.pipeline().get(FrameOrderIn.class), "Missing inbound ordering"),
                    Objects.requireNonNull(
                            ctx.pipeline().get(FrameOrderOut.class), "Missing outbound ordering"),
                    Objects.requireNonNull(
                            ctx.pipeline().get(FrameJoiner.class), "Missing reassembly"),
                    Objects.requireNonNull(
                            ctx.pipeline().get(ReliabilityHandler.class), "Missing reliability"));
        }
    }
}
