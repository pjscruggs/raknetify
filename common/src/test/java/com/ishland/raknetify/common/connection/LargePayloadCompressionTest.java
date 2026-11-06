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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.test.TransportTestPayload;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CorruptedFrameException;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import org.junit.jupiter.api.Test;

/** Verifies the shared streaming codec can carry packets larger than vanilla's eight-MiB bound. */
class LargePayloadCompressionTest {

    /** Raw application frame identifier reserved for this isolated codec pair. */
    private static final int RAW = 253;

    /** Compressed application frame identifier reserved for this isolated codec pair. */
    private static final int COMPRESSED = 252;

    /** Rejects invalid channel metadata without leaking incoming payloads or control frames. */
    @Test
    void invalidIncomingChannelsReleasePayloadAndControlFrames() {
        for (int id :
                new int[] {
                    RAW, COMPRESSED, Constants.RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID
                }) {
            for (int ordering : new int[] {-1, 8, 255}) {
                EmbeddedChannel channel =
                        new EmbeddedChannel(
                                new MultiChannelingStreamingCompression(RAW, COMPRESSED));
                FrameData frame = invalidFrame(channel, id, ordering);
                try {
                    assertThrows(CorruptedFrameException.class, () -> channel.writeInbound(frame));
                    assertEquals(0, frame.refCnt());
                    assertNull(channel.readInbound());
                } finally {
                    channel.finishAndReleaseAll();
                }
            }
        }
    }

    /** Rejects invalid local output while releasing the message whose write promise fails. */
    @Test
    void invalidOutgoingChannelsReleasePayloads() {
        for (int ordering : new int[] {-1, 8, 255}) {
            EmbeddedChannel channel =
                    new EmbeddedChannel(new MultiChannelingStreamingCompression(RAW, COMPRESSED));
            FrameData frame = invalidFrame(channel, RAW, ordering);
            try {
                assertThrows(CorruptedFrameException.class, () -> channel.writeOutbound(frame));
                assertEquals(0, frame.refCnt());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Creates a frame that reaches channel validation with a complete control-sized payload.
     *
     * @param channel allocator owner for the isolated test pipeline
     * @param id wire identifier selecting payload or compression control handling
     * @param ordering invalid channel under test
     * @return owned frame transferred to the handler
     */
    private static FrameData invalidFrame(
            final EmbeddedChannel channel, final int id, final int ordering) {
        ByteBuf payload =
                channel.alloc()
                        .buffer()
                        .writeLong(MultiChannelingStreamingCompression.CHANNEL_START);
        FrameData frame;
        try {
            frame = FrameData.create(channel.alloc(), id, payload);
        } finally {
            payload.release();
        }
        frame.setReliability(FramedPacket.Reliability.RELIABLE_ORDERED);
        frame.setOrderChannel(ordering);
        return frame;
    }

    /**
     * Preserves every byte and ordering policy through repeated independent streams in both
     * directions.
     */
    @Test
    void oversizedPacketsRetainBytesAndOrderingAcrossAllChannels() {
        EmbeddedChannel left =
                new EmbeddedChannel(new MultiChannelingStreamingCompression(RAW, COMPRESSED));
        EmbeddedChannel right =
                new EmbeddedChannel(new MultiChannelingStreamingCompression(RAW, COMPRESSED));
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
    private static void transferControl(
            final EmbeddedChannel sender, final EmbeddedChannel receiver) {
        sender.flushOutbound();
        Object message;
        while ((message = sender.readOutbound()) != null) {
            receiver.writeInbound(message);
        }
        assertNull(receiver.readInbound());
    }

    /**
     * Sends one oversized ordered frame and requires compression, exact reconstruction and
     * ownership release.
     *
     * @param sender endpoint retaining this channel's outbound compression dictionary
     * @param receiver endpoint retaining the matching inbound dictionary
     * @param channel ordering channel that must survive both transformations
     * @param marker distinct message identity that detects stale output across repeated writes
     */
    private static void exchange(
            final EmbeddedChannel sender,
            final EmbeddedChannel receiver,
            final int channel,
            final long marker) {
        ByteBuf payload =
                TransportTestPayload.create(
                        sender.alloc(), TransportTestPayload.LARGE_SIZE, marker);
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
     * Checks decoded bytes, metadata and the absence of extra output before releasing the received
     * frame.
     *
     * @param receiver endpoint exposing the reconstructed application frame
     * @param channel expected ordering channel
     * @param marker expected message identity
     */
    private static void verifyReceived(
            final EmbeddedChannel receiver, final int channel, final long marker) {
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
