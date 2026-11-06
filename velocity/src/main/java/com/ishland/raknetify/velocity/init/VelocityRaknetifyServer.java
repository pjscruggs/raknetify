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

package com.ishland.raknetify.velocity.init;

import static com.ishland.raknetify.common.util.ReflectionUtil.accessible;

import com.google.common.base.Preconditions;
import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.proxy.ProxyProtocol;
import com.ishland.raknetify.common.util.NetworkInterfaceListener;
import com.ishland.raknetify.velocity.RaknetifyVelocityPlugin;
import com.ishland.raknetify.velocity.connection.RakNetVelocityConnectionUtil;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.event.proxy.ListenerCloseEvent;
import com.velocitypowered.api.network.ListenerType;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.network.ConnectionManager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.concurrent.TimeUnit;

/**
 * Mirrors Velocity's Minecraft listener with UDP endpoints using the proxy's own network transport.
 */
public class VelocityRaknetifyServer {

    /** Optional UDP-only port from raknetify.velocity.portOverride, with invalid values ignored. */
    private static final int portOverride =
            Integer.getInteger("raknetify.velocity.portOverride", -1);

    /** Accessible ChannelInitializer hook used to reuse Velocity's standard connection setup. */
    private static final Method INIT_CHANNEL;

    static {
        try {
            INIT_CHANNEL =
                    accessible(
                            ChannelInitializer.class.getDeclaredMethod(
                                    "initChannel", Channel.class));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Bound UDP endpoints, guarded by this set during binding, address removal, and shutdown. */
    private static final ReferenceOpenHashSet<ChannelFuture> channels =
            new ReferenceOpenHashSet<>();

    /** Original Minecraft listener address used to recognize wildcard interface changes. */
    private static volatile InetSocketAddress currentServerAddress;

    /** Whether interface changes should create or remove companion UDP endpoints. */
    private static volatile boolean active;

    static {
        NetworkInterfaceListener.addListener(event -> {
            RaknetifyVelocityPlugin.PROXY
                    .getScheduler()
                    .buildTask(
                            RaknetifyVelocityPlugin.INSTANCE,
                            () -> {
                                if (!active) {
                                    return;
                                }
                                final InetSocketAddress address = currentServerAddress;
                                if (address != null && address.getAddress().isAnyLocalAddress()) {
                                    if (event.added()) {
                                        startServer(
                                                new InetSocketAddress(
                                                        event.address(), address.getPort()));
                                    } else {
                                        synchronized (channels) {
                                            final ObjectIterator<ChannelFuture> iterator =
                                                    channels.iterator();
                                            while (iterator.hasNext()) {
                                                final ChannelFuture future = iterator.next();
                                                if (((InetSocketAddress)
                                                                future.channel().localAddress())
                                                        .getAddress()
                                                        .equals(event.address())) {
                                                    closeServer(future);
                                                    iterator.remove();
                                                }
                                            }
                                        }
                                    }
                                }
                            })
                    .delay(100, TimeUnit.MILLISECONDS)
                    .schedule();
        });
    }

    /**
     * Creates UDP listeners when Velocity binds its Minecraft listener, expanding wildcard
     * addresses. Individual interface binding failures are logged while other addresses are still
     * attempted.
     *
     * @param evt bound-listener event, ignored for non-Minecraft listener types
     * @throws RuntimeException if wildcard interface enumeration fails
     */
    public static void start(final ListenerBoundEvent evt) {
        if (evt.getListenerType() != ListenerType.MINECRAFT) {
            return;
        }
        Preconditions.checkArgument(RaknetifyVelocityPlugin.PROXY instanceof VelocityServer);
        Preconditions.checkState(channels.isEmpty(), "Raknetify Server is already running");
        final InetSocketAddress address = evt.getAddress();
        currentServerAddress = address;
        active = true;

        if (address.getAddress().isAnyLocalAddress()) {
            try {
                for (NetworkInterface networkInterface :
                        NetworkInterface.networkInterfaces().toList()) {
                    for (InetAddress inetAddress : networkInterface.inetAddresses().toList()) {
                        try {
                            startServer(new InetSocketAddress(inetAddress, address.getPort()));
                        } catch (Throwable t) {
                            RaknetifyVelocityPlugin.LOGGER.error(
                                    "Failed to start raknetify server", t);
                        }
                    }
                }
            } catch (Throwable t) {
                throw new RuntimeException("Failed to start raknetify server", t);
            }
        } else {
            startServer(address);
        }
    }

    /**
     * Binds one UDP endpoint with Velocity's datagram factory, event loops, and child initializer.
     * Duplicate addresses are skipped while holding the channel-set lock. Bind I/O failures are
     * logged.
     *
     * @param address concrete interface address and default Minecraft port
     * @throws RuntimeException if proxy reflection or a non-I/O initialization step fails
     */
    private static void startServer(final InetSocketAddress address) {
        try {
            final ConnectionManager cm =
                    (ConnectionManager)
                            accessible(VelocityServer.class.getDeclaredField("cm"))
                                    .get(RaknetifyVelocityPlugin.PROXY);
            final Object transportType =
                    accessible(ConnectionManager.class.getDeclaredField("transportType")).get(cm);
            final ChannelFactory<? extends DatagramChannel> datagramChannelFactory =
                    (ChannelFactory<? extends DatagramChannel>)
                            accessible(
                                            Class.forName(
                                                            "com.velocitypowered.proxy.network.TransportType")
                                                    .getDeclaredField("datagramChannelFactory"))
                                    .get(transportType);
            final EventLoopGroup bossGroup =
                    (EventLoopGroup)
                            accessible(ConnectionManager.class.getDeclaredField("bossGroup"))
                                    .get(cm);
            final EventLoopGroup workerGroup =
                    (EventLoopGroup)
                            accessible(ConnectionManager.class.getDeclaredField("workerGroup"))
                                    .get(cm);

            final boolean hasPortOverride = portOverride > 0 && portOverride < 65535;
            InetSocketAddress actualAddress =
                    hasPortOverride
                            ? new InetSocketAddress(address.getAddress(), portOverride)
                            : address;

            synchronized (channels) {
                for (ChannelFuture future : channels) {
                    if (future.channel().localAddress().equals(actualAddress)) {
                        return;
                    }
                }

                ChannelFuture future =
                        new ServerBootstrap()
                                .channelFactory(() ->
                                        ProxyProtocol.create(() -> {
                                            final DatagramChannel channel =
                                                    datagramChannelFactory.newChannel();
                                            channel.config()
                                                    .setOption(
                                                            ChannelOption.IP_TOS,
                                                            RakNetConnectionUtil.DEFAULT_IP_TOS);
                                            channel.config()
                                                    .setRecvByteBufAllocator(
                                                            new FixedRecvByteBufAllocator(
                                                                            Constants.LARGE_MTU
                                                                                    + 512)
                                                                    .maxMessagesPerRead(128));
                                            return channel;
                                        }))
                                .group(bossGroup, workerGroup)
                                .childHandler(
                                        new ChannelInitializer<>() {
                                            /**
                                             * Wraps Velocity's accepted-channel initialization with
                                             * RakNet transport and codec setup.
                                             *
                                             * @param channel accepted RakNet application channel
                                             * @throws Exception if invoking Velocity's child
                                             *     initializer fails
                                             */
                                            @Override
                                            protected void initChannel(final Channel channel)
                                                    throws Exception {
                                                RakNetVelocityConnectionUtil.initChannel(channel);
                                                INIT_CHANNEL.invoke(
                                                        cm.serverChannelInitializer.get(), channel);
                                                RakNetVelocityConnectionUtil.postInitChannel(
                                                        channel, false);
                                            }
                                        })
                                .localAddress(actualAddress)
                                .bind()
                                .syncUninterruptibly();

                RaknetifyVelocityPlugin.LOGGER.info(
                        "Raknetify server started on {}", future.channel().localAddress());
                channels.add(future);
            }

        } catch (Throwable t) {
            if (t instanceof IOException) {
                RaknetifyVelocityPlugin.LOGGER.error(
                        "Raknetify server failed to start on {}: {}", address, t.getMessage());
                return;
            }
            throw new RuntimeException("Failed to start Raknetify server", t);
        }
    }

    /**
     * Disables interface-change handling and closes all UDP endpoints with the Minecraft listener.
     *
     * @param evt closed-listener event, ignored for non-Minecraft listener types
     */
    public static void stop(final ListenerCloseEvent evt) {
        if (evt.getListenerType() != ListenerType.MINECRAFT) {
            return;
        }
        Preconditions.checkArgument(RaknetifyVelocityPlugin.PROXY instanceof VelocityServer);
        Preconditions.checkState(!channels.isEmpty(), "Raknetify Server is not running");
        synchronized (channels) {
            active = false;
            for (ChannelFuture channel : channels) {
                closeServer(channel);
            }

            channels.clear();
        }
    }

    /**
     * Closes a UDP endpoint and waits for completion, logging any interruption.
     *
     * @param channel bind future identifying the listener to close
     */
    private static void closeServer(final ChannelFuture channel) {
        RaknetifyVelocityPlugin.LOGGER.info(
                "Closing Raknetify server {}", channel.channel().localAddress());
        try {
            channel.channel().close().sync();
        } catch (InterruptedException e) {
            RaknetifyVelocityPlugin.LOGGER.error("Interrupted whilst closing raknetify server");
        }
    }
}
