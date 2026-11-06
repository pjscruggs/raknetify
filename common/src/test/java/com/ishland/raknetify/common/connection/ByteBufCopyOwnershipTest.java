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

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import java.nio.ReadOnlyBufferException;

/** Verifies both sides of a failed native-buffer conversion retain no abandoned reference. */
class ByteBufCopyOwnershipTest {
    /**
     * Copy failures release the original heap buffer and the already allocated direct replacement.
     */
    @Test
    void failedCopyReleasesBothBuffers() {
        var allocator = new ReadOnlyAllocator();
        var channel = new EmbeddedChannel(new ByteBufCopyDecoder());
        channel.config().setAllocator(allocator);
        ByteBuf input = Unpooled.buffer().writeLong(42);
        try {
            assertThrows(ReadOnlyBufferException.class, () -> channel.writeInbound(input));
            assertEquals(0, input.refCnt());
            assertEquals(0, allocator.allocated.refCnt());
        } finally {
            if (input.refCnt() > 0) {
                input.release();
            }
            if (allocator.allocated != null && allocator.allocated.refCnt() > 0) {
                allocator.allocated.release();
            }
            channel.finishAndReleaseAll();
        }
    }

    /** Injects a write failure after allocation so both ownership obligations are observable. */
    private static final class ReadOnlyAllocator extends AbstractByteBufAllocator {
        /** Replacement allocation retained only for observing its reference count. */
        private ByteBuf allocated;

        /**
         * Supplies the deliberately unwritable direct buffer.
         *
         * @param initialCapacity requested initial bytes
         * @param maxCapacity maximum growth permitted by the caller
         * @return reference-counted read-only replacement
         */
        @Override
        protected ByteBuf newDirectBuffer(final int initialCapacity, final int maxCapacity) {
            allocated = Unpooled.directBuffer(initialCapacity, maxCapacity).asReadOnly();
            return allocated;
        }

        /**
         * Supplies ordinary heap storage if requested by the pipeline.
         *
         * @param initialCapacity initial bytes
         * @param maxCapacity maximum growth
         * @return owned writable heap buffer
         */
        @Override
        protected ByteBuf newHeapBuffer(final int initialCapacity, final int maxCapacity) {
            return Unpooled.buffer(initialCapacity, maxCapacity);
        }

        /**
         * Identifies the isolated test allocator's ownership model.
         *
         * @return false because allocations are unpooled
         */
        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
