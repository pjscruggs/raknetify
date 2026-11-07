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

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

/** Exercises receipt ranges and lossy windows independently of cryptographic provider behavior. */
class FrameReplayWindowTest {
    /**
     * Compares lossy receipt decisions with an independent set model across gaps, wraps and
     * reordering.
     *
     * @throws GeneralSecurityException if loss-tolerant receipt unexpectedly exhausts reliable
     *     budget
     */
    @Test
    void unreliableHistoryMatchesAnIndependentReceiptSet() throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        var random = new SplittableRandom(723910);
        Set<Long> received = new HashSet<>();
        long highest = -1;
        for (int index = 0; index < 10000; index++) {
            long sequence = Math.max(0, highest + random.nextInt(8193) - 4096);
            highest = Math.max(highest, sequence);
            boolean expected = sequence > highest - 4096 && received.add(sequence);
            assertEquals(expected, history.accept(sequence, true), "Sequence " + sequence);
        }
    }

    /** Ordered lanes accept gaps but never deliver a repeated or earlier authenticated sequence. */
    @Test
    void orderedTrafficNeverMovesBackwards() {
        var history = new FrameReplayWindow();
        assertTrue(history.acceptOrdered(0));
        assertFalse(history.acceptOrdered(0));
        assertTrue(history.acceptOrdered(20));
        assertFalse(history.acceptOrdered(19));
        assertFalse(history.acceptOrdered(20));
        assertTrue(history.acceptOrdered(21));
    }

    /**
     * Reliable duplicates must be rejected even while earlier missing packets prevent prefix
     * advancement.
     *
     * @throws GeneralSecurityException if a small receipt history unexpectedly exhausts its budget
     */
    @Test
    void reliableDuplicatesAreRejectedInsidePendingRanges() throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        assertTrue(history.accept(4, false));
        assertTrue(history.accept(5, false));
        assertTrue(history.accept(6, false));
        assertFalse(history.accept(4, false));
        assertFalse(history.accept(5, false));
        assertFalse(history.accept(6, false));
    }

    /**
     * Joining two pending ranges returns their spare slot without requiring the missing prefix.
     *
     * @throws GeneralSecurityException if merging ranges fails to return the documented capacity
     */
    @Test
    void bridgingReliableRangesReturnsBudgetCapacity() throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        for (int index = 0; index < 65536; index++) {
            assertTrue(history.accept(index * 2L + 1, false));
        }
        assertTrue(history.accept(2, false));
        assertTrue(history.accept(131073, false));
        assertThrows(GeneralSecurityException.class, () -> history.accept(131075, false));
    }

    /**
     * Stale unseen packets and old bitmap slots cannot reappear after a full window jump.
     *
     * @throws GeneralSecurityException if loss-tolerant receipt unexpectedly consumes reliable
     *     budget
     */
    @Test
    void unreliableWindowExpiresUnseenBoundaryAndClearsReusedSlots()
            throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        assertTrue(history.accept(4096, true));
        assertFalse(history.accept(0, true));
        assertTrue(history.accept(1, true));
        assertTrue(history.accept(10000, true));
        assertTrue(history.accept(8193, true));
        assertFalse(history.accept(8193, true));
        assertTrue(history.accept((1L << 32) - 1, true));
        assertFalse(history.accept((1L << 32) - 1, true));
    }

    /**
     * Switching channels cannot multiply the connection's retained reliable-gap allowance.
     *
     * @throws GeneralSecurityException if a shared range budget fills before its limit
     */
    @Test
    void reliableMemoryBudgetIsSharedAcrossLanes() throws GeneralSecurityException {
        var budget = new FrameReplayWindow.Budget();
        var first = new FrameReplayWindow(budget);
        var second = new FrameReplayWindow(budget);
        for (int index = 0; index < 32768; index++) {
            assertTrue(first.accept(index * 2L + 1, false));
            assertTrue(second.accept(index * 2L + 1, false));
        }
        assertThrows(GeneralSecurityException.class, () -> first.accept(65537, false));
        assertThrows(GeneralSecurityException.class, () -> second.accept(65537, false));
        assertTrue(first.accept(0, false));
        assertTrue(second.accept(65537, false));
    }

    /**
     * Bridging gaps merges both adjacent ranges without forgetting duplicate history.
     *
     * @throws GeneralSecurityException if the bounded reliable history unexpectedly fills
     */
    @Test
    void reliableRangesMergeAndRetainTheCompletePrefix() throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        for (int sequence : new int[] {8, 9, 4, 5, 7, 6, 2, 3, 0, 1, 10}) {
            assertTrue(history.accept(sequence, false));
        }
        for (int sequence = 0; sequence <= 10; sequence++) {
            assertFalse(history.accept(sequence, false));
        }
    }

    /**
     * An unreliable window forgets only expired positions, including after a complete wrap.
     *
     * @throws GeneralSecurityException if the unreliable history unexpectedly rejects its budget
     */
    @Test
    void unreliableWindowHandlesLossReorderingAndWrap() throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        assertTrue(history.accept(5, true));
        assertTrue(history.accept(0, true));
        assertTrue(history.accept(4095, true));
        assertFalse(history.accept(0, true));
        assertTrue(history.accept(4096, true));
        assertFalse(history.accept(0, true));
        assertTrue(history.accept(1, true));
        assertFalse(history.accept(1, true));
        assertTrue(history.accept(10000, true));
        assertFalse(history.accept(4096, true));
        assertTrue(history.accept(9999, true));
    }

    /**
     * Exact reliable history remains bounded even when a peer never fills any gap.
     *
     * @throws GeneralSecurityException if history fills before its documented capacity
     */
    @Test
    void disjointReliableRangesHaveAFiniteMemoryBudget() throws GeneralSecurityException {
        var history = new FrameReplayWindow();
        for (int index = 0; index < 65536; index++) {
            assertTrue(history.accept(index * 2L + 1, false));
        }
        assertThrows(GeneralSecurityException.class, () -> history.accept(131073, false));
        assertTrue(history.accept(0, false));
        assertTrue(history.accept(2, false));
        assertFalse(history.accept(3, false));
    }
}
