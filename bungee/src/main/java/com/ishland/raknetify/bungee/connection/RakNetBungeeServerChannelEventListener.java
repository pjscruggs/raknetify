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

package com.ishland.raknetify.bungee.connection;

import com.google.common.base.Preconditions;
import com.ishland.raknetify.bungee.RaknetifyBungeePlugin;
import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.DelayedPacketSender;
import com.ishland.raknetify.common.connection.SynchronizationLayer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.packet.KeepAlive;
import net.md_5.bungee.protocol.packet.Respawn;

import network.ycc.raknet.RakNet;

import java.util.Map;

/**
 * Answers backend keepalives using the RakNet client's measured RTT instead of relaying them to the
 * client.
 */
public class RakNetBungeeServerChannelEventListener extends DelayedPacketSender {

    /** Pipeline name for the backend-facing keepalive and connection diagnostic handler. */
    public static final String NAME = "raknetify-bungee-downstream-event-listener";

    /** RakNet client connection whose transport RTT determines backend keepalive reply timing. */
    private final Channel clientChannel;

    /**
     * Associates a backend pipeline with the RakNet client it serves.
     *
     * @param clientChannel client-facing channel required to have a RakNet configuration
     */
    public RakNetBungeeServerChannelEventListener(final Channel clientChannel) {
        Preconditions.checkArgument(clientChannel.config() instanceof RakNet.Config);
        this.clientChannel = clientChannel;
    }

    /**
     * Resynchronizes client ordering on respawn and answers backend keepalives locally. Replies are
     * delayed by the client RTT minus four milliseconds, bounded below by zero, to account for
     * scheduling overhead without involving Minecraft's client keepalive path.
     *
     * @param ctx backend-facing context used to schedule and send the reply
     * @param msg decoded backend packet wrapper
     * @throws Exception if superclass forwarding fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof PacketWrapper wrapper) {
            final DefinedPacket packet = wrapper.packet;
            if (packet instanceof Respawn) {
                clientChannel
                        .write(SynchronizationLayer.SYNC_REQUEST_OBJECT)
                        .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            } else if (packet instanceof KeepAlive keepAlive) {
                if (Constants.DEBUG) {
                    RaknetifyBungeePlugin.LOGGER.info(
                            "Received downstream keepalive, swallowing it");
                }
                final long rttNanos = RakNet.config(clientChannel).getRTTNanos();
                try {
                    replyAfter(
                            ctx,
                            new KeepAlive(keepAlive.getRandomId()),
                            Math.max(rttNanos - 4_000_000, 0));
                } finally {
                    wrapper.trySingleRelease();
                }
                return; // prevent keepalive from being sent to clients
            }
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Propagates the failure and prints the backend pipeline layout alongside its stack trace.
     *
     * @param ctx backend context reporting the exception
     * @param cause original connection failure
     * @throws Exception if superclass exception forwarding fails
     */
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
            throws Exception {
        super.exceptionCaught(ctx, cause);
        cause.printStackTrace();
        for (Map.Entry<String, ChannelHandler> entry :
                ctx.channel().pipeline().toMap().entrySet()) {
            System.out.println(
                    "%s: %s".formatted(entry.getKey(), entry.getValue().getClass().getName()));
        }
    }
}
