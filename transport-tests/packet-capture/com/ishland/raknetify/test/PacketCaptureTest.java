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

package com.ishland.raknetify.test;

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.fabric.common.connection.MultiChannellingPacketCapture;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runs the same reentrant packet-classification checks against both Fabric implementations. */
class PacketCaptureTest {

    /**
     * A nested successful write must not erase the packet class still needed by its outer encoder.
     */
    @Test
    void nestedWriteRestoresOuterCapture() {
        verifyNestedWrite(false);
    }

    /** A failed nested write restores the outer capture and still reports its own failure. */
    @Test
    void nestedFailureRestoresOuterCapture() {
        verifyNestedWrite(true);
    }

    /**
     * Exercises actual outbound reentrancy and checks every capture observation and write result.
     *
     * @param rejectNested whether the inner encoder deliberately rejects its packet
     */
    private static void verifyNestedWrite(final boolean rejectNested) {
        MultiChannellingPacketCapture capture = new MultiChannellingPacketCapture();
        NestedWriter encoder = new NestedWriter(capture, rejectNested);
        EmbeddedChannel channel = new EmbeddedChannel(encoder, capture);
        try {
            assertTrue(channel.writeAndFlush("outer").isSuccess());
            assertEquals(List.of(String.class, Integer.class, String.class), encoder.observed);
            assertNull(capture.getPacketClass());
            if (!rejectNested) {
                assertEquals(Integer.valueOf(7), channel.readOutbound());
            }
            assertEquals("outer", channel.readOutbound());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Generates one nested write before the outer packet reaches the transport. */
    private static final class NestedWriter extends ChannelOutboundHandlerAdapter {

        /** Per-connection capture inspected from inside the encoder. */
        private final MultiChannellingPacketCapture capture;

        /** Whether the inner packet fails before it is passed to the transport. */
        private final boolean rejectNested;

        /** Ordered observations from the outer write, inner write and resumed outer write. */
        private final List<Class<?>> observed = new ArrayList<>();

        /** Failure identity that must survive the inner write's promise propagation. */
        private final IllegalStateException failure =
                new IllegalStateException("nested encoder failure");

        /**
         * Selects the encoder outcome without changing the actual capture implementation.
         *
         * @param capture installed packet-class capture
         * @param rejectNested whether to fail the inner packet
         */
        private NestedWriter(
                final MultiChannellingPacketCapture capture, final boolean rejectNested) {
            this.capture = capture;
            this.rejectNested = rejectNested;
        }

        /**
         * Records packet identity around a nested write before forwarding the outer packet.
         *
         * @param ctx encoder context placed below the capture in outbound order
         * @param msg outer string or inner integer payload
         * @param promise original completion notification for this particular write
         * @throws IllegalStateException for the deliberately rejected inner packet
         * @throws Exception if superclass forwarding fails
         */
        @Override
        public void write(
                final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                throws Exception {
            observed.add(Objects.requireNonNull(capture.getPacketClass()));
            if (msg instanceof Integer && rejectNested) {
                throw failure;
            }
            if (msg instanceof String) {
                ChannelFuture nested = ctx.channel().writeAndFlush(7);
                if (rejectNested) {
                    assertSame(failure, nested.cause());
                } else {
                    assertTrue(nested.isSuccess());
                }
                observed.add(Objects.requireNonNull(capture.getPacketClass()));
            }
            super.write(ctx, msg, promise);
        }
    }
}
