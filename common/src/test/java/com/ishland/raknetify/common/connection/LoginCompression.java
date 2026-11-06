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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Implements vanilla login compression for probes that deliberately do not negotiate RakNet
 * streaming compression.
 */
final class LoginCompression {
    /** Negative until the server sends its native compression negotiation packet. */
    private int threshold = -1;

    /**
     * Consumes a complete native compression negotiation without treating other login responses as
     * configuration.
     *
     * @param packet decoded login packet
     * @return whether this was a valid compression negotiation
     * @throws IllegalArgumentException if compression is repeated, malformed or uses an invalid
     *     threshold
     */
    boolean configure(final byte[] packet) {
        ByteBuf input = Unpooled.wrappedBuffer(packet);
        try {
            if (RaknetStatusProbe.readVarint(input) != 3) {
                return false;
            }
            int value = RaknetStatusProbe.readVarint(input);
            if (threshold >= 0 || value < 0 || input.isReadable()) {
                throw new IllegalArgumentException("Invalid login compression negotiation");
            }
            threshold = value;
            return true;
        } finally {
            input.release();
        }
    }

    /**
     * Validates zlib lengths and threshold rules before the native admission reason is inspected.
     *
     * @param packet complete wire frame without a TCP length prefix
     * @return exact decompressed Minecraft packet
     * @throws DataFormatException if the zlib stream is corrupt or incomplete
     * @throws IllegalArgumentException if declared lengths or threshold rules are violated
     */
    byte[] decode(final byte[] packet) throws DataFormatException {
        if (threshold < 0) {
            return packet;
        }
        ByteBuf input = Unpooled.wrappedBuffer(packet);
        try {
            int size = RaknetStatusProbe.readVarint(input);
            byte[] encoded = new byte[input.readableBytes()];
            input.readBytes(encoded);
            if (size == 0) {
                if (encoded.length >= threshold) {
                    throw new IllegalArgumentException("Uncompressed packet exceeds threshold");
                }
                return encoded;
            }
            if (size < threshold || size > 1048576) {
                throw new IllegalArgumentException("Invalid compressed login size");
            }
            return inflate(encoded, size);
        } finally {
            input.release();
        }
    }

    /**
     * Requires an exact complete stream with neither truncation nor hidden trailing compressed
     * bytes.
     *
     * @param encoded complete zlib payload
     * @param size bounded declared output length
     * @return reconstructed packet
     * @throws DataFormatException if decompression fails
     * @throws IllegalArgumentException if the stream does not match its declared boundary
     */
    private static byte[] inflate(final byte[] encoded, final int size) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(encoded);
            byte[] result = new byte[size];
            int count = inflater.inflate(result);
            if (count != size || !inflater.finished() || inflater.getRemaining() != 0) {
                throw new IllegalArgumentException(
                        "Incomplete or oversized compressed login packet");
            }
            return result;
        } finally {
            inflater.end();
        }
    }

    /**
     * Encodes optional query replies under the negotiated rules and transfers ownership to a fresh
     * frame.
     *
     * @param packet owned decoded reply, released here when a replacement is created
     * @return owned wire frame for the caller to write
     */
    ByteBuf encode(final ByteBuf packet) {
        if (threshold < 0) {
            return packet;
        }
        try {
            byte[] bytes = new byte[packet.readableBytes()];
            packet.readBytes(bytes);
            ByteBuf result = Unpooled.buffer();
            if (bytes.length < threshold) {
                result.writeByte(0).writeBytes(bytes);
            } else {
                RaknetStatusProbe.writeVarint(result, bytes.length);
                result.writeBytes(deflate(bytes));
            }
            return result;
        } finally {
            packet.release();
        }
    }

    /**
     * Finishes the tiny probe reply's zlib stream without retaining native compressor state.
     *
     * @param bytes complete decoded query response
     * @return compressed payload
     * @throws IllegalStateException if a bounded probe reply cannot be completely compressed
     */
    private static byte[] deflate(final byte[] bytes) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(bytes);
            deflater.finish();
            byte[] output = new byte[bytes.length + 64];
            int count = deflater.deflate(output);
            if (!deflater.finished()) {
                throw new IllegalStateException("Probe reply compression overflow");
            }
            return java.util.Arrays.copyOf(output, count);
        } finally {
            deflater.end();
        }
    }
}
