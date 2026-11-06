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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Checks acknowledgement ordering, bounded shutdown and ownership when writes race a normal RakNet close. */
class GracefulCloseHandlerTest {

    /** Waits for every prior write, even when a later write completes first, and rejects new data while draining. */
    @Test
    void waitsForAllWritesAndRejectsNewOnes() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler(), new NoFlush());
        try {
            ChannelFuture first = channel.write(Unpooled.buffer().writeByte(1));
            ChannelFuture second = channel.write(Unpooled.buffer().writeByte(2));
            ChannelFuture closed = channel.close();
            ChannelFuture repeated = channel.close();
            assertFalse(closed.isDone());
            assertEquals(0, transport.closes);
            assertEquals(1, transport.flushes);
            ByteBuf late = Unpooled.buffer().writeByte(3);
            assertInstanceOf(ClosedChannelException.class, channel.write(late).cause());
            assertEquals(0, late.refCnt());
            transport.complete(1, null);
            assertTrue(second.isSuccess());
            assertFalse(first.isDone());
            assertFalse(closed.isDone());
            transport.complete(0, null);
            assertTrue(first.isSuccess());
            assertTrue(closed.isSuccess());
            assertTrue(repeated.isSuccess());
            assertEquals(1, transport.closes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Preserves a write failure and closes once all other application writes have also finished. */
    @Test
    void writeFailureDoesNotStrandClose() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler());
        try {
            ChannelFuture write = channel.write(Unpooled.buffer().writeByte(1));
            ChannelFuture closed = channel.close();
            IOException failure = new IOException("fixture send failure");
            transport.complete(0, failure);
            assertSame(failure, write.cause());
            assertTrue(closed.isSuccess());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Forces normal transport cleanup when a peer never acknowledges the pending message. */
    @Test
    void deadlineBoundsUnacknowledgedWrites() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler(0));
        ByteBuf bytes = Unpooled.buffer().writeByte(1);
        try {
            ChannelFuture write = channel.write(bytes);
            ChannelFuture closed = channel.close();
            channel.runScheduledPendingTasks();
            assertTrue(closed.isSuccess());
            assertInstanceOf(ClosedChannelException.class, write.cause());
            assertEquals(0, bytes.refCnt());
            assertEquals(1, transport.closes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Tracks fire-and-forget writes rather than allowing their discarded promises to bypass the drain. */
    @Test
    void voidPromiseStillDrains() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler());
        try {
            channel.write(Unpooled.buffer().writeByte(1), channel.voidPromise());
            ChannelFuture closed = channel.close();
            assertFalse(closed.isDone());
            transport.complete(0, null);
            assertTrue(closed.isSuccess());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Removing an active drain handler cannot abandon a close request or leave a deadline task behind. */
    @Test
    void removalCompletesPendingClose() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler());
        try {
            channel.write(Unpooled.buffer().writeByte(1));
            ChannelFuture closed = channel.close();
            channel.pipeline().remove(GracefulCloseHandler.class);
            assertTrue(closed.isSuccess());
            assertEquals(1, transport.closes);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Unused connections retain immediate normal close behavior. */
    @Test
    void closesImmediatelyWithoutPendingWrites() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler());
        assertTrue(channel.close().isSuccess());
        assertEquals(1, transport.closes);
        channel.finishAndReleaseAll();
    }

    /** An independent peer or transport close completes the outstanding request without waiting for its deadline. */
    @Test
    void externalCloseCompletesDrain() {
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = new EmbeddedChannel(transport, new GracefulCloseHandler());
        ByteBuf bytes = Unpooled.buffer().writeByte(1);
        try {
            ChannelFuture write = channel.write(bytes);
            ChannelFuture closed = channel.close();
            channel.unsafe().close(channel.newPromise());
            channel.runPendingTasks();
            assertTrue(closed.isSuccess());
            assertInstanceOf(ClosedChannelException.class, write.cause());
            assertEquals(0, bytes.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Simulates transport acknowledgements separately from accepting an application write. */
    private static final class HeldTransport extends ChannelOutboundHandlerAdapter {

        /** Owned messages retained until an explicit acknowledgement or actual transport close. */
        private final List<Object> messages = new ArrayList<>();
        /** Caller promises completed by the test in deliberately different acknowledgement orders. */
        private final List<ChannelPromise> promises = new ArrayList<>();
        /** Number of actual close attempts reaching the underlying transport. */
        private int closes;
        /** Explicit final flushes reaching transport despite application flush suppression. */
        private int flushes;

        /**
         * Retains ownership until the witness supplies an acknowledgement.
         * @param context transport context, unused until close
         * @param message owned outbound data
         * @param promise completion to be delayed
         */
        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            this.messages.add(message);
            this.promises.add(promise);
        }

        /**
         * Records the drain's explicit flush without manufacturing any acknowledgement.
         * @param context lower transport context
         */
        @Override public void flush(ChannelHandlerContext context) {
            this.flushes++;
            context.flush();
        }

        /**
         * Releases one retained message before notifying its original caller of the transport result.
         * @param index original write position
         * @param failure null for acknowledgement or the real transport failure to propagate
         */
        private void complete(int index, Throwable failure) {
            ReferenceCountUtil.release(this.messages.set(index, null));
            if (failure == null) this.promises.get(index).trySuccess();
            else this.promises.get(index).tryFailure(failure);
        }

        /**
         * Fails and releases any unacknowledged frames when normal transport shutdown begins.
         * @param context native embedded-channel close entry point
         * @param promise caller's actual close completion
         */
        @Override public void close(ChannelHandlerContext context, ChannelPromise promise) {
            this.closes++;
            for (int i = 0; i < this.messages.size(); i++) {
                if (this.messages.get(i) != null) complete(i, new ClosedChannelException());
            }
            context.close(promise);
        }
    }
}
