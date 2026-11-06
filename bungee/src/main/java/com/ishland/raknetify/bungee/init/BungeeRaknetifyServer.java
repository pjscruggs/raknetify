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

package com.ishland.raknetify.bungee.init;

import static com.ishland.raknetify.common.util.ReflectionUtil.accessible;

import com.ishland.raknetify.bungee.RaknetifyBungeePlugin;
import com.ishland.raknetify.bungee.connection.RakNetBungeeConnectionUtil;
import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.proxy.ProxyProtocol;
import com.ishland.raknetify.common.util.NetworkInterfaceListener;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.AttributeKey;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.query.QueryHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * Creates companion UDP listeners from BungeeCord's existing TCP listener configuration. Wildcard
 * binds expand to individual interface addresses and follow later address changes.
 */
public class BungeeRaknetifyServer {

    /**
     * Optional UDP-only port from the raknetify.bungee.portOverride property, with invalid values
     * ignored.
     */
    private static final int portOverride = Integer.getInteger("raknetify.bungee.portOverride", -1);

    /** Accessible ChannelInitializer hook used to reuse the proxy's normal child pipeline. */
    private static final Method INIT_CHANNEL;

    /** Reflective access to the proxy's live listener set for lifecycle interception. */
    private static final Field BUNGEE_LISTENERS_FIELD;

    /** Netty acceptor implementation that retains the child initializer and listener attributes. */
    private static final Class<?> ACCEPTOR_CLASS;

