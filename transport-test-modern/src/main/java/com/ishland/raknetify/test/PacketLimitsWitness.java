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
import net.fabricmc.api.ModInitializer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.CompressionDecoder;

/** Checks PacketFixer's transformed native limits without changing any production codec. */
public final class PacketLimitsWitness implements ModInitializer {

    /**
     * Verifies larger strings and compressed packets only when the runner explicitly requests them.
     *
     * @throws IllegalStateException if native codecs reject or corrupt a value above vanilla's limit
     */
    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("raknetify.test.packetLimits")) return;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            String expected = "p".repeat(40000);
            buffer.writeUtf(expected);
            if (!expected.equals(buffer.readUtf())) throw new IllegalStateException("Native string corruption");
            NativeCompressionWitness.verify(CompressionEncoder.class, CompressionDecoder.class);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported native compression constructor", failure);
        } finally {
            buffer.release();
        }
        System.out.println("PASS native string above 32767 characters");
    }
}
