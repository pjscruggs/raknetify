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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.config.DefaultConfig;
import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.api.Test;

/**
 * Checks transport-specific handler removal without opening sockets or relying on garbage
 * collection.
 */
class TcpHandlerCompatibilityTest {

    /** Name installed by Polymer's shared-port HTTP hosting integration. */
    private static final String HTTP_DETECTOR = "polymer:autohost/protocol_switcher";

    /** Ensures a frame reaches the application with exactly one remaining ownership reference. */
    @Test
    void raknetFramesBypassHttpDetection() {
        EmbeddedChannel channel = new RaknetChannel();
        ByteBuf payload = Unpooled.buffer().writeByte(42);
        FrameData frame = FrameData.create(channel.alloc(), 253, payload);
        payload.release();
        try {
            channel.pipeline().addFirst(HTTP_DETECTOR, new ChannelInboundHandlerAdapter());
            RakNetConnectionUtil.removeTcpOnlyHandlers(channel);
            assertNull(channel.pipeline().get(HTTP_DETECTOR));
            assertTrue(channel.writeInbound(frame));
            assertSame(frame, channel.readInbound());
            assertEquals(1, frame.refCnt());
        } finally {
            frame.release();
            channel.finishAndReleaseAll();
        }
        assertEquals(0, frame.refCnt());
    }

    /**
     * Keeps the TCP detector installed and preserves the HTTP request bytes and their ownership.
     */
    @Test
    void tcpHttpDetectionRemainsInstalled() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelInboundHandlerAdapter detector = new ChannelInboundHandlerAdapter();
        ByteBuf request = Unpooled.buffer().writeByte('G').writeByte('E').writeByte('T');
        try {
            channel.pipeline().addFirst(HTTP_DETECTOR, detector);
            RakNetConnectionUtil.removeTcpOnlyHandlers(channel);
            assertSame(detector, channel.pipeline().get(HTTP_DETECTOR));
            assertTrue(channel.writeInbound(request));
            assertSame(request, channel.readInbound());
            assertEquals('G', request.getByte(0));
            assertEquals(1, request.refCnt());
        } finally {
            request.release();
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Allows repeated initialization and servers without Polymer while retaining unrelated
     * handlers.
     */
    @Test
    void missingDetectorAndRepeatedCallsAreHarmless() {
        EmbeddedChannel channel = new RaknetChannel();
        ChannelInboundHandlerAdapter other = new ChannelInboundHandlerAdapter();
        try {
            channel.pipeline().addLast("other-mod", other);
            RakNetConnectionUtil.removeTcpOnlyHandlers(channel);
            RakNetConnectionUtil.removeTcpOnlyHandlers(channel);
            assertSame(other, channel.pipeline().get("other-mod"));
            assertFalse(channel.finish());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Supplies a real RakNet configuration to Netty's deterministic in-memory channel. */
    private static final class RaknetChannel extends EmbeddedChannel {

        /**
         * Lazily initialized because EmbeddedChannel asks for its configuration during
         * construction.
         */
        private ChannelConfig transportConfig;

        /**
         * Selects the same configuration marker used by the production RakNet channel
         * implementations.
         *
         * @return persistent RakNet configuration belonging to this channel
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
