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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.utils.UINT;

import java.util.List;
import java.util.TreeMap;

/** Rejects repeated reliable message IDs before fragments or unordered packets reach their consumers. */
final class ReliableFrameDeduplicator extends MessageToMessageDecoder<Frame> {
    /** Bounds adversarial sparse histories without allocating a bitmap for all sixteen million IDs per player. */
    private static final int MAX_RANGES = 65536;
    /** Inclusive runs of received IDs in an unwrapped sequence space, normally just one contiguous run. */
    private final TreeMap<Long, Long> received = new TreeMap<>();
    /** Largest unwrapped reliable ID observed on this transport. */
    private long newest;
    /** Distinguishes the first received frame from a legitimate reliable ID of zero. */
    private boolean initialized;

    /**
     * Preserves unreliable duplicates and lets the transport acknowledge reliable retries before discarding them.
     * This handler belongs after reliability processing and before fragment reassembly, whose IDs are per fragment.
     * @param ctx transport event-loop context
     * @param frame decoded frame with its original reliable message index
     * @param output frames that have not already been delivered
     * @throws TooLongFrameException if excessive disjoint received ranges exhaust the bounded history
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, Frame frame, List<Object> output) {
        try {
            if (!frame.getReliability().isReliable || remember(frame.getReliableIndex())) output.add(frame.retain());
        } catch (TooLongFrameException excessive) {
            ctx.close();
            throw excessive;
        }
    }

    /**
     * Records a message once without delaying valid out-of-order traffic or confusing ID wrap with a retry.
     * @param index unsigned 24-bit reliable ID assigned before transmission
     * @return whether this is the first observation within the protocol's unambiguous serial-number range
     * @throws TooLongFrameException if too many disjoint ranges remain outstanding
     */
    private boolean remember(int index) {
        long absolute = unwrap(index);
        var previous = received.floorEntry(absolute);
        if (previous != null && previous.getValue() >= absolute) return false;
        long start = absolute;
        long end = absolute;
        if (previous != null && previous.getValue() == absolute - 1) {
            start = previous.getKey();
            received.remove(start);
        }
        var next = received.ceilingEntry(absolute);
        if (next != null && next.getKey() == absolute + 1) {
            end = next.getValue();
            received.remove(next.getKey());
        }
        if (received.size() >= MAX_RANGES) throw new TooLongFrameException("Too many gaps in reliable message history");
        received.put(start, end);
        return true;
    }

    /**
     * Projects the wire counter onto a long sequence and retires history only beyond half the serial space.
     * Small fixed windows would wrongly discard unseen low-priority frames overtaken by other channels.
     * @param index wire reliable message index
     * @return index relative to the most recent wrap
     */
    private long unwrap(int index) {
        if (!initialized) {
            initialized = true;
            newest = index;
        }
        long absolute = newest + UINT.B3.minusWrap(index, (int) newest & UINT.B3.MAX_VALUE);
        if (absolute > newest) {
            newest = absolute;
            retireHistory();
        }
        return absolute;
    }

    /** Removes expired runs and trims their boundary so new IDs can be reused after a full counter wrap. */
    private void retireHistory() {
        long oldest = newest - UINT.B3.HALF_MAX;
        while (!received.isEmpty() && received.firstEntry().getValue() < oldest) received.pollFirstEntry();
        if (!received.isEmpty() && received.firstKey() < oldest) {
            long end = received.pollFirstEntry().getValue();
            received.put(oldest, end);
        }
    }
}
