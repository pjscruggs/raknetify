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

package com.ishland.raknetify.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/** Generates bounded deterministic messages that cross Minecraft's usual packet-size limits. */
public final class TransportTestPayload {

    /** Slightly exceeds the largest vanilla packet bound without allowing unbounded test allocations. */
    public static final int LARGE_SIZE = 8 * 1024 * 1024 + 64;

    /** Prevents instantiation of deterministic payload helpers. */
    private TransportTestPayload() {
    }

    /**
     * Allocates a marker followed by a byte pattern independent of encryption and fragmentation.
     *
     * @param allocator connection allocator owning the returned buffer
     * @param size requested message length, including its eight-byte marker
     * @param marker direction-specific challenge value
     * @return owned message transferred to the caller
     * @throws IllegalArgumentException if the size falls outside the bounded fixture range
     */
    public static ByteBuf create(ByteBufAllocator allocator, int size, long marker) {
        if (size < 8 || size > LARGE_SIZE) throw new IllegalArgumentException("Invalid fixture payload size");
        ByteBuf buffer = allocator.buffer(size).writeLong(marker);
        for (int i = 8; i < size; i++) buffer.writeByte(i % 251);
        return buffer;
    }

    /**
     * Checks every plaintext byte without consuming the buffer needed for the echo response.
     *
     * @param buffer complete message whose reader index points to its marker
     * @param marker expected direction-specific challenge
     * @throws IllegalArgumentException if the bounded length, marker, or any byte differs
     */
    public static void verify(ByteBuf buffer, long marker) {
        int size = buffer.readableBytes();
        int start = buffer.readerIndex();
        if (size < 8 || size > LARGE_SIZE) throw new IllegalArgumentException("Invalid fixture payload size");
        if (buffer.getLong(start) != marker) throw new IllegalArgumentException("Encrypted challenge mismatch");
        for (int i = 8; i < size; i++) {
            if (buffer.getUnsignedByte(start + i) != i % 251) {
                throw new IllegalArgumentException("Corrupt fixture payload at " + i);
            }
        }
    }
}
