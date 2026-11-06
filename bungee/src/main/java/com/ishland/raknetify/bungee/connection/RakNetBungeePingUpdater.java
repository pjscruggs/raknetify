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

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;

import net.md_5.bungee.UserConnection;

import network.ycc.raknet.RakNet;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Publishes RakNet RTT plus its standard deviation as the proxy-visible player ping. */
public class RakNetBungeePingUpdater extends ChannelDuplexHandler {

    /** Pipeline name for the transport-based player ping updater. */
    public static final String NAME = "raknetify-bungee-ping-updater";

    /** Player whose proxy-visible ping follows this connection's transport measurements. */
    private final UserConnection player;

    /** Once-per-second event-loop task, or null before installation and after removal. */
    ScheduledFuture<?> updateTask;

    /**
     * Associates transport ping samples with an authenticated proxy player.
     *
     * @param player player record whose ping is updated while this handler is installed
     */
    public RakNetBungeePingUpdater(final UserConnection player) {
        this.player = Objects.requireNonNull(player);
    }

    /**
     * Schedules millisecond ping updates using RakNet RTT and its standard deviation.
     *
     * @param ctx installed context whose channel supplies transport measurements and scheduling
     */
    public void handlerAdded(final ChannelHandlerContext ctx) {
        if (ctx.channel().config() instanceof RakNet.Config config) {
            updateTask =
                    ctx.channel()
                            .eventLoop()
                            .scheduleAtFixedRate(
                                    () ->
                                            player.setPing(
                                                    (int)
                                                            ((config.getRTTNanos()
                                                                            + config
                                                                                    .getRTTStdDevNanos())
                                                                    / 1_000_000)),
                                    0,
                                    1000,
                                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Cancels further ping updates when the player's handler is removed.
     *
     * @param ctx removed handler context, unused because the task is already retained
     */
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
    }
}