    static {
        try {
            INIT_CHANNEL =
                    accessible(
                            ChannelInitializer.class.getDeclaredMethod(
                                    "initChannel", Channel.class));
            BUNGEE_LISTENERS_FIELD = accessible(BungeeCord.class.getDeclaredField("listeners"));
            ACCEPTOR_CLASS =
                    Class.forName("io.netty.bootstrap.ServerBootstrap$ServerBootstrapAcceptor");
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Wildcard TCP listeners mapped by identity to their per-interface UDP bind futures. */
    private static final Reference2ReferenceOpenHashMap<
                    Channel, ReferenceOpenHashSet<ChannelFuture>>
            channels = new Reference2ReferenceOpenHashMap<>();

    /** UDP bind futures created for listeners with an explicit local address. */
    private static final ReferenceOpenHashSet<ChannelFuture> nonWildcardChannels =
            new ReferenceOpenHashSet<>();

    /** Whether plugin listener creation and interface-change handling are enabled. */
    private static volatile boolean active;

    /** Activation generation used to reject callbacks captured by an earlier plugin activation. */
    private static volatile int activeIndex;

    /** Whether the proxy listener set has already been wrapped with lifecycle hooks. */
    private static boolean injected;

    /** Current interface-change callback, replaced on each activation. */
    private static volatile Consumer<NetworkInterfaceListener.InterfaceAddressChangeEvent> listener;

    /**
     * Wraps the proxy listener set once and attaches UDP endpoints to existing listeners. Collects
     * initial binding failures before reporting them, then arranges interface-change callbacks.
     *
     * @throws RuntimeException if listener inspection or initial UDP setup fails
     */
    public static void inject() {
        if (active) {
            return;
        }
        try {
            active = true;
            final BungeeCord instance = (BungeeCord) ProxyServer.getInstance();

            //            stopAllQueryPorts();

            final Set<Channel> listeners = (Set<Channel>) BUNGEE_LISTENERS_FIELD.get(instance);

            if (!injected) {
                BUNGEE_LISTENERS_FIELD.set(instance, new InjectedSet(listeners));
                injected = true;
            }

            List<Throwable> errors = new ArrayList<>();

            for (Channel listener : listeners) {
                try {
                    injectChannel(instance, listener, true);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }

            int currentActiveIndex = ++activeIndex;
            listener =
                    event -> {
                        onInterfaceChange(instance, currentActiveIndex, event);
                    };
            NetworkInterfaceListener.addListener(event ->
                    instance.getScheduler()
                            .schedule(
                                    RaknetifyBungeePlugin.INSTANCE,
                                    () -> listener.accept(event),
                                    100,
                                    TimeUnit.MILLISECONDS));

            if (!errors.isEmpty()) {
                final RuntimeException exception =
                        new RuntimeException("Failed to start Raknetify server");
                errors.forEach(exception::addSuppressed);
                throw exception;
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to start Raknetify server", t);
        }
    }

    /**
     * Finds a Minecraft listener's child pipeline and binds matching UDP endpoints. Query sockets,
     * Geyser listeners, and incomplete acceptor metadata are excluded.
     *
     * @param instance running BungeeCord proxy
     * @param listener existing proxy listener to inspect
     * @param throwErrors whether binding failures should escape instead of only being logged
     * @throws RuntimeException if setup fails while throwErrors is true
     */
    public static void injectChannel(
            final BungeeCord instance, final Channel listener, final boolean throwErrors) {
        try {
            if (!isListenerCandidate(listener)) {
                return;
            }

            ListenerContext context = findListenerContext(listener);
            if (context == null) {
                return;
            }
            ChannelInitializer<Channel> initializer = context.initializer();
            ListenerInfo info = context.info();

            if (initializer == null) {
                RaknetifyBungeePlugin.LOGGER.severe(
                        "Unable to find channel initializer for listener %s".formatted(listener));
                return;
            }

            if (info == null) {
                RaknetifyBungeePlugin.LOGGER.severe(
                        "Unable to find listener info for listener %s".formatted(listener));
                return;
            }

            if (listener.getClass()
                    .getName()
                    .startsWith("org.geysermc.geyser.network")) { // filter out geyser
                return;
            }

            bindListenerAddresses(instance, listener, initializer, info);

        } catch (Throwable t) {
            if (throwErrors) {
                throw new RuntimeException("Failed to start Raknetify server", t);
            } else {
                RaknetifyBungeePlugin.LOGGER.log(
                        Level.SEVERE, "Failed to start Raknetify server", t);
            }
        }
    }

    /**
     * Updates wildcard listeners after an address change, ignoring callbacks from an older plugin
     * instance.
     *
     * @param instance running proxy used to initialize newly available listeners
     * @param currentActiveIndex generation captured when this callback was registered
     * @param event local interface address addition or removal
     */
    private static void onInterfaceChange(
            final BungeeCord instance,
            final int currentActiveIndex,
            final NetworkInterfaceListener.InterfaceAddressChangeEvent event) {
        if (!active) {
            NetworkInterfaceListener.removeListener(listener);
        }

        if (currentActiveIndex != activeIndex) {
            return;
        } // we can't remove ourselves now, is plugin reloaded?

        if (event.added()) {
            for (Channel channel : channels.keySet()) {
                injectChannel(instance, channel, false);
            }
        } else {
            for (ReferenceOpenHashSet<ChannelFuture> futures : channels.values()) {
                for (ObjectIterator<ChannelFuture> iterator = futures.iterator();
                        iterator.hasNext(); ) {
                    ChannelFuture future = iterator.next();
                    if (((InetSocketAddress) future.channel().localAddress())
                            .getAddress()
                            .equals(event.address())) {
                        RaknetifyBungeePlugin.LOGGER.info(
                                "Closing Raknetify server %s"
                                        .formatted(future.channel().localAddress()));
                        future.channel().close();
                        iterator.remove();
                    }
                }
            }
        }
    }

    /**
     * Rejects inactive, non-IP, or duplicate port-override listeners before inspecting their
     * pipeline.
     *
     * @param listener proxy listener considered for a companion UDP socket
     * @return whether the listener can create a new RakNet endpoint
     */
    private static boolean isListenerCandidate(final Channel listener) {
        if (!active) {
            return false;
        }

        final boolean hasPortOverride = portOverride > 0 && portOverride < 65535;
        if (hasPortOverride && !channels.isEmpty()) {
            return false;
        } // avoid duplicate listeners

        if (!(listener.localAddress() instanceof InetSocketAddress)) {
            return false;
        }
        return true;
    }

    /**
     * Reads the proxy acceptor's child initializer and listener metadata, excluding query sockets.
     *
     * @param listener bound proxy channel whose acceptor owns the Minecraft child pipeline
     * @return recovered acceptor metadata, or null when the channel serves query traffic
     * @throws RuntimeException if the proxy's acceptor fields cannot be inspected
     */
    private static ListenerContext findListenerContext(final Channel listener) {
        ChannelInitializer<Channel> initializer = null;
        ListenerInfo info = null;

        for (String name : listener.pipeline().names()) {
            final ChannelHandler handler = listener.pipeline().get(name);
            if (handler instanceof QueryHandler) {
                return null;
            }
            if (handler != null && ACCEPTOR_CLASS.isAssignableFrom(handler.getClass())) {
                try {
                    initializer =
                            (ChannelInitializer<Channel>)
                                    accessible(ACCEPTOR_CLASS.getDeclaredField("childHandler"))
                                            .get(handler);
                    final Map.Entry<AttributeKey<?>, ?>[] attrs =
                            (Map.Entry<AttributeKey<?>, ?>[])
                                    accessible(ACCEPTOR_CLASS.getDeclaredField("childAttrs"))
                                            .get(handler);
                    for (var attr : attrs) {
                        if (attr.getKey() == PipelineUtils.LISTENER) {
                            info = (ListenerInfo) attr.getValue();
                        }
                    }
                } catch (IllegalAccessException | NoSuchFieldException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return new ListenerContext(initializer, info);
    }

    /**
     * Keeps the child initializer paired with the listener attributes from the same acceptor scan.
     *
     * @param initializer Minecraft pipeline initializer, or null when no acceptor was found
     * @param info proxy listener configuration, or null when its required attribute is absent
     */
    private record ListenerContext(ChannelInitializer<Channel> initializer, ListenerInfo info) {}

    /**
     * Expands wildcard binds into one UDP endpoint per address while preserving per-address failure
     * logging.
     *
     * @param instance running proxy supplying worker event loops
     * @param listener original TCP listener that owns the companion UDP endpoints
     * @param initializer child pipeline initializer recovered from the proxy acceptor
     * @param info listener address and proxy connection options
     * @throws java.net.SocketException if local network interfaces cannot be enumerated
     * @throws NoSuchFieldException if a required event-loop field is absent for a specific-address
     *     bind
     * @throws IllegalAccessException if that event-loop field cannot be read
     */
    private static void bindListenerAddresses(
            final BungeeCord instance,
            final Channel listener,
            final ChannelInitializer<Channel> initializer,
            final ListenerInfo info)
            throws java.net.SocketException, NoSuchFieldException, IllegalAccessException {
        if (((InetSocketAddress) info.getSocketAddress()).getAddress().isAnyLocalAddress()) {
            for (NetworkInterface networkInterface :
                    NetworkInterface.networkInterfaces().toList()) {
                final Iterator<InetAddress> iterator =
                        networkInterface.getInetAddresses().asIterator();
                while (iterator.hasNext()) {
                    final InetAddress address = iterator.next();
                    try {
                        startServer(instance, listener, initializer, info, address);
                    } catch (Throwable t) {
                        RaknetifyBungeePlugin.LOGGER.log(
                                Level.SEVERE, "Failed to start Raknetify server", t);
                    }
                }
            }
        } else {
            startServer(instance, listener, initializer, info, null);
        }
    }

    /**
     * Binds one UDP endpoint using the proxy's transport and child initializer. Existing
     * per-interface endpoints are reused by skipping duplicate addresses.
     *
     * @param instance running proxy supplying event loops
     * @param listener TCP listener that owns the new UDP endpoint
     * @param initializer original proxy child initializer reused for accepted connections
     * @param info proxy listener attributes and configured address
     * @param address interface address for wildcard expansion, or null for an explicit bind
     * @throws NoSuchFieldException if the proxy exposes neither supported event-loop field layout
     * @throws IllegalAccessException if the proxy event-loop fields cannot be read
     */
    private static void startServer(
            final BungeeCord instance,
            final Channel listener,
            final ChannelInitializer<Channel> initializer,
            final ListenerInfo info,
            final InetAddress address)
            throws NoSuchFieldException, IllegalAccessException {
        if (address != null) {
            final ReferenceOpenHashSet<ChannelFuture> futures = channels.get(listener);
            if (futures != null) {
                for (ChannelFuture future : futures) {
                    if (((InetSocketAddress) future.channel().localAddress())
                            .getAddress()
                            .equals(address)) {
                        return;
                    } // avoid duplicate
                }
            }
        }

        final boolean hasPortOverride = portOverride > 0 && portOverride < 65535;
        final ReflectiveChannelFactory<? extends DatagramChannel> factory =
                new ReflectiveChannelFactory<>(PipelineUtils.getDatagramChannel());
        final InetAddress actualAddress =
                address == null
                        ? ((InetSocketAddress) info.getSocketAddress()).getAddress()
                        : address;
        final ChannelFuture future =
                new ServerBootstrap()
                        .channelFactory(() ->
                                ProxyProtocol.create(() -> {
                                    final DatagramChannel channel = factory.newChannel();
                                    channel.config()
                                            .setOption(
                                                    ChannelOption.IP_TOS,
                                                    RakNetConnectionUtil.DEFAULT_IP_TOS);
                                    channel.config()
                                            .setRecvByteBufAllocator(
                                                    new FixedRecvByteBufAllocator(
                                                                    Constants.LARGE_MTU + 512)
                                                            .maxMessagesPerRead(128));
                                    return channel;
                                }))
                        .option(ChannelOption.SO_REUSEADDR, true)
                        .childAttr(PipelineUtils.LISTENER, info)
                        .childHandler(
                                new ChannelInitializer<>() {
                                    /**
                                     * Wraps the normal BungeeCord child initialization with RakNet
                                     * transport setup and codec replacement.
                                     *
                                     * @param channel accepted RakNet application channel
                                     * @throws Exception if invoking the proxy's initializer fails
                                     */
                                    @Override
                                    protected void initChannel(final Channel channel)
                                            throws Exception {
                                        RakNetBungeeConnectionUtil.initChannel(channel);
                                        INIT_CHANNEL.invoke(initializer, channel);
                                        RakNetBungeeConnectionUtil.postInitChannel(channel, false);
                                    }
                                })
                        .group(getBossEventLoopGroup(instance), getWorkerEventLoopGroup(instance))
                        .localAddress(
                                hasPortOverride
                                        ? new InetSocketAddress(actualAddress, portOverride)
                                        : new InetSocketAddress(
                                                actualAddress,
                                                ((InetSocketAddress) info.getSocketAddress())
                                                        .getPort()))
                        .bind()
                        .syncUninterruptibly();
        if (address == null) {
            nonWildcardChannels.add(future);
        } else {
            channels.computeIfAbsent(listener, unused -> new ReferenceOpenHashSet<>()).add(future);
        }

        RaknetifyBungeePlugin.LOGGER.info(
                "Raknetify server started on %s".formatted(future.channel().localAddress()));
    }

    /**
     * Closes all tracked UDP endpoints and clears their ownership tables while the plugin is
     * active.
     */
    public static void stopAll() {
        if (!active) {
            return;
        }
        for (ChannelFuture future :
                Stream.concat(
                                channels.values().stream().flatMap(Collection::stream),
                                nonWildcardChannels.stream())
                        .toList()) {
            RaknetifyBungeePlugin.LOGGER.info(
                    "Closing Raknetify server %s".formatted(future.channel().localAddress()));
            try {
                future.channel().close().sync();
            } catch (InterruptedException e) {
                RaknetifyBungeePlugin.LOGGER.severe("Interrupted whilst closing raknetify server");
            }
        }

        channels.clear();
        nonWildcardChannels.clear();
    }

    /** Closes active UDP endpoints and prevents subsequent listener creation. */
    public static void disable() {
        if (!active) {
            return;
        }
        stopAll();
        active = false;
    }

    /**
     * Finds the acceptor event loops across BungeeCord's shared and Waterfall's split layouts.
     *
     * @param instance running proxy supplying event loops
     * @return proxy-owned acceptor event-loop group
     * @throws NoSuchFieldException if neither supported field layout exists
     * @throws IllegalAccessException if the selected event-loop field cannot be read
     */
    private static EventLoopGroup getBossEventLoopGroup(final BungeeCord instance)
            throws NoSuchFieldException, IllegalAccessException {
        try {
            return (EventLoopGroup)
                    accessible(BungeeCord.class.getDeclaredField("eventLoops")).get(instance);
        } catch (NoSuchFieldException e) { // waterfall: use split boss and worker group
            //noinspection JavaReflectionMemberAccess
            return (EventLoopGroup)
                    accessible(BungeeCord.class.getDeclaredField("bossEventLoopGroup"))
                            .get(instance);
        }
    }

    /**
     * Finds the connection event loops across BungeeCord's shared and Waterfall's split layouts.
     *
     * @param instance running proxy supplying event loops
     * @return proxy-owned connection event-loop group
     * @throws NoSuchFieldException if neither supported field layout exists
     * @throws IllegalAccessException if the selected event-loop field cannot be read
     */
    private static EventLoopGroup getWorkerEventLoopGroup(final BungeeCord instance)
            throws NoSuchFieldException, IllegalAccessException {
        try {
            return (EventLoopGroup)
                    accessible(BungeeCord.class.getDeclaredField("eventLoops")).get(instance);
        } catch (NoSuchFieldException e) { // waterfall: use split boss and worker group
            //noinspection JavaReflectionMemberAccess
            return (EventLoopGroup)
                    accessible(BungeeCord.class.getDeclaredField("workerEventLoopGroup"))
                            .get(instance);
        }
    }
}
