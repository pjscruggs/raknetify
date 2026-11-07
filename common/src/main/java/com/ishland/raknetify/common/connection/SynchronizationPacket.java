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
import io.netty.handler.codec.CorruptedFrameException;

import it.unimi.dsi.fastutil.ints.IntSet;

import network.ycc.raknet.pipeline.FrameJoiner;
import network.ycc.raknet.pipeline.FrameOrderIn;
import network.ycc.raknet.pipeline.ReliabilityHandler;
import network.ycc.raknet.utils.UINT;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fully validated reset cursors, decoded before any connection state or downstream event changes.
 */
final class SynchronizationPacket {
    /** Lane count supported by the transport's ordering stages. */
    static final int CHANNEL_COUNT = 8;

    /** Immutable lane-to-cursor snapshot with duplicates rejected during decoding. */
    private final Map<Integer, Integer> cursors;

    /** Last datagram sequence covered by the reset marker. */
    private final int sequence;

    /**
     * Captures validated cursors so later callers cannot change a pending reset.
     *
     * @param cursors unique lanes paired with normalized twenty-four-bit positions
     * @param sequence validated datagram sequence
     */
    private SynchronizationPacket(final Map<Integer, Integer> cursors, final int sequence) {
        this.cursors = Map.copyOf(cursors);
        this.sequence = sequence;
    }

    /**
     * Rejects invalid lengths, duplicate or protected lanes and out-of-width cursors before
     * returning a reset. The historical minus-one order sentinel is normalized to the maximum
     * twenty-four-bit position.
     *
     * @param bytes payload immediately after the synchronization packet identifier
     * @param ignoredChannels protected lanes which the peer may not reset
     * @return complete validated marker, with the payload fully consumed
     * @throws CorruptedFrameException if any marker field is invalid or trailing bytes remain
     */
    static SynchronizationPacket read(final ByteBuf bytes, final IntSet ignoredChannels) {
        if (bytes.readableBytes() < 5) {
            throw new CorruptedFrameException("Truncated synchronization marker");
        }
        final int count = bytes.readUnsignedByte();
        if (count > CHANNEL_COUNT || bytes.readableBytes() != count * 5 + 4) {
            throw new CorruptedFrameException("Invalid synchronization length");
        }
        final Map<Integer, Integer> cursors = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            readCursor(bytes, ignoredChannels, cursors);
        }
        final int sequence = bytes.readInt();
        if (!UINT.fits(sequence, 24)) {
            throw new CorruptedFrameException("Invalid synchronization sequence");
        }
        return new SynchronizationPacket(cursors, sequence);
    }

    /**
     * Validates one lane entry without changing the transport or publishing a partially decoded
     * marker.
     *
     * @param bytes remaining marker bytes, already checked for the complete declared length
     * @param ignoredChannels lanes that must retain their current receive state
     * @param cursors local decode accumulator with no reference to transport queues
     * @throws CorruptedFrameException if a lane or its ordering cursor is invalid
     */
    private static void readCursor(
            final ByteBuf bytes,
            final IntSet ignoredChannels,
            final Map<Integer, Integer> cursors) {
        final int channel = bytes.readUnsignedByte();
        final int cursor = bytes.readInt();
        if (channel >= CHANNEL_COUNT
                || ignoredChannels.contains(channel)
                || cursors.containsKey(channel)) {
            throw new CorruptedFrameException("Invalid synchronization lane");
        }
        if (cursor != -1 && !UINT.fits(cursor, 24)) {
            throw new CorruptedFrameException("Invalid synchronization order cursor");
        }
        cursors.put(channel, cursor & UINT.B3.MAX_VALUE);
    }

    /**
     * Applies validated cursor changes and releases obsolete fragments on the owning event loop.
     *
     * @param ordering inbound lane queues advanced without exposing their storage
     * @param joiner fragment stage releasing superseded partial packets
     * @param reliability scheduler advancing receive-gap tracking
     */
    void apply(
            final FrameOrderIn ordering,
            final FrameJoiner joiner,
            final ReliabilityHandler reliability) {
        cursors.forEach((channel, cursor) -> {
            ordering.skipOrderedThrough(channel, cursor);
            joiner.discardOrderedThrough(channel, cursor);
        });
        reliability.skipReceivedThrough(sequence);
    }
}
