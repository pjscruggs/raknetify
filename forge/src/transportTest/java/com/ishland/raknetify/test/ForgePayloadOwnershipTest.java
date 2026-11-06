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

import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.network.ForgePayload;

/** Verifies that Forge payload copies have independent, garbage-collectable storage after decoding. */
final class ForgePayloadOwnershipTest {
    /** Prevents construction outside the native loader fixture. */
    private ForgePayloadOwnershipTest() {
    }

    /**
     * Decodes a registered channel through Forge's actual codec and checks storage without waiting for GC sampling.
     * @throws AssertionError if decoding retains off-heap memory or depends on the source buffer's lifetime
     */
    static void verify() {
        FriendlyByteBuf source = new FriendlyByteBuf(Unpooled.directBuffer().writeInt(0x12345678));
        ForgePayload payload = null;
        try {
            payload = (ForgePayload) ForgeHooks.<FriendlyByteBuf>getCustomPayloadCodec(
                    Identifier.fromNamespaceAndPath("forge", "handshake"), 1024).decode(source);
            if (payload.data().isDirect() || !payload.data().hasArray()
                    || payload.data().alloc() != UnpooledByteBufAllocator.DEFAULT) {
                throw new AssertionError("Forge payload owns an unreleased allocator buffer");
            }
            if (source.readableBytes() != 0) throw new AssertionError("Payload bytes were not consumed");
            source.setInt(0, 0);
            source.release();
            if (payload.data().readInt() != 0x12345678) throw new AssertionError("Payload aliases its source");
            System.out.println("PASS Forge payload storage ownership");
        } finally {
            if (source.refCnt() != 0) source.release();
            if (payload != null) payload.data().release();
        }
    }
}
