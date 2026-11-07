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

import java.security.GeneralSecurityException;
import java.util.BitSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Tracks authenticated sequence numbers without making one ordering channel block another. */
final class FrameReplayWindow {
    /** Maximum reorder distance retained for traffic that already permits packet loss. */
    private static final int WINDOW = 4096;

    /** Shared connection budget prevents separate lanes from multiplying retained gap memory. */
    private final Budget budget;

    /** Exact disjoint received ranges for reliable traffic, including arbitrarily late frames. */
    private final NavigableMap<Long, Long> ranges = new TreeMap<>();

    /** Circular receipt bitmap used only for unreliable or sequenced traffic. */
    private final BitSet received = new BitSet(WINDOW);

    /** Greatest unreliable sequence authenticated so far, or minus one before receipt. */
    private long highest = -1;

    /**
     * End of the reliable prefix with no missing sequence, allowing allocation-free normal receipt.
     */
    private long contiguous = -1;

    /** Creates empty history for one authenticated reliability and ordering lane. */
    FrameReplayWindow() {
        this(new Budget());
    }

    /**
     * Shares a finite gap budget with the other reliability and ordering lanes on a connection.
     *
     * @param budget connection-wide accounting for retained reliable ranges
     */
    FrameReplayWindow(final Budget budget) {
        this.budget = budget;
    }

    /**
     * Prevents altered outer transport indices from reversing authenticated ordered traffic.
     *
     * @param sequence authenticated sequence on an ordered or sequenced lane
     * @return whether the packet follows every previously delivered packet on this lane
     */
    boolean acceptOrdered(final long sequence) {
        if (sequence <= highest) {
            return false;
        }
        highest = sequence;
        return true;
    }

    /**
     * Records a sequence only after its authentication tag has been verified.
     *
     * @param sequence nonnegative per-lane sequence from the authenticated header
     * @param lossTolerant whether old traffic may be discarded under the transport's delivery
     *     policy
     * @return false for duplicate or stale unreliable traffic, true for newly accepted traffic
     * @throws GeneralSecurityException if reliable gaps exhaust the bounded history
     */
    boolean accept(final long sequence, final boolean lossTolerant)
            throws GeneralSecurityException {
        if (lossTolerant) {
            return acceptUnreliable(sequence);
        }
        if (sequence <= contiguous) {
            return false;
        }
        if (sequence == contiguous + 1) {
            Long adjacent = ranges.remove(sequence + 1);
            if (adjacent != null) {
                budget.release();
            }
            contiguous = adjacent == null ? sequence : adjacent;
            return true;
        }
        return acceptReliable(sequence);
    }

    /**
     * Merges adjacent receipt intervals so ordered reliable traffic needs only one map entry.
     *
     * @param sequence authenticated reliable sequence to record
     * @return whether the sequence had not been received before
     * @throws GeneralSecurityException if the peer leaves too many simultaneous gaps
     */
    private boolean acceptReliable(final long sequence) throws GeneralSecurityException {
        Map.Entry<Long, Long> lower = ranges.floorEntry(sequence);
        if (lower != null && lower.getValue() >= sequence) {
            return false;
        }
        long start = sequence;
        long end = sequence;
        if (lower != null && lower.getValue() == sequence - 1) {
            start = lower.getKey();
            ranges.remove(start);
            budget.release();
        }
        Map.Entry<Long, Long> upper = ranges.ceilingEntry(sequence);
        if (upper != null && upper.getKey() == sequence + 1) {
            end = upper.getValue();
            ranges.remove(upper.getKey());
            budget.release();
        }
        budget.acquire();
        ranges.put(start, end);
        return true;
    }

    /**
     * Keeps bounded duplicate history for traffic whose contract permits late packets to be lost.
     *
     * @param sequence authenticated sequence within the finite session counter range
     * @return whether the packet lies in the window and has not already been accepted
     */
    private boolean acceptUnreliable(final long sequence) {
        if (sequence <= highest - WINDOW) {
            return false;
        }
        if (sequence > highest) {
            advance(sequence);
        }
        int slot = (int) (sequence % WINDOW);
        if (received.get(slot)) {
            return false;
        }
        received.set(slot);
        return true;
    }

    /**
     * Clears only slots displaced by a newer authenticated unreliable packet. Splitting a wrapped
     * interval at the bitmap boundary avoids walking every missing sequence after packet loss.
     *
     * @param sequence new greatest received sequence
     */
    private void advance(final long sequence) {
        if (sequence - highest >= WINDOW) {
            received.clear();
        } else {
            int first = (int) ((highest + 1) % WINDOW);
            int last = (int) (sequence % WINDOW);
            if (first <= last) {
                received.clear(first, last + 1);
            } else {
                received.clear(first, WINDOW);
                received.clear(0, last + 1);
            }
        }
        highest = sequence;
    }

    /** Bounds total reliable gap storage across every authenticated lane of one connection. */
    static final class Budget {
        /** Maximum number of disjoint ranges retained across all channels together. */
        private static final int LIMIT = 65536;

        /** Current number of map entries owned by all lanes sharing this budget. */
        private int retained;

        /** Creates empty accounting confined to the connection's event loop. */
        Budget() {}

        /**
         * Reserves one retained range before the corresponding map insertion.
         *
         * @throws GeneralSecurityException if the peer's gaps exhaust the connection-wide budget
         */
        private void acquire() throws GeneralSecurityException {
            if (retained >= LIMIT) {
                throw new GeneralSecurityException("Encrypted reliable reorder history exhausted");
            }
            retained++;
        }

        /**
         * Returns one range after it has merged with a neighbor or the complete received prefix.
         */
        private void release() {
            retained--;
        }
    }
}
