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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Owns delayed proxy replies until delivery or connection cleanup, on the channel's event loop. */
public class DelayedPacketSender extends ChannelDuplexHandler {

    /**
     * Bounds retained replies even when a backend sends keepalives faster than the measured RTT.
     */
    private static final int MAX_PENDING = 64;

    /** Replies still owned by this handler rather than the outbound pipeline. */
    private final Set<PendingWrite> pending = new HashSet<>();

    /** Prevents an obsolete handler from scheduling writes after disconnect or removal. */
    private boolean closed;

    /**
     * Takes ownership of one reply and schedules its transfer to the outbound pipeline. Calls must
     * originate on this context's event loop. Rejected replies are released before failure returns.
     *
     * @param ctx backend context that will send the reply
     * @param message reply whose reference ownership transfers to this handler
     * @param delayNanos nonnegative delay derived from the client RTT
     * @throws RejectedExecutionException if cleanup has started, the queue is full or scheduling
     *     fails
     */
    protected final void replyAfter(
            final ChannelHandlerContext ctx, final Object message, final long delayNanos) {
        if (closed || pending.size() >= MAX_PENDING) {
            ReferenceCountUtil.release(message);
            throw new RejectedExecutionException("Delayed reply queue is closed or full");
        }
        final PendingWrite write = new PendingWrite(ctx, message);
        pending.add(write);
        try {
            write.task = ctx.executor().schedule(write, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException failure) {
            pending.remove(write);
            ReferenceCountUtil.release(message);
            throw failure;
        }
    }

    /**
     * Releases replies that no longer have a live recipient before forwarding the disconnect.
     *
     * @param ctx disconnected backend context
     * @throws Exception if downstream disconnect handling fails
     */
    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
        cancelPending();
        super.channelInactive(ctx);
    }

    /**
     * Cancels retained work when the proxy replaces this handler without closing the connection.
     *
     * @param ctx removed backend context
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        cancelPending();
    }

    /** Cancels timers and releases each unsent message exactly once, including repeated cleanup. */
    private void cancelPending() {
        closed = true;
        for (PendingWrite write : pending) {
            if (write.task != null) {
                write.task.cancel(false);
            }
            ReferenceCountUtil.release(write.message);
        }
        pending.clear();
    }

    /** A single event-loop-owned reply whose timer never outlives handler cleanup. */
    private final class PendingWrite implements Runnable {

        /** Context from which the reply must resume outbound processing. */
        private final ChannelHandlerContext context;

        /** Payload reference owned while this entry remains in the pending set. */
        private final Object message;

        /** Cancellation handle, assigned immediately after the event loop accepts the timer. */
        private @Nullable ScheduledFuture<?> task;

        /**
         * Captures the reply without retaining another reference to its contents.
         *
         * @param context backend pipeline position for the eventual write
         * @param message payload reference transferred by the caller
         */
        private PendingWrite(final ChannelHandlerContext context, final Object message) {
            this.context = context;
            this.message = message;
        }

        /** Transfers an outstanding reply once and reports asynchronous write failures. */
        @Override
        public void run() {
            if (pending.remove(this)) {
                context.writeAndFlush(message).addListener(RakNet.INTERNAL_WRITE_LISTENER);
            }
        }
    }
}
