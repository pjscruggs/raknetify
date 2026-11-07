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

package com.ishland.raknetify.bungee.connection;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;

import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.packet.KeepAlive;

import network.ycc.raknet.config.DefaultConfig;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Exercises real BungeeCord packet wrappers and stream prefixes at the transport boundary. */
class ProxyPacketOwnershipTest {

    /** Stripping the TCP prefix preserves the original write completion and payload ownership. */
    @Test
    void stripsPrefixWithoutCopyingOrLosingCompletion() {
        EmbeddedChannel channel = new EmbeddedChannel(StripFrameHandler.INSTANCE);
        ByteBuf framed = Unpooled.buffer().writeByte(2).writeShort(0x1234);
        try {
            ChannelFuture written = channel.writeAndFlush(framed);
            assertTrue(written.isSuccess());
            ByteBuf payload = channel.readOutbound();
            assertNotNull(payload);
            try {
                assertEquals(2, payload.readableBytes());
                assertEquals(0x1234, payload.readUnsignedShort());
                assertEquals(1, framed.refCnt());
            } finally {
                payload.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        assertEquals(0, framed.refCnt());
    }

    /** A truncated VarInt fails its write and releases the buffer that never reached transport. */
    @Test
    void malformedPrefixReleasesRejectedBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(StripFrameHandler.INSTANCE);
        ByteBuf truncated = Unpooled.buffer().writeByte(0x80);
        try {
            ChannelFuture written = channel.writeAndFlush(truncated);
            assertTrue(written.isDone());
            assertFalse(written.isSuccess());
            assertInstanceOf(IndexOutOfBoundsException.class, written.cause());
            assertEquals(0, truncated.refCnt());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Swallowing a wrapped backend keepalive releases its encoded bytes and replies with its ID.
     */
    @Test
    void consumesWrapperAndRepliesWithSameKeepaliveId() {
        EmbeddedChannel client = new RaknetChannel();
        EmbeddedChannel backend =
                new EmbeddedChannel(new RakNetBungeeServerChannelEventListener(client));
        ByteBuf encoded = Unpooled.buffer().writeLong(42);
        try {
            assertFalse(
                    backend.writeInbound(
                            new PacketWrapper(new KeepAlive(42), encoded, Protocol.GAME)));
            assertEquals(0, encoded.refCnt());
            backend.runScheduledPendingTasks();
            KeepAlive reply = backend.readOutbound();
            assertNotNull(reply);
            assertEquals(42, reply.getRandomId());
            assertNull(backend.readInbound());
        } finally {
            backend.finishAndReleaseAll();
            client.finishAndReleaseAll();
        }
    }

    /** Supplies transport RTT configuration without creating a socket or event-loop thread. */
    private static final class RaknetChannel extends EmbeddedChannel {

        /** Initialized lazily because the superclass requests configuration during construction. */
        private @Nullable DefaultConfig transportConfig;

        /**
         * Keeps the channel recognizable as RakNet while making keepalive replies immediately due.
         *
         * @return persistent zero-RTT transport configuration
         */
        @Override
        public ChannelConfig config() {
            if (transportConfig == null) {
                transportConfig = new DefaultConfig(this);
                transportConfig.setRTTNanos(0);
            }
            return transportConfig;
        }
    }
}
