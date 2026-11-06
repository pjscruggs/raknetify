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

import com.ishland.raknetify.common.Constants;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;

import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.utils.UINT;

import java.util.List;
import java.util.TreeMap;

/**
 * Rejects repeated reliable message IDs before fragments or unordered packets reach their
 * consumers.
 */
final class ReliableFrameDeduplicator extends MessageToMessageDecoder<Frame> {
    /**
     * Bounds adversarial sparse histories without allocating a bitmap for all sixteen million IDs
     * per player.
     */
    private static final int MAX_RANGES = 65536;

    /**
     * Inclusive runs of received IDs in an unwrapped sequence space, normally just one contiguous
     * run.
     */
    private final java.util.NavigableMap<Long, Range> received = new TreeMap<>();

    /** Cached highest interval permits sequential traffic without tree lookup or allocation. */
    private Range lastRange;

    /** Cached oldest interval makes retirement checks constant time until a whole run expires. */
    private Range firstRange;

    /** Largest unwrapped reliable ID observed on this transport. */
    private long newest;

    /** Distinguishes the first received frame from a legitimate reliable ID of zero. */
    private boolean initialized;

    /**
     * Preserves unreliable duplicates and lets the transport acknowledge reliable retries before
     * discarding them. This handler belongs after reliability processing and before fragment
     * reassembly, whose IDs are per fragment.
     *
     * @param ctx transport event-loop context
     * @param frame decoded frame with its original reliable message index
     * @param output frames that have not already been delivered
     * @throws TooLongFrameException if excessive disjoint received ranges exhaust the bounded
     *     history
     * @throws CorruptedFrameException if the frame addresses an unsupported ordering queue
     */
    @Override
    protected void decode(
            final ChannelHandlerContext ctx, final Frame frame, final List<Object> output) {
        try {
            if (frame.getOrderChannel() < 0
                    || frame.getOrderChannel() >= Constants.ORDER_CHANNEL_COUNT) {
                throw new CorruptedFrameException(
                        "Invalid ordering channel: " + frame.getOrderChannel());
            }
            if (!frame.getReliability().isReliable || remember(frame.getReliableIndex())) {
                output.add(frame.retain());
            }
        } catch (DecoderException invalid) {
            ctx.close();
            throw invalid;
        }
    }

    /**
     * Records a message once without delaying valid out-of-order traffic or confusing ID wrap with
     * a retry.
     *
     * @param index unsigned 24-bit reliable ID assigned before transmission
     * @return whether this is the first observation within the protocol's unambiguous serial-number
     *     range
     * @throws TooLongFrameException if too many disjoint ranges remain outstanding
     */
    private boolean remember(final int index) {
        long absolute = unwrap(index);
        if (lastRange != null && absolute >= lastRange.start) {
            if (absolute <= lastRange.end) {
                return false;
            }
            if (absolute == lastRange.end + 1) {
                lastRange.end = absolute;
                return true;
            }
        }
        return insertRange(absolute);
    }

    /**
     * Merges a reordered or discontinuous arrival with its adjacent intervals.
     *
     * @param absolute unwrapped reliable identity outside the sequential fast path
     * @return whether the identity was previously unseen
     * @throws TooLongFrameException if the sparse interval limit would be exceeded
     */
    private boolean insertRange(final long absolute) {
        Range previous = previousRange(absolute);
        if (previous != null && previous.end >= absolute) {
            return false;
        }
        Range next = nextRange(absolute);
        if (previous != null && previous.end == absolute - 1) {
            extendRange(previous, next, absolute);
            return true;
        }
        long end = absolute;
        if (next != null && next.start == absolute + 1) {
            end = next.end;
            received.remove(next.start);
        }
        addRange(absolute, end);
        return true;
    }

    /**
     * Reuses boundary intervals without allocating a map entry for identities outside the interior
     * gaps.
     *
     * @param absolute identity being inserted
     * @return nearest preceding interval, or null before the first accepted identity
     */
    private Range previousRange(final long absolute) {
        if (firstRange == null || absolute < firstRange.start) {
            return null;
        }
        if (absolute >= lastRange.start) {
            return lastRange;
        }
        return received.floorEntry(absolute).getValue();
    }

    /**
     * Finds the next interval while avoiding map-entry allocation at either end of the history.
     *
     * @param absolute identity being inserted
     * @return nearest following interval, or null beyond the newest run
     */
    private Range nextRange(final long absolute) {
        if (lastRange == null || absolute > lastRange.start) {
            return null;
        }
        if (absolute <= firstRange.start) {
            return firstRange;
        }
        return received.ceilingEntry(absolute).getValue();
    }

    /**
     * Extends an existing run in place and removes the following run when the new identity bridges
     * the gap.
     *
     * @param previous run ending immediately before the new identity
     * @param next following run, if any
     * @param absolute newly accepted identity
     */
    private void extendRange(final Range previous, final Range next, final long absolute) {
        previous.end = absolute;
        if (next != null && next.start == absolute + 1) {
            previous.end = next.end;
            received.remove(next.start);
            if (lastRange == next) {
                lastRange = previous;
            }
        }
    }

    /**
     * Inserts one run and updates cached boundaries without allocating immutable map-entry
     * snapshots.
     *
     * @param start first identity in the run
     * @param end last identity in the run
     * @throws TooLongFrameException if adding another disjoint run would exhaust the bounded
     *     history
     */
    private void addRange(final long start, final long end) {
        if (received.size() >= MAX_RANGES) {
            throw new TooLongFrameException("Too many gaps in reliable message history");
        }
        Range added = new Range(start, end);
        received.put(start, added);
        if (firstRange == null || start < firstRange.start) {
            firstRange = added;
        }
        if (lastRange == null || end >= lastRange.end) {
            lastRange = added;
        }
    }

    /**
     * Projects the wire counter onto a long sequence and retires history only beyond half the
     * serial space. Small fixed windows would wrongly discard unseen low-priority frames overtaken
     * by other channels.
     *
     * @param index wire reliable message index
     * @return index relative to the most recent wrap
     */
    private long unwrap(final int index) {
        if (!initialized) {
            initialized = true;
            newest = index;
        }
        long absolute = newest + UINT.B3.minusWrap(index, (int) (newest & UINT.B3.MAX_VALUE));
        if (absolute > newest) {
            newest = absolute;
            retireHistory();
        }
        return absolute;
    }

    /**
     * Removes runs below the smallest identity the transport's serial arithmetic can return. The
     * negative half-space boundary is inclusive. A surviving run keeps its original start because
     * trimming it would only replace the tree key on every packet after a long connection.
     */
    private void retireHistory() {
        long oldest = newest - (UINT.B3.HALF_MAX + 1L);
        while (firstRange != null && firstRange.end < oldest) {
            received.pollFirstEntry();
            firstRange = received.isEmpty() ? null : received.firstEntry().getValue();
        }
        if (firstRange == null) {
            lastRange = null;
        }
    }

    /** Mutable upper boundary avoids allocating boxed values as a contiguous sequence advances. */
    private static final class Range {
        /** Lowest accepted identity in this run, also used as its stable tree key. */
        private final long start;

        /** Highest accepted identity, extended only by the channel event loop. */
        private long end;

        /**
         * Creates one inclusive run after merging neighboring received identities.
         *
         * @param start first accepted identity
         * @param end last accepted identity
         */
        private Range(final long start, final long end) {
            this.start = start;
            this.end = end;
        }
    }
}
