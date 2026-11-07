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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Verifies delayed replies transfer ownership once and cannot survive handler cleanup. */
class DelayedPacketSenderTest {

    /** A completed timer transfers the original buffer and never releases the outbound owner. */
    @Test
    void sendsOnceAndTransfersOwnership() {
        DelayedPacketSender sender = new DelayedPacketSender();
        EmbeddedChannel channel = new EmbeddedChannel(sender);
        ByteBuf message = Unpooled.buffer().writeInt(42);
        try {
            sender.replyAfter(
                    Objects.requireNonNull(channel.pipeline().context(sender)), message, 0);
            channel.runScheduledPendingTasks();
            ByteBuf sent = channel.readOutbound();
            assertSame(message, sent);
            assertEquals(1, message.refCnt());
            sent.release();
            channel.runScheduledPendingTasks();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
        assertEquals(0, message.refCnt());
    }

    /** Disconnect cancels a far-future timer and releases its payload before handler removal. */
    @Test
    void disconnectReleasesPendingReply() {
        DelayedPacketSender sender = new DelayedPacketSender();
        EmbeddedChannel channel = new EmbeddedChannel(sender);
        ByteBuf message = Unpooled.buffer().writeByte(1);
        try {
            sender.replyAfter(
                    Objects.requireNonNull(channel.pipeline().context(sender)),
                    message,
                    TimeUnit.DAYS.toNanos(1));
            assertTrue(channel.close().isSuccess());
            assertEquals(0, message.refCnt());
            channel.runScheduledPendingTasks();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Removal on a still-open channel cancels work and rejects calls through the old context. */
    @Test
    void removalReleasesPendingAndRejectedReplies() {
        DelayedPacketSender sender = new DelayedPacketSender();
        EmbeddedChannel channel = new EmbeddedChannel(sender);
        ByteBuf pending = Unpooled.buffer().writeByte(1);
        ByteBuf rejected = Unpooled.buffer().writeByte(2);
        try {
            ChannelHandlerContext context =
                    Objects.requireNonNull(channel.pipeline().context(sender));
            sender.replyAfter(context, pending, TimeUnit.DAYS.toNanos(1));
            channel.pipeline().remove(sender);
            assertTrue(channel.isActive());
            assertEquals(0, pending.refCnt());
            assertThrows(
                    RejectedExecutionException.class,
                    () -> sender.replyAfter(context, rejected, 0));
            assertEquals(0, rejected.refCnt());
            channel.runScheduledPendingTasks();
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Excess traffic cannot retain an unbounded number of replies behind a long RTT estimate. */
    @Test
    void capsQueuedRepliesWithoutLeakingRejectedPayload() {
        DelayedPacketSender sender = new DelayedPacketSender();
        EmbeddedChannel channel = new EmbeddedChannel(sender);
        List<ByteBuf> accepted = new ArrayList<>();
        ByteBuf rejected = Unpooled.buffer().writeByte(65);
        try {
            ChannelHandlerContext context =
                    Objects.requireNonNull(channel.pipeline().context(sender));
            for (int index = 0; index < 64; index++) {
                ByteBuf message = Unpooled.buffer().writeByte(index);
                accepted.add(message);
                sender.replyAfter(context, message, TimeUnit.DAYS.toNanos(1));
            }
            assertThrows(
                    RejectedExecutionException.class,
                    () -> sender.replyAfter(context, rejected, 0));
            assertEquals(0, rejected.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
        for (ByteBuf message : accepted) {
            assertEquals(0, message.refCnt());
        }
    }
}
