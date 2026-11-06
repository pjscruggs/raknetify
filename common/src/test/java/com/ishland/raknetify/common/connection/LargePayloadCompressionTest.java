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

import com.ishland.raknetify.test.TransportTestPayload;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the shared streaming codec can carry packets larger than vanilla's eight-MiB bound. */
class LargePayloadCompressionTest {

    /** Raw application frame identifier reserved for this isolated codec pair. */
    private static final int RAW = 253;

    /** Compressed application frame identifier reserved for this isolated codec pair. */
    private static final int COMPRESSED = 252;

    /** Preserves every byte and ordering policy through repeated independent streams in both directions. */
    @Test
    void oversizedPacketsRetainBytesAndOrderingAcrossAllChannels() {
        EmbeddedChannel left = new EmbeddedChannel(new MultiChannelingStreamingCompression(RAW, COMPRESSED));
        EmbeddedChannel right = new EmbeddedChannel(new MultiChannelingStreamingCompression(RAW, COMPRESSED));
        try {
            transferControl(left, right);
            transferControl(right, left);
            transferControl(left, right);
            for (int repetition = 0; repetition < 2; repetition++) {
                for (int channel = 0; channel < 8; channel++) {
                    exchange(left, right, channel, 100L * repetition + channel);
                    exchange(right, left, channel, -100L * repetition - channel - 1);
                }
            }
        } finally {
            left.finishAndReleaseAll();
            right.finishAndReleaseAll();
        }
    }

    /**
     * Delivers queued capability and dictionary-start frames before application traffic begins.
     *
     * @param sender endpoint whose control writes are ready to flush
     * @param receiver peer that consumes the control frames without application output
     */
    private static void transferControl(EmbeddedChannel sender, EmbeddedChannel receiver) {
        sender.flushOutbound();
        Object message;
        while ((message = sender.readOutbound()) != null) receiver.writeInbound(message);
        assertNull(receiver.readInbound());
    }

    /**
     * Sends one oversized ordered frame and requires compression, exact reconstruction and ownership release.
     *
     * @param sender endpoint retaining this channel's outbound compression dictionary
     * @param receiver endpoint retaining the matching inbound dictionary
     * @param channel ordering channel that must survive both transformations
     * @param marker distinct message identity that detects stale output across repeated writes
     */
    private static void exchange(EmbeddedChannel sender, EmbeddedChannel receiver, int channel, long marker) {
        ByteBuf payload = TransportTestPayload.create(sender.alloc(), TransportTestPayload.LARGE_SIZE, marker);
        FrameData frame;
        try {
            frame = FrameData.create(sender.alloc(), RAW, payload);
        } finally {
            payload.release();
        }
        frame.setOrderChannel(channel);
        frame.setReliability(FramedPacket.Reliability.RELIABLE_ORDERED);
        assertTrue(sender.writeOutbound(frame));
        FrameData compressed = sender.readOutbound();
        assertNotNull(compressed);
        try {
            assertEquals(COMPRESSED, compressed.getPacketId());
            assertTrue(compressed.getDataSize() < TransportTestPayload.LARGE_SIZE);
            receiver.writeInbound(compressed.retain());
        } finally {
            compressed.release();
        }
        verifyReceived(receiver, channel, marker);
    }

    /**
     * Checks decoded bytes, metadata and the absence of extra output before releasing the received frame.
     *
     * @param receiver endpoint exposing the reconstructed application frame
     * @param channel expected ordering channel
     * @param marker expected message identity
     */
    private static void verifyReceived(EmbeddedChannel receiver, int channel, long marker) {
        FrameData decoded = receiver.readInbound();
        assertNotNull(decoded);
        ByteBuf bytes = decoded.createData();
        try {
            assertEquals(RAW, bytes.readUnsignedByte());
            assertEquals(channel, decoded.getOrderChannel());
            assertEquals(FramedPacket.Reliability.RELIABLE_ORDERED, decoded.getReliability());
            assertEquals(TransportTestPayload.LARGE_SIZE, bytes.readableBytes());
            TransportTestPayload.verify(bytes, marker);
            assertNull(receiver.readInbound());
        } finally {
            bytes.release();
            decoded.release();
        }
    }
}
