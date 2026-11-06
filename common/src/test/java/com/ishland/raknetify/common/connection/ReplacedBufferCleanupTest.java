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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import org.junit.jupiter.api.Test;

/**
 * Checks buffer ownership across both leaking and already-correct optional decoder implementations.
 */
class ReplacedBufferCleanupTest {

    /** Leaves an unchanged helper alive for the decoder's next frame and eventual removal. */
    @Test
    void unchangedHelperRemainsOwned() {
        ByteBuf helper = Unpooled.directBuffer(3);
        try {
            ReplacedBufferCleanup.releaseAbandoned(helper, helper, 1);
            assertEquals(1, helper.refCnt());
        } finally {
            ReferenceCountUtil.safeRelease(helper);
        }
    }

    /**
     * Releases the abandoned constructor allocation while preserving the replacement allocation.
     */
    @Test
    void replacedHelperReleasesOldOwnership() {
        checkReplacement(1, false, 0);
    }

    /** Does not release an old helper twice when the optional decoder already cleans it up. */
    @Test
    void previouslyReleasedHelperStaysReleased() {
        checkReplacement(1, true, 0);
    }

    /** Preserves another owner's retained reference when the decoder already released its own. */
    @Test
    void correctedSharedHelperKeepsOtherOwner() {
        checkReplacement(2, true, 1);
    }

    /** Removes exactly the abandoned ownership reference without consuming another owner's hold. */
    @Test
    void abandonedSharedHelperKeepsOtherOwner() {
        checkReplacement(2, false, 1);
    }

    /**
     * Models a decoder replacement with an optional prior release and checks both buffer lifetimes.
     *
     * @param references ownership count before the decoder runs
     * @param released whether the decoder already relinquished its old helper
     * @param expected remaining old-buffer owners after compatibility cleanup
     */
    private static void checkReplacement(
            final int references, final boolean released, final int expected) {
        ByteBuf previous = Unpooled.directBuffer(3);
        ByteBuf current = Unpooled.directBuffer(5);
        if (references > 1) {
            previous.retain(references - 1);
        }
        try {
            if (released) {
                previous.release();
            }
            ReplacedBufferCleanup.releaseAbandoned(previous, current, references);
            assertEquals(expected, previous.refCnt());
            assertEquals(1, current.refCnt());
        } finally {
            if (previous.refCnt() > 0) {
                previous.release(previous.refCnt());
            }
            current.release();
        }
    }
}
