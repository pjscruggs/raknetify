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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * Drains terminal disconnect packets before invoking native callbacks that may wait for channel
 * closure.
 */
final class SpigotDisconnectHandler extends ChannelOutboundHandlerAdapter {
    /**
     * Keeps ordinary packet promises unchanged and gives terminal packets an asynchronous close
     * sequence.
     *
     * @param context outbound context immediately before the native packet listener
     * @param message native packet to encode and send
     * @throws Exception if ordinary outbound forwarding fails
     * @param promise native send callback, completed after transport closure for disconnect packets
     */
    @Override
    public void write(
            final ChannelHandlerContext context, final Object message, final ChannelPromise promise)
            throws Exception {
        if (isDisconnect(message.getClass().getName())) {
            writeAndClose(context, message, promise);
        } else {
            super.write(context, message, promise);
        }
    }

    /**
     * Recognizes both native login and common-protocol terminal packets without linking Minecraft
     * classes.
     *
     * @param name complete native packet class name
     * @return true only for a packet that ends this connection
     */
    static boolean isDisconnect(final String name) {
        return "net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket".equals(name)
                || "net.minecraft.network.protocol.common.ClientboundDisconnectPacket".equals(name);
    }

    /**
     * Lets the transport drain the reason while keeping a blocking native close callback off the
     * live event loop. The lower graceful-close handler tracks the separate write promise and
     * bounds acknowledgement waiting.
     *
     * @param context outbound context before transport encoding and graceful close
     * @param message terminal packet whose ownership passes to the pipeline
     * @param promise original send completion, retaining the actual packet write outcome
     */
    static void writeAndClose(
            final ChannelHandlerContext context,
            final Object message,
            final ChannelPromise promise) {
        ChannelPromise written = context.newPromise();
        context.writeAndFlush(message, written)
                .addListener(sent ->
                        context.channel()
                                .closeFuture()
                                .addListener(closed -> {
                                    if (sent.isSuccess()) {
                                        promise.trySuccess();
                                    } else {
                                        promise.tryFailure(sent.cause());
                                    }
                                }));
        context.close()
                .addListener(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }
}
