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

import com.ishland.raknetify.common.util.ReflectionUtil;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Publishes RakNet RTT plus its standard deviation as the proxy-visible player ping. */
public class RakNetVelocityPingUpdater extends ChannelDuplexHandler {

    /** Pipeline name for the transport-based player ping updater. */
    public static final String NAME = "raknetify-velocity-ping-updater";

    /** Accessible Velocity player mutator used to publish the transport-based ping. */
    private static final Method PLAYER_SET_PING;

    static {
        try {
            PLAYER_SET_PING =
                    ReflectionUtil.accessible(
                            ConnectedPlayer.class.getDeclaredMethod("setPing", long.class));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /** Player whose proxy-visible ping follows this connection's transport measurements. */
    private final ConnectedPlayer player;

    /** Once-per-second event-loop task, or null before installation and after removal. */
    private @Nullable ScheduledFuture<?> updateTask;

    /**
     * Associates transport ping samples with an authenticated proxy player.
     *
     * @param player player record whose ping is updated while this handler is installed
     */
    public RakNetVelocityPingUpdater(final ConnectedPlayer player) {
        this.player = Objects.requireNonNull(player);
    }

    /**
     * Schedules millisecond ping updates using RakNet RTT and its standard deviation.
     *
     * @param ctx installed context whose channel supplies transport measurements and scheduling
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        if (ctx.channel().config() instanceof RakNet.Config config) {
            updateTask =
                    ctx.channel()
                            .eventLoop()
                            .scheduleAtFixedRate(
                                    () -> {
                                        try {
                                            PLAYER_SET_PING.invoke(
                                                    player,
                                                    ((config.getRTTNanos()
                                                                    + config.getRTTStdDevNanos())
                                                            / 1_000_000));
                                        } catch (IllegalAccessException
                                                | InvocationTargetException e) {
                                            throw new RuntimeException(e);
                                        }
                                    },
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
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
    }
}
