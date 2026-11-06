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
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

/** Exercises the installed mods' actual Minecraft compression codecs above vanilla's size bound. */
public final class NativeCompressionWitness {

    /** Marker distinguishes a fully decoded message from an empty or truncated codec output. */
    private static final long MARKER = 0x316db29e074ac58fL;

    /** Prevents construction of the static codec witness. */
    private NativeCompressionWitness() {}

    /**
     * Round-trips a message larger than eight MiB through the native, mixin-transformed codecs.
     *
     * @param encoder Minecraft compression encoder class in the active namespace
     * @param decoder Minecraft compression decoder class in the active namespace
     * @throws ReflectiveOperationException if the native constructor contract changes
     * @throws IllegalStateException if compression produces no complete payload
     */
    public static void verify(final Class<?> encoder, final Class<?> decoder)
            throws ReflectiveOperationException {
        ChannelHandler encodeHandler =
                (ChannelHandler) encoder.getConstructor(int.class).newInstance(0);
        EmbeddedChannel outgoing = new EmbeddedChannel(encodeHandler);
        EmbeddedChannel incoming = new EmbeddedChannel(decoder(decoder));
        try {
            outgoing.writeOutbound(
                    TransportTestPayload.create(
                            outgoing.alloc(), TransportTestPayload.LARGE_SIZE, MARKER));
            ByteBuf compressed = outgoing.readOutbound();
            if (compressed == null) {
                throw new IllegalStateException("No compressed payload");
            }
            incoming.writeInbound(compressed);
            ByteBuf decoded = incoming.readInbound();
            if (decoded == null) {
                throw new IllegalStateException("No decompressed payload");
            }
            try {
                TransportTestPayload.verify(decoded, MARKER);
                if (decoded.readableBytes() != TransportTestPayload.LARGE_SIZE) {
                    throw new IllegalStateException("Truncated native decompression");
                }
            } finally {
                decoded.release();
            }
        } finally {
            outgoing.finishAndReleaseAll();
            incoming.finishAndReleaseAll();
        }
        System.out.println("PASS native compression above eight MiB");
    }

    /**
     * Supports the historical decoder constructor and the later explicit validation flag.
     *
     * @param type native decoder class transformed by the installed mods
     * @return decoder with compression enabled and size validation left active
     * @throws ReflectiveOperationException if neither supported constructor exists
     */
    private static ChannelHandler decoder(final Class<?> type) throws ReflectiveOperationException {
        try {
            return (ChannelHandler)
                    type.getConstructor(int.class, boolean.class).newInstance(0, true);
        } catch (NoSuchMethodException ignored) {
            return (ChannelHandler) type.getConstructor(int.class).newInstance(0);
        }
    }
}
