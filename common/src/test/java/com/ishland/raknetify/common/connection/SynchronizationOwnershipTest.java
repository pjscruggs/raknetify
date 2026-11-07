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

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.common.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

import network.ycc.raknet.config.DefaultConfig;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.pipeline.FrameJoiner;
import network.ycc.raknet.pipeline.FrameOrderIn;
import network.ycc.raknet.pipeline.FrameOrderOut;
import network.ycc.raknet.pipeline.ReliabilityHandler;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Guards synchronization frame ownership and propagation of the transport's actual write result.
 */
class SynchronizationOwnershipTest {

    /**
     * Releases both the consumed SYNC frame and its derived buffer after applying an ordering
     * reset.
     */
    @Test
    void consumedMarkerReleasesEveryReference() {
        EmbeddedChannel channel = transport();
        FrameData marker =
                marker(Unpooled.buffer().writeByte(1).writeByte(2).writeInt(4).writeInt(23));
        try {
            assertFalse(channel.writeInbound(marker));
            assertEquals(0, marker.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Releases the original frame even when its truncated cursor data fails to decode. */
    @Test
    void malformedMarkerAlsoReleasesEveryReference() {
        EmbeddedChannel channel = transport();
        FrameData marker = marker(Unpooled.buffer().writeByte(1));
        try {
            assertThrows(
                    io.netty.handler.codec.CorruptedFrameException.class,
                    () -> channel.writeInbound(marker));
            assertEquals(0, marker.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Keeps ownership of unrelated application frames with the next inbound handler. */
    @Test
    void ordinaryFrameIsForwardedWithoutRelease() {
        EmbeddedChannel channel = transport();
        ByteBuf bytes = Unpooled.buffer().writeInt(42);
        FrameData packet =
                FrameData.create(channel.alloc(), Constants.RAKNET_GAME_PACKET_ID, bytes);
        bytes.release();
        try {
            assertTrue(channel.writeInbound(packet));
            assertSame(packet, channel.readInbound());
            assertEquals(1, packet.refCnt());
        } finally {
            packet.release();
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Does not turn a pending or failed synchronization write into an early successful completion.
     */
    @Test
    void synchronizationPromiseBelongsToTransport() {
        CompletionGate gate = new CompletionGate();
        RakNetSimpleMultiChannelCodec codec =
                new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                        .addHandler((buffer, silent) -> 2);
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            ((FrameData) channel.readOutbound()).release();
            ChannelPromise result = channel.newPromise();
            assertSame(result, channel.write(SynchronizationLayer.SYNC_REQUEST_OBJECT, result));
            assertSame(result, gate.pending);
            assertFalse(result.isDone(), "The codec must wait for the downstream write result");
            IOException failure =
                    new IOException("Fixture transport rejected the synchronization marker");
            java.util.Objects.requireNonNull(gate.pending).setFailure(failure);
            assertSame(failure, result.cause());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Keeps a packet waiting for negotiation on the same channel as packets written after
     * acknowledgement.
     */
    @Test
    void negotiationDoesNotSplitOneOrderedStreamAcrossChannels() {
        NegotiationGate gate = new NegotiationGate();
        RakNetSimpleMultiChannelCodec codec =
                new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                        .addHandler((buffer, silent) -> 1);
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            channel.writeOutbound(Unpooled.buffer().writeByte(1));
            assertNull(channel.readOutbound(), "Packet must wait for the marker acknowledgement");
            java.util.Objects.requireNonNull(gate.pending).setSuccess();
            channel.writeOutbound(Unpooled.buffer().writeByte(2));
            FrameData earlier = channel.readOutbound();
            FrameData later = channel.readOutbound();
            try {
                assertEquals(1, earlier.getOrderChannel());
                assertEquals(earlier.getOrderChannel(), later.getOrderChannel());
                assertEquals(earlier.getReliability(), later.getReliability());
            } finally {
                earlier.release();
                later.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A failed marker must fail queued writes instead of sending packets on channels the peer never
     * enabled.
     */
    @Test
    void failedNegotiationReleasesQueuedWrites() {
        NegotiationGate gate = new NegotiationGate();
        RakNetSimpleMultiChannelCodec codec =
                new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                        .addHandler((buffer, silent) -> 1);
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            var write = channel.write(payload);
            IOException failure = new IOException("Fixture negotiation write failed");
            java.util.Objects.requireNonNull(gate.pending).setFailure(failure);
            assertSame(failure, write.cause());
            assertNull(channel.readOutbound());
            assertEquals(0, payload.refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Cancelling one queued promise cannot interrupt cleanup of later frames when the handler is
     * removed.
     */
    @Test
    void removalDrainsCancelledWritesBeforeLateAcknowledgement() {
        NegotiationGate gate = new NegotiationGate();
        RakNetSimpleMultiChannelCodec codec =
                new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                        .addHandler((buffer, silent) -> 1);
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        ByteBuf first = Unpooled.buffer().writeByte(1);
        ByteBuf second = Unpooled.buffer().writeByte(2);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            var cancelled = channel.write(first);
            var remaining = channel.write(second);
            assertTrue(cancelled.cancel(false));
            channel.pipeline().remove(codec);
            assertTrue(remaining.isDone());
            assertFalse(remaining.isSuccess());
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            java.util.Objects.requireNonNull(gate.pending).setSuccess();
            assertNull(
                    channel.readOutbound(),
                    "Removed frames cannot be forwarded by a late acknowledgement");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A classifier exception while negotiation is pending still releases the input buffer and fails
     * its write.
     */
    @Test
    void queuedClassifierFailureReleasesInput() {
        NegotiationGate gate = new NegotiationGate();
        IllegalArgumentException failure =
                new IllegalArgumentException("Fixture classifier rejected packet");
        RakNetSimpleMultiChannelCodec codec =
                new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                        .addHandler((buffer, silent) -> {
                            throw failure;
                        });
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            assertSame(failure, channel.write(payload).cause());
            assertEquals(0, payload.refCnt());
            java.util.Objects.requireNonNull(gate.pending).setSuccess();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A failed synchronization marker must release held application writes and close the unusable
     * connection.
     */
    @Test
    void failedResetReleasesQueuedApplicationWrites() {
        final EmbeddedChannel channel = transport();
        final NegotiationGate gate = new NegotiationGate();
        channel.pipeline()
                .addBefore(
                        channel.pipeline().context(SynchronizationLayer.class).name(),
                        "gate",
                        gate);
        final ByteBuf payload = Unpooled.buffer().writeByte(42);
        try {
            assertTrue(channel.write(SynchronizationLayer.SYNC_REQUEST_OBJECT).isSuccess());
            final var pending = channel.write(payload);
            assertFalse(pending.isDone());
            final IOException failure = new IOException("Reset marker rejected");
            java.util.Objects.requireNonNull(gate.pending).setFailure(failure);
            assertSame(failure, pending.cause());
            assertEquals(0, payload.refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Removal releases held writes and a late marker acknowledgement cannot restore them. */
    @Test
    void removedResetCannotResumeAfterLateAcknowledgement() {
        final EmbeddedChannel channel = transport();
        final NegotiationGate gate = new NegotiationGate();
        channel.pipeline()
                .addBefore(
                        channel.pipeline().context(SynchronizationLayer.class).name(),
                        "gate",
                        gate);
        final ByteBuf payload = Unpooled.buffer().writeByte(42);
        try {
            assertTrue(channel.write(SynchronizationLayer.SYNC_REQUEST_OBJECT).isSuccess());
            final var pending = channel.write(payload);
            channel.pipeline().remove(SynchronizationLayer.class);
            assertTrue(pending.isDone());
            assertFalse(pending.isSuccess());
            assertEquals(0, payload.refCnt());
            java.util.Objects.requireNonNull(gate.pending).setSuccess();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Peer-controlled lane and cursor fields are rejected without retaining their payloads. */
    @Test
    void invalidResetFieldsAreRejected() {
        final int[][] invalid = {{8, 1}, {255, 1}, {1, 1}, {2, -2}, {2, 0x1000000}};
        for (final int[] entry : invalid) {
            final EmbeddedChannel channel = transport();
            final FrameData packet =
                    marker(
                            Unpooled.buffer()
                                    .writeByte(1)
                                    .writeByte(entry[0])
                                    .writeInt(entry[1])
                                    .writeInt(3));
            try {
                assertThrows(
                        io.netty.handler.codec.CorruptedFrameException.class,
                        () -> channel.writeInbound(packet));
                assertEquals(0, packet.refCnt());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * A later invalid entry cannot partially advance an earlier valid lane or publish a reset
     * event.
     */
    @Test
    void malformedMarkerCannotPartiallyApply() {
        final EmbeddedChannel channel = transport();
        final ResetEvents events = new ResetEvents();
        channel.pipeline().addLast(events);
        final FrameData invalid =
                marker(
                        Unpooled.buffer()
                                .writeByte(2)
                                .writeByte(0)
                                .writeInt(4)
                                .writeByte(8)
                                .writeInt(7)
                                .writeInt(23));
        try {
            assertThrows(
                    io.netty.handler.codec.CorruptedFrameException.class,
                    () -> channel.writeInbound(invalid));
            assertEquals(0, events.resets);
            final ByteBuf bytes = Unpooled.buffer().writeByte(42);
            final FrameData payload =
                    FrameData.create(channel.alloc(), Constants.RAKNET_GAME_PACKET_ID, bytes);
            bytes.release();
            payload.setReliability(
                    network.ycc.raknet.packet.FramedPacket.Reliability.RELIABLE_ORDERED);
            payload.setOrderChannel(0);
            final network.ycc.raknet.frame.Frame frame =
                    network.ycc.raknet.frame.Frame.createOrdered(payload, 0, 0);
            payload.release();
            assertTrue(
                    channel.writeInbound(frame),
                    "Malformed reset must not skip the first application frame");
            final FrameData delivered = channel.readInbound();
            assertNotNull(delivered);
            delivered.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** A peer withholding the reset acknowledgement cannot retain unlimited application writes. */
    @Test
    void pendingResetCannotBypassTheWriteBudget() {
        final EmbeddedChannel channel = transport();
        final NegotiationGate gate = new NegotiationGate();
        channel.pipeline()
                .addBefore(
                        channel.pipeline().context(SynchronizationLayer.class).name(),
                        "gate",
                        gate);
        network.ycc.raknet.RakNet.config(channel).setMaxQueuedBytes(50);
        final ByteBuf first = Unpooled.buffer().writeByte(1);
        final ByteBuf second = Unpooled.buffer().writeByte(2);
        final ByteBuf rejected = Unpooled.buffer().writeByte(3);
        try {
            assertTrue(channel.write(SynchronizationLayer.SYNC_REQUEST_OBJECT).isSuccess());
            final var firstWrite = channel.write(first);
            final var secondWrite = channel.write(second);
            assertFalse(secondWrite.isDone());
            final var failed = channel.write(rejected);
            assertInstanceOf(io.netty.handler.codec.TooLongFrameException.class, failed.cause());
            assertSame(failed.cause(), firstWrite.cause());
            assertSame(failed.cause(), secondWrite.cause());
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertEquals(0, rejected.refCnt());
            assertFalse(channel.isOpen());
            java.util.Objects.requireNonNull(gate.pending).setSuccess();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** The application sees the phase-change event before packets made ready by the same reset. */
    @Test
    void resetNotifiesApplicationBeforeDeliveringQueuedPackets() {
        final EmbeddedChannel channel = transport();
        final ResetEvents events = new ResetEvents();
        channel.pipeline().addLast(events);
        final ByteBuf bytes = Unpooled.buffer().writeByte(42);
        final FrameData payload =
                FrameData.create(channel.alloc(), Constants.RAKNET_GAME_PACKET_ID, bytes);
        bytes.release();
        payload.setOrderChannel(0);
        final network.ycc.raknet.frame.Frame future =
                network.ycc.raknet.frame.Frame.createOrdered(payload, 6, 0);
        payload.release();
        try {
            assertFalse(channel.writeInbound(future));
            assertTrue(
                    channel.writeInbound(
                            marker(
                                    Unpooled.buffer()
                                            .writeByte(1)
                                            .writeByte(0)
                                            .writeInt(5)
                                            .writeInt(23))));
            assertEquals(1, events.resetsBeforeRead);
            final FrameData delivered = channel.readInbound();
            assertNotNull(delivered);
            delivered.release();
            assertNull(channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Counts semantic reset notifications independently of the parser and transport cursor
     * implementation.
     */
    private static final class ResetEvents extends io.netty.channel.ChannelInboundHandlerAdapter {
        /** Number of fully accepted reset markers observed by application handlers. */
        private int resets;

        /** Reset count captured at the instant an application payload arrives. */
        private int resetsBeforeRead;

        /**
         * Captures notification order while forwarding ownership of the application payload.
         *
         * @param context fixture's downstream application context
         * @param message owned packet forwarded to the test's inbound queue
         */
        @Override
        public void channelRead(final ChannelHandlerContext context, final Object message) {
            resetsBeforeRead = resets;
            context.fireChannelRead(message);
        }

        /**
         * Records reset events while preserving downstream notification behavior.
         *
         * @param context inbound application context
         * @param event reset marker or unrelated lifecycle notification
         */
        @Override
        public void userEventTriggered(final ChannelHandlerContext context, final Object event) {
            if (event instanceof SynchronizationLayer.SyncRequest) {
                resets++;
            }
            context.fireUserEventTriggered(event);
        }
    }

    /**
     * Builds the real transport handlers whose queues and cursors are inspected by synchronization.
     *
     * @return owned embedded transport with no sockets or wall-clock timers
     */
    private static EmbeddedChannel transport() {
        EmbeddedChannel channel = new RaknetChannel();
        channel.pipeline()
                .addLast(
                        new ReliabilityHandler(),
                        new FrameJoiner(),
                        new FrameOrderIn(),
                        new FrameOrderOut(),
                        new SynchronizationLayer(Constants.synchronizationIgnoredChannels()));
        return channel;
    }

    /**
     * Transfers an owned payload buffer into a SYNC frame and drops the caller's buffer reference.
     *
     * @param payload cursor data following the frame identifier
     * @return owned synchronization frame
     */
    private static FrameData marker(final ByteBuf payload) {
        try {
            return FrameData.create(payload.alloc(), Constants.RAKNET_SYNC_PACKET_ID, payload);
        } finally {
            payload.release();
        }
    }

    /**
     * Holds only the synchronization write so its eventual success or failure remains controlled by
     * the test.
     */
    private static final class CompletionGate extends ChannelOutboundHandlerAdapter {

        /**
         * Pending transport completion, deliberately left unfinished until the assertion checks it.
         */
        private @org.jspecify.annotations.Nullable ChannelPromise pending;

        /**
         * Retains the local marker's promise while allowing negotiation and ordinary frames to
         * pass.
         *
         * @param ctx outgoing context forwarding non-marker traffic
         * @param msg local synchronization marker or normal frame
         * @param promise original completion that must not be completed by the codec
         */
        @Override
        public void write(
                final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
            if (msg instanceof SynchronizationLayer.SyncRequest) {
                pending = promise;
            } else {
                assertSame(promise, ctx.write(msg, promise));
            }
        }
    }

    /** Delays the negotiation acknowledgement while releasing the consumed control frame. */
    private static final class NegotiationGate extends ChannelOutboundHandlerAdapter {

        /** Completion callback released explicitly after the first application write is queued. */
        private @org.jspecify.annotations.Nullable ChannelPromise pending;

        /**
         * Holds the first control write and forwards subsequent application frames unchanged.
         *
         * @param ctx outgoing context for application frames
         * @param msg negotiation marker or encoded application frame
         * @param promise transport completion for this write
         */
        @Override
        public void write(
                final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
            if (pending == null) {
                pending = promise;
                ReferenceCountUtil.release(msg);
            } else {
                assertSame(promise, ctx.write(msg, promise));
            }
        }
    }

    /** Supplies real RakNet configuration to the deterministic in-memory transport. */
    private static final class RaknetChannel extends EmbeddedChannel {

        /**
         * Lazy because EmbeddedChannel requests its configuration from the superclass constructor.
         */
        private @org.jspecify.annotations.Nullable ChannelConfig transportConfig;

        /**
         * Returns the transport settings expected by reliability and fragmentation handlers.
         *
         * @return this channel's persistent RakNet configuration
         */
        @Override
        public ChannelConfig config() {
            if (transportConfig == null) {
                transportConfig = new DefaultConfig(this);
            }
            return transportConfig;
        }
    }
}
