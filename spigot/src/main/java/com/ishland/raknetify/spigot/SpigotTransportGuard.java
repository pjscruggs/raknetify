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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.util.ReferenceCountUtil;

/** Removes redundant packet compression before it can consume or transform a RakNet payload. */
final class SpigotTransportGuard extends ChannelDuplexHandler {
    /** Encryption state shared with the decoded-packet observer. */
    private final SpigotCipherState cipher;
    /** True only after the server's compression packet was deliberately replaced by negotiated streaming compression. */
    private boolean streamingOnly;

    /**
     * Links inbound transport checks to the same login state used by outbound packets.
     * @param cipher connection-local encryption adapter
     */
    SpigotTransportGuard(SpigotCipherState cipher) { this.cipher = cipher; }

    /**
     * Records whether the peer has already negotiated streaming compression before suppressing a setup packet.
     * @param pipeline current connection pipeline
     * @return true when vanilla packet compression must be disabled for this connection
     */
    boolean suppressCompression(ChannelPipeline pipeline) {
        var compression = pipeline.get(MultiChannelingStreamingCompression.class);
        streamingOnly = compression != null && compression.isActive();
        return streamingOnly;
    }

    /**
     * Reconciles handlers installed by native login callbacks before the next packet crosses them.
     * @param pipeline pipeline owned by the current channel event loop
     * @throws Exception if a native encryption transition is invalid
     */
    void refresh(ChannelPipeline pipeline) throws Exception {
        cipher.refresh(pipeline);
        if (streamingOnly) {
            SpigotConnection.remove(pipeline, "compress");
            SpigotConnection.remove(pipeline, "decompress");
        }
    }

    /**
     * Refreshes dynamic login handlers before forwarding the next transport frame.
     * @param ctx context before streaming compression and Minecraft decoding
     * @param msg incoming transport frame or unrelated event
     * @throws Exception if handler reconciliation or downstream decoding fails
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            refresh(ctx.pipeline());
        } catch (Exception exception) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            throw exception;
        }
        ctx.fireChannelRead(msg);
    }
}
