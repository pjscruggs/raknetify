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

import static org.junit.jupiter.api.Assertions.*;

/** Guards synchronization frame ownership and propagation of the transport's actual write result. */
class SynchronizationOwnershipTest {

    /** Releases both the consumed SYNC frame and its derived buffer after applying an ordering reset. */
    @Test
    void consumedMarkerReleasesEveryReference() {
        EmbeddedChannel channel = transport();
        FrameData marker = marker(Unpooled.buffer().writeByte(1).writeByte(2).writeInt(4).writeInt(23));
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
            assertThrows(IndexOutOfBoundsException.class, () -> channel.writeInbound(marker));
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
        FrameData packet = FrameData.create(channel.alloc(), Constants.RAKNET_GAME_PACKET_ID, bytes);
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

    /** Does not turn a pending or failed synchronization write into an early successful completion. */
    @Test
    void synchronizationPromiseBelongsToTransport() {
        CompletionGate gate = new CompletionGate();
        RakNetSimpleMultiChannelCodec codec = new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                .addHandler((buffer, silent) -> 2);
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            ((FrameData) channel.readOutbound()).release();
            ChannelPromise result = channel.newPromise();
            channel.write(SynchronizationLayer.SYNC_REQUEST_OBJECT, result);
            assertSame(result, gate.pending);
            assertFalse(result.isDone(), "The codec must wait for the downstream write result");
            IOException failure = new IOException("Fixture transport rejected the synchronization marker");
            gate.pending.setFailure(failure);
            assertSame(failure, result.cause());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Keeps a packet waiting for negotiation on the same channel as packets written after acknowledgement. */
    @Test
    void negotiationDoesNotSplitOneOrderedStreamAcrossChannels() {
        NegotiationGate gate = new NegotiationGate();
        RakNetSimpleMultiChannelCodec codec = new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID)
                .addHandler((buffer, silent) -> 1);
        EmbeddedChannel channel = new EmbeddedChannel(gate, codec);
        try {
            channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            channel.writeOutbound(Unpooled.buffer().writeByte(1));
            assertNull(channel.readOutbound(), "Packet must wait for the marker acknowledgement");
            gate.pending.setSuccess();
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
     * Builds the real transport handlers whose queues and cursors are inspected by synchronization.
     *
     * @return owned embedded transport with no sockets or wall-clock timers
     */
    private static EmbeddedChannel transport() {
        EmbeddedChannel channel = new RaknetChannel();
        channel.pipeline().addLast(new ReliabilityHandler(), new FrameJoiner(), new FrameOrderIn(),
                new FrameOrderOut(), new SynchronizationLayer(Constants.SYNC_IGNORE_CHANNELS));
        return channel;
    }

    /**
     * Transfers an owned payload buffer into a SYNC frame and drops the caller's buffer reference.
     *
     * @param payload cursor data following the frame identifier
     * @return owned synchronization frame
     */
    private static FrameData marker(ByteBuf payload) {
        try {
            return FrameData.create(payload.alloc(), Constants.RAKNET_SYNC_PACKET_ID, payload);
        } finally {
            payload.release();
        }
    }

    /** Holds only the synchronization write so its eventual success or failure remains controlled by the test. */
    private static final class CompletionGate extends ChannelOutboundHandlerAdapter {

        /** Pending transport completion, deliberately left unfinished until the assertion checks it. */
        private ChannelPromise pending;

        /**
         * Retains the local marker's promise while allowing negotiation and ordinary frames to pass.
         *
         * @param ctx outgoing context forwarding non-marker traffic
         * @param msg local synchronization marker or normal frame
         * @param promise original completion that must not be completed by the codec
         */
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg == SynchronizationLayer.SYNC_REQUEST_OBJECT) pending = promise;
            else ctx.write(msg, promise);
        }
    }

    /** Delays the negotiation acknowledgement while releasing the consumed control frame. */
    private static final class NegotiationGate extends ChannelOutboundHandlerAdapter {

        /** Completion callback released explicitly after the first application write is queued. */
        private ChannelPromise pending;

        /**
         * Holds the first control write and forwards subsequent application frames unchanged.
         *
         * @param ctx outgoing context for application frames
         * @param msg negotiation marker or encoded application frame
         * @param promise transport completion for this write
         */
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (pending == null) {
                pending = promise;
                ReferenceCountUtil.release(msg);
            } else {
                ctx.write(msg, promise);
            }
        }
    }

    /** Supplies real RakNet configuration to the deterministic in-memory transport. */
    private static final class RaknetChannel extends EmbeddedChannel {

        /** Lazy because EmbeddedChannel requests its configuration from the superclass constructor. */
        private ChannelConfig transportConfig;

        /**
         * Returns the transport settings expected by reliability and fragmentation handlers.
         *
         * @return this channel's persistent RakNet configuration
         */
        @Override
        public ChannelConfig config() {
            if (transportConfig == null) transportConfig = new DefaultConfig(this);
            return transportConfig;
        }
    }
}
