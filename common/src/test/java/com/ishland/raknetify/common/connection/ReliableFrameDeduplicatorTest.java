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

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelConfig;
import io.netty.handler.codec.TooLongFrameException;
import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.config.DefaultConfig;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.pipeline.FrameJoiner;
import network.ycc.raknet.pipeline.FrameOrderIn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises retransmitted reliable frames before reassembly and application packet delivery. */
class ReliableFrameDeduplicatorTest {
    /** A lost acknowledgement must not make Paper receive the same keepalive challenge twice. */
    @Test
    void retransmittedUnorderedFrameIsDeliveredOnce() {
        EmbeddedChannel channel = new EmbeddedChannel(new ReliableFrameDeduplicator(), new FrameJoiner(), new FrameOrderIn());
        try {
            assertTrue(channel.writeInbound(frame(42)));
            FrameData first = channel.readInbound();
            first.release();
            assertFalse(channel.writeInbound(frame(42)), "A retransmitted reliable ID reached the application twice");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Equal payloads with distinct IDs remain separate messages, even when delivery order is reversed. */
    @Test
    void distinctAndWidelyReorderedIdsAreNotDiscarded() {
        EmbeddedChannel channel = transport();
        try {
            deliver(channel, 1, true);
            deliver(channel, 1_000_000, true);
            deliver(channel, 3, true);
            deliver(channel, 2, true);
            deliver(channel, 3, false);
            deliver(channel, 1, false);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Counter wrap reuses IDs only after retiring the previous epoch, while late frames still retain their epoch. */
    @Test
    void serialWrapAndLateFramesPreserveIdentity() {
        EmbeddedChannel channel = transport();
        try {
            deliver(channel, 0, true);
            deliver(channel, 0x7fffff, true);
            deliver(channel, 0xfffffe, true);
            deliver(channel, 0, true);
            deliver(channel, 0xffffff, true);
            deliver(channel, 0xfffffe, false);
            deliver(channel, 0, false);
            deliver(channel, 1, true);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Unreliable packets carry no reliable ID, so identical index fields cannot be used to suppress them. */
    @Test
    void unreliablePacketsAreNeverDeduplicated() {
        EmbeddedChannel channel = transport();
        try {
            for (int i = 0; i < 2; i++) {
                Frame packet = frame(0);
                FrameData payload = packet.retainedFrameData();
                payload.setReliability(FrameData.Reliability.UNRELIABLE);
                payload.release();
                assertTrue(channel.writeInbound(packet));
                ((FrameData) channel.readInbound()).release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Retrying all fragments after completion must neither redeliver the packet nor leave a new incomplete builder. */
    @Test
    void retransmittedFragmentsAreConsumedBeforeReassembly() {
        EmbeddedChannel channel = transport();
        var payload = Unpooled.buffer().writeZero(300);
        FrameData data = FrameData.create(UnpooledByteBufAllocator.DEFAULT, 0xfe, payload);
        payload.release();
        data.setReliability(FrameData.Reliability.RELIABLE);
        Frame packet = Frame.create(data);
        data.release();
        var fragments = new ArrayList<Object>();
        packet.fragment(7, 128, 12, fragments);
        packet.release();
        try {
            for (Object fragment : fragments) channel.writeInbound(((Frame) fragment).retain());
            FrameData delivered = channel.readInbound();
            assertNotNull(delivered);
            delivered.release();
            for (Object fragment : fragments) assertFalse(channel.writeInbound(((Frame) fragment).retain()));
            assertNull(channel.readInbound());
            for (Object fragment : fragments) assertEquals(1, ((Frame) fragment).refCnt());
        } finally {
            channel.finishAndReleaseAll();
            fragments.forEach(fragment -> ((Frame) fragment).release());
        }
    }

    /** Excessive sparse history fails the connection explicitly instead of silently forgetting old reliable IDs. */
    @Test
    void sparseHistoryHasABoundedFailClosedLimit() {
        EmbeddedChannel channel = transport();
        try {
            for (int i = 0; i < 65536; i++) deliver(channel, i * 2, true);
            assertThrows(TooLongFrameException.class, () -> channel.writeInbound(frame(131072)));
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Checks both delivery count and reference ownership after one synthetic network arrival.
     * @param channel isolated receive pipeline
     * @param index reliable identity of this arrival
     * @param expected whether this ID has not been seen before
     */
    private static void deliver(EmbeddedChannel channel, int index, boolean expected) {
        assertEquals(expected, channel.writeInbound(frame(index)));
        FrameData result = channel.readInbound();
        if (expected) {
            assertNotNull(result);
            result.release();
        } else {
            assertNull(result);
        }
    }

    /**
     * Places duplicate filtering ahead of the transport's real reassembly and ordering handlers.
     * @return owned pipeline with no external socket or scheduler
     */
    private static EmbeddedChannel transport() {
        EmbeddedChannel channel = new TransportChannel();
        channel.pipeline().addLast(new ReliableFrameDeduplicator(), new FrameJoiner(), new FrameOrderIn());
        return channel;
    }

    /** Supplies actual RakNet queue limits to the native fragment decoder. */
    private static final class TransportChannel extends EmbeddedChannel {
        /** Lazy initialization also supports config queries from the superclass constructor. */
        private ChannelConfig transportConfig;

        /**
         * Returns the same configuration for every fragment rather than substituting a TCP channel config.
         * @return persistent transport queue and MTU settings
         */
        @Override
        public ChannelConfig config() {
            if (transportConfig == null) transportConfig = new DefaultConfig(this);
            return transportConfig;
        }
    }

    /**
     * Creates a wire-equivalent unordered frame with an explicit reliable message index.
     * @param index identity retained when the sender retransmits the frame in another datagram
     * @return owned frame transferred to the inbound pipeline
     */
    private static Frame frame(int index) {
        var payload = Unpooled.buffer().writeLong(240496);
        FrameData data = FrameData.create(UnpooledByteBufAllocator.DEFAULT, 0xfe, payload);
        payload.release();
        data.setReliability(FrameData.Reliability.RELIABLE);
        Frame frame = Frame.create(data);
        data.release();
        frame.setReliableIndex(index);
        return frame;
    }
}
