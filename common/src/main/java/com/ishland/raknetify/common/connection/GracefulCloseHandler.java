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
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a normal transport close behind outstanding application writes, including their reliable
 * retransmissions.
 */
public final class GracefulCloseHandler extends ChannelDuplexHandler {

    /** Maximum extra drain time before the existing transport close procedure takes over. */
    private final long timeoutNanos;

    /** Application promises still waiting for transport completion on this channel's event loop. */
    private int pendingWrites;

    /** First close request owns completion and causes subsequent writes to be rejected. */
    private ChannelPromise closingPromise;

    /**
     * Ensures drain completion, timeout and external closure cannot forward the same close twice.
     */
    private boolean closeForwarded;

    /** Bounded drain deadline, cancelled as soon as normal transport closing begins. */
    private ScheduledFuture<?> timeout;

    /** Uses a three-second drain budget before the transport's existing close timeout. */
    public GracefulCloseHandler() {
        this(TimeUnit.SECONDS.toNanos(3));
    }

    /**
     * Allows deterministic deadline tests without sleeping or changing the production timeout.
     *
     * @param timeoutNanos nonnegative drain budget, with zero expiring on the next scheduled-task
     *     run
     * @throws IllegalArgumentException if the requested duration is negative
     */
    GracefulCloseHandler(final long timeoutNanos) {
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("Negative close drain timeout");
        }
        this.timeoutNanos = timeoutNanos;
    }

    /**
     * Tracks actual write completion rather than queue acceptance. RakNet resolves reliable writes
     * after acknowledgement.
     *
     * @param context application-side transport context
     * @param message owned outbound frame or control message
     * @param promise caller's write result, preserved even when it originally discards completion
     *     notifications
     */
    @Override
    public void write(
            final ChannelHandlerContext context,
            final Object message,
            final ChannelPromise promise) {
        if (this.closingPromise != null) {
            ReferenceCountUtil.release(message);
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        ChannelPromise tracked = promise.unvoid();
        this.pendingWrites++;
        tracked.addListener(future -> writeCompleted(context));
        context.write(message, tracked);
    }

    /**
     * Releases a pending write and starts transport closing only after every earlier write has
     * completed.
     *
     * @param context channel event loop receiving the write promise notification
     */
    private void writeCompleted(final ChannelHandlerContext context) {
        this.pendingWrites--;
        if (this.pendingWrites == 0 && this.closingPromise != null) {
            finishClose(context);
        }
    }

    /**
     * Drains the existing writes before sending RakNet's close notification on its independent
     * ordering channel.
     *
     * @param context application channel whose lower transport handlers perform the eventual close
     * @param promise close completion observed by the caller
     */
    @Override
    public void close(final ChannelHandlerContext context, final ChannelPromise promise) {
        if (this.closingPromise != null) {
            relayClose(promise);
            return;
        }
        this.closingPromise = promise.unvoid();
        if (this.pendingWrites == 0) {
            finishClose(context);
            return;
        }
        this.timeout =
                context.executor()
                        .schedule(
                                () -> finishClose(context),
                                this.timeoutNanos,
                                TimeUnit.NANOSECONDS);
        // Bypass application flush suppression so the last reason is sent before the drain
        // deadline.
        context.flush();
    }

    /**
     * Makes repeated close requests observe the first request's outcome without extending its
     * deadline.
     *
     * @param promise additional caller's close completion notification
     */
    private void relayClose(final ChannelPromise promise) {
        this.closingPromise.addListener(future -> {
            if (future.isSuccess()) {
                promise.trySuccess();
            } else {
                promise.tryFailure(future.cause());
            }
        });
    }

    /**
     * Forwards close once and removes the scheduled drain task on every completion path.
     *
     * @param context lower pipeline entry point for the actual transport close
     */
    private void finishClose(final ChannelHandlerContext context) {
        if (this.closeForwarded) {
            return;
        }
        this.closeForwarded = true;
        if (this.timeout != null) {
            this.timeout.cancel(false);
        }
        context.close(this.closingPromise);
    }

    /**
     * Completes application cleanup when the peer or a lower transport handler closes
     * independently. Threaded RakNet channels forward socket inactivity without closing their own
     * application future.
     *
     * @param context inactive application channel
     */
    @Override
    public void channelInactive(final ChannelHandlerContext context) {
        if (this.closingPromise != null) {
            finishClose(context);
        } else {
            context.close();
        }
        context.fireChannelInactive();
    }

    /**
     * Prevents removing this handler from stranding an already requested close or its scheduled
     * task.
     *
     * @param context removed handler's remaining connection to the outbound pipeline
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext context) {
        if (this.closingPromise != null) {
            finishClose(context);
        }
    }
}
