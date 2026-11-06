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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

/** Exercises receipt ranges and lossy windows independently of cryptographic provider behavior. */
class FrameReplayWindowTest {
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
