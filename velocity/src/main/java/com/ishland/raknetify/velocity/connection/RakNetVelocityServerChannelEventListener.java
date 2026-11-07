/*
 * This file is a part of the Velocity implementation of the Raknetify
 * project, licensed under GPLv3.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ishland.raknetify.velocity.connection;

import com.google.common.base.Preconditions;
import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.DelayedPacketSender;
import com.ishland.raknetify.velocity.RaknetifyVelocityPlugin;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;

import network.ycc.raknet.RakNet;

import java.util.Map;

/**
 * Answers backend keepalives using the RakNet client's measured RTT instead of relaying them to the
 * client.
 */
public class RakNetVelocityServerChannelEventListener extends DelayedPacketSender {

    /** Pipeline name for the backend-facing keepalive and connection diagnostic handler. */
    public static final String NAME = "raknetify-bungee-downstream-event-listener";

    /** RakNet client connection whose transport RTT determines backend keepalive reply timing. */
    private final Channel clientChannel;

    /**
     * Associates a backend pipeline with the RakNet client it serves.
     *
     * @param clientChannel client-facing channel required to have a RakNet configuration
     */
    public RakNetVelocityServerChannelEventListener(final Channel clientChannel) {
        Preconditions.checkArgument(clientChannel.config() instanceof RakNet.Config);
        this.clientChannel = clientChannel;
    }

    /**
     * Answers backend keepalives locally and forwards other decoded packets to the proxy. Replies
     * are delayed by the client RTT minus four milliseconds, bounded below by zero, to account for
     * scheduling overhead without involving Minecraft's client keepalive path.
     *
     * @param ctx backend-facing context used to schedule and send the reply
     * @param msg decoded backend packet
     * @throws Exception if superclass forwarding fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof KeepAlivePacket) {
            if (Constants.DEBUG) {
                RaknetifyVelocityPlugin.LOGGER.info("Received downstream keepalive, swallowing it");
            }
            final long rttNanos = RakNet.config(clientChannel).getRTTNanos();
            replyAfter(ctx, msg, Math.max(rttNanos - 4_000_000, 0));
            return; // prevent keepalive from being sent to clients
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
