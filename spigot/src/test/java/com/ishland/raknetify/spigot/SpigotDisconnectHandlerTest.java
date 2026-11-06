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

package com.ishland.raknetify.spigot;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.BlockingOperationException;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Reproduces native blocking-close callbacks with a deliberately unfinished asynchronous transport
 * close.
 */
class SpigotDisconnectHandlerTest {
    /**
     * Proves the fixture catches the original event-loop wait instead of relying on a fast socket
     * close.
     */
    @Test
    void unguardedCallbackReproducesBlockingClose() {
        DelayedClose close = new DelayedClose();
        EmbeddedChannel channel = new EmbeddedChannel(close);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            ChannelPromise sent = nativeCallback(channel, failure);
            sent.setSuccess();
            assertInstanceOf(BlockingOperationException.class, failure.get());
            close.finish();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Holds the native callback until the actual close completes while delivering the terminal
     * bytes first.
     */
    @Test
    void terminalWriteClosesBeforeNativeCallback() {
        DelayedClose close = new DelayedClose();
        EmbeddedChannel channel = new EmbeddedChannel(close, new TerminalWrite());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            ChannelPromise sent = nativeCallback(channel, failure);
            channel.writeAndFlush(Unpooled.buffer().writeByte(42), sent);
            assertFalse(sent.isDone());
            assertTrue(channel.isOpen());
            var bytes = (io.netty.buffer.ByteBuf) channel.readOutbound();
            assertEquals(42, bytes.readUnsignedByte());
            bytes.release();
            close.finish();
            assertTrue(sent.isSuccess());
            assertFalse(channel.isOpen());
            assertNull(failure.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Preserves a failed packet write and releases its buffer even when native notification waits
     * for closure.
     */
    @Test
    void failedWriteRetainsCauseAfterClose() {
        DelayedClose close = new DelayedClose();
        IllegalStateException cause = new IllegalStateException("fixture write failure");
        EmbeddedChannel channel =
                new EmbeddedChannel(close, new FailedWrite(cause), new TerminalWrite());
        var bytes = Unpooled.buffer().writeByte(42);
        try {
            ChannelPromise sent = channel.newPromise();
            channel.writeAndFlush(bytes, sent);
            assertEquals(0, bytes.refCnt());
            assertFalse(sent.isDone());
            close.finish();
            assertSame(cause, sent.cause());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Keeps ordinary writes immediate and recognizes exactly the two supported terminal packet
     * types.
     */
    @Test
    void ordinaryWriteDoesNotClose() {
        EmbeddedChannel channel = new EmbeddedChannel(new SpigotDisconnectHandler());
        try {
            ChannelPromise sent = channel.newPromise();
            channel.writeAndFlush(Unpooled.buffer().writeByte(1), sent);
            assertTrue(sent.isSuccess());
            assertTrue(channel.isOpen());
            ReferenceCountUtil.release(channel.readOutbound());
            assertTrue(
                    SpigotDisconnectHandler.isDisconnect(
                            "net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket"));
            assertTrue(
                    SpigotDisconnectHandler.isDisconnect(
                            "net.minecraft.network.protocol.common.ClientboundDisconnectPacket"));
            assertFalse(
                    SpigotDisconnectHandler.isDisconnect(
                            "net.minecraft.network.protocol.game.ClientboundLoginPacket"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Models the native connection's open-channel check followed by a blocking close on its send
     * callback.
     *
     * @param channel embedded channel whose event loop runs the callback
     * @param failure captures callback exceptions so the regression asserts them instead of logging
     *     them
     * @return promise whose completion runs the native close sequence
     */
    private static ChannelPromise nativeCallback(
            final EmbeddedChannel channel, final AtomicReference<Throwable> failure) {
        ChannelPromise promise = channel.newPromise();
        promise.addListener(completed -> {
            try {
                if (channel.isOpen()) {
                    channel.close().awaitUninterruptibly();
                }
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        return promise;
    }

    /**
     * Holds a close request until the test explicitly finishes it, without depending on network
     * timing.
     */
    private static final class DelayedClose extends ChannelDuplexHandler {
        /** Context used to forward the held close below this handler. */
        private ChannelHandlerContext context;

        /** Original close completion that remains pending until finish is called. */
        private ChannelPromise promise;

        /**
         * Retains one asynchronous close while allowing repeated closes after completion to finish
         * normally.
         *
         * @param context handler receiving the close request
         * @param promise original transport close result
         */
        @Override
        public void close(final ChannelHandlerContext context, final ChannelPromise promise) {
            if (!context.channel().isOpen()) {
                context.close(promise);
                return;
            }
            this.context = context;
            this.promise = promise;
        }

        /**
         * Completes the actual channel closure at the deterministic point chosen by the regression.
         */
        private void finish() {
            context.close(promise);
        }
    }

    /**
     * Routes synthetic payload bytes through the same terminal-write implementation used for native
     * packets.
     */
    private static final class TerminalWrite extends ChannelDuplexHandler {
        /**
         * Exercises the close sequencing without bundling a fake Minecraft packet class.
         *
         * @param context outbound fixture context
         * @param message owned synthetic packet bytes
         * @param promise original native completion callback
         */
        @Override
        public void write(
                final ChannelHandlerContext context,
                final Object message,
                final ChannelPromise promise) {
            SpigotDisconnectHandler.writeAndClose(context, message, promise);
        }
    }

    /** Models a transport write failure while retaining Netty's buffer-ownership contract. */
    private static final class FailedWrite extends ChannelDuplexHandler {
        /** Exact failure that the caller must observe after closure. */
        private final Throwable cause;

        /**
         * Retains the synthetic failure for one test pipeline.
         *
         * @param cause expected failure identity
         */
        private FailedWrite(final Throwable cause) {
            this.cause = cause;
        }

        /**
         * Releases the rejected bytes and fails the actual transport write.
         *
         * @param context outbound fixture context
         * @param message buffer whose ownership was transferred by the caller
         * @param promise transport write result
         */
        @Override
        public void write(
                final ChannelHandlerContext context,
                final Object message,
                final ChannelPromise promise) {
            ReferenceCountUtil.release(message);
            promise.setFailure(cause);
        }
    }
}
