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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import network.ycc.raknet.frame.FrameData;

/**
 * Releases transport frames left unconsumed after decoding so they cannot reach Minecraft handlers.
 */
public class FrameDataBlocker extends ChannelInboundHandlerAdapter {

    /**
     * Enables diagnostics for unconsumed frames through the raknetify.printBlockedFrames property.
     */
    private static final boolean printBlockedFrames = Boolean.getBoolean("raknetify.printBlockedFrames");

    /**
     * Drops and releases remaining RakNet frames while forwarding decoded Minecraft messages.
     *
     * @param ctx inbound context receiving messages that are no longer transport frames
     * @param msg decoded payload or unrecognized transport frame
     * @throws Exception reserved by the Netty callback contract
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FrameData data) {
            if (printBlockedFrames) System.out.println("Blocked %s".formatted(msg));
            data.release();
            return;
        }
        ctx.fireChannelRead(msg);
    }
}
