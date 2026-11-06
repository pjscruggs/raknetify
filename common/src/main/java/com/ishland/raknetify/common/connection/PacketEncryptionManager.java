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

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;

// TODO [VanillaCopy] from fabric
/**
 * Applies a configured cipher independently to each frame payload using reusable byte arrays.
 * Calling doFinal for each payload resets the cipher to its initialized state between frames.
 * Instances must stay on one pipeline thread because the cipher and scratch arrays are mutable.
 */
public class PacketEncryptionManager {
    /** Initialized encryption or decryption cipher reused for independent frame payloads. */
    private final Cipher cipher;

    /** Reusable input array grown to fit the largest payload seen so far. */
    private byte[] conversionBuffer = new byte[0];

    /** Reusable output array sized using the cipher's output-size bound. */
    private byte[] encryptionBuffer = new byte[0];

    /**
     * Takes ownership of a configured cipher for one direction of a connection.
     *
     * @param cipher initialized encryption or decryption cipher used only by this manager
     */
    protected PacketEncryptionManager(final Cipher cipher) {
        this.cipher = cipher;
    }

    /**
     * Consumes the buffer's readable bytes into reusable scratch storage. Only the original
     * readable-byte count is valid, since the array may be larger than the payload.
     *
     * @param buf input buffer whose reader index advances to its writer index
     * @return reusable array containing the consumed bytes at offset zero
     */
    private byte[] toByteArray(final ByteBuf buf) {
        int i = buf.readableBytes();
        if (this.conversionBuffer.length < i) {
            this.conversionBuffer = new byte[i];
        }

        buf.readBytes(this.conversionBuffer, 0, i);
        return this.conversionBuffer;
    }

    /**
     * Transforms one complete payload and appends the cipher output to the destination. Consumes
     * the input reader index but leaves reference-count ownership with the caller.
     *
     * @param buf source payload to encrypt or decrypt
     * @param result destination buffer receiving the transformed bytes
     * @throws GeneralSecurityException if the cipher cannot process the payload
     */
    public void doWork(final ByteBuf buf, final ByteBuf result) throws GeneralSecurityException {
        int i = buf.readableBytes();
        byte[] bs = this.toByteArray(buf);
        int outputSize = this.cipher.getOutputSize(i);
        if (this.encryptionBuffer.length < outputSize) {
            this.encryptionBuffer = new byte[outputSize];
        }

        result.writeBytes(
                this.encryptionBuffer, 0, this.cipher.doFinal(bs, 0, i, this.encryptionBuffer));
    }
}
