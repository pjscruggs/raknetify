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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ishland.raknetify.common.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.api.Test;

/**
 * Verifies that an incomplete peer update cannot publish fields or poison the accepted timestamp.
 */
class MetricsSnapshotTest {
    /** Parsing must finish before either peer support or its most recent measurements change. */
    @Test
    void truncatedSnapshotCannotReplaceAcceptedMeasurements() {
        var handler = new MetricsSynchronizationHandler();
        var channel = new EmbeddedChannel(handler);
        try {
            assertFalse(handler.isRemoteSupported());
            channel.writeInbound(snapshot(1, 11));
            assertTrue(handler.isRemoteSupported());
            FrameData truncated = frame(Unpooled.buffer().writeByte(0).writeLong(100).writeInt(99));
            assertThrows(IndexOutOfBoundsException.class, () -> channel.writeInbound(truncated));
            assertEquals(0, truncated.refCnt());
            assertEquals(11, handler.getQueuedBytes());
            channel.writeInbound(snapshot(2, 22));
            assertEquals(22, handler.getQueuedBytes());
            assertEquals(4, handler.getBurst());
            assertEquals(0.125, handler.getErrorRate());
            assertEquals(6, handler.getTX());
            assertEquals(7, handler.getRX());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Produces one supported wire snapshot with independently chosen timestamp and queue size.
     *
     * @param time sender's monotonically increasing fixture timestamp
     * @param queuedBytes advertised queue size
     * @return owned metric frame consumed by the handler
     */
    private static FrameData snapshot(final long time, final int queuedBytes) {
        return frame(
                Unpooled.buffer()
                        .writeByte(0)
                        .writeLong(time)
                        .writeInt(queuedBytes)
                        .writeInt(4)
                        .writeDouble(0.125)
                        .writeInt(6)
                        .writeInt(7));
    }

    /**
     * Transfers fixture bytes into the same frame envelope accepted by the production pipeline.
     *
     * @param data owned metric payload released after the frame retains it
     * @return owned transport frame
     */
    private static FrameData frame(final ByteBuf data) {
        try {
            return FrameData.create(
                    UnpooledByteBufAllocator.DEFAULT,
                    Constants.RAKNET_METRICS_SYNC_PACKET_ID,
                    data);
        } finally {
            data.release();
        }
    }
}
