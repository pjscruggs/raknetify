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

package com.ishland.raknetify.spigot;

import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;

import io.netty.channel.ChannelPipeline;

import java.security.PrivateKey;

import javax.crypto.SecretKey;

/** Adapts encryption only after the native login handler has accepted the key exchange. */
final class SpigotCipherState {
    /** Native server key used by the same key-exchange packet that the server validates. */
    private final PrivateKey privateKey;

    /** Candidate shared secret retained only until the native server enables encryption. */
    private SecretKey pending;

    /** Rejects a second encryption negotiation on an already encrypted connection. */
    private boolean installed;

    /**
     * Retains the server key for one accepted connection without changing native authentication.
     *
     * @param privateKey server-owned login private key
     */
    SpigotCipherState(final PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * Reads a candidate secret but leaves challenge and account verification to the native packet
     * listener. No transport cipher is enabled until that listener installs both native cipher
     * handlers.
     *
     * @param packet decoded native key-response packet
     * @throws ReflectiveOperationException if the native packet cannot recover its shared secret
     * @throws IllegalStateException if encryption has already been negotiated
     */
    void capture(final Object packet) throws ReflectiveOperationException {
        if (installed || pending != null) {
            throw new IllegalStateException("Duplicate encryption key response");
        }
        pending =
                (SecretKey)
                        packet.getClass()
                                .getMethod("getSecretKey", PrivateKey.class)
                                .invoke(packet, privateKey);
    }

    /**
     * Replaces newly enabled byte-stream ciphers with frame encryption before either direction
     * processes more data. Removing the native handlers also closes Paper's native cipher
     * allocations.
     *
     * @param pipeline connection pipeline owned by the current event loop
     * @throws Exception if cipher initialization fails
     * @throws IllegalStateException if encryption appeared without a captured key exchange or only
     *     one direction is enabled
     */
    void refresh(final ChannelPipeline pipeline) throws Exception {
        if (pipeline.get("encrypt") == null && pipeline.get("decrypt") == null) {
            return;
        }
        if (installed || pending == null) {
            throw new IllegalStateException("Unexpected native encryption transition");
        }
        if (pipeline.get("encrypt") == null || pipeline.get("decrypt") == null) {
            throw new IllegalStateException("Incomplete native encryption transition");
        }
        MultiChannellingEncryption encryption = new MultiChannellingEncryption(pending, false);
        pipeline.addBefore(
                MultiChannelingStreamingCompression.NAME,
                MultiChannellingEncryption.NAME,
                encryption);
        pipeline.remove("encrypt");
        pipeline.remove("decrypt");
        pending = null;
        installed = true;
    }
}
