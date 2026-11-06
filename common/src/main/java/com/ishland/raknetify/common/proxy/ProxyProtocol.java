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

package com.ishland.raknetify.common.proxy;

import com.ishland.raknetify.common.voice.VoiceTransport;

import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.AttributeKey;

import network.ycc.raknet.server.channel.RakNetChildChannel;
import network.ycc.raknet.server.channel.RakNetServerChannel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared PROXY v2 entry point for Fabric, native mod loaders and server plugins. */
public final class ProxyProtocol {
    /**
     * Marks only application channels whose identity came through the validated UDP trust boundary.
     */
    private static final AttributeKey<Boolean> FORWARDED =
            AttributeKey.valueOf("raknetify2:proxy-v2-validated");

    /** Prevents instances of the listener construction utility. */
    private ProxyProtocol() {}

    /**
     * Selects direct or required-PROXY transport before the new listener binds.
     *
     * @param sockets factory preserving the platform's NIO or native transport and socket options
     * @return unregistered RakNet listener
     * @throws UncheckedIOException if the operator's configuration cannot be read
     */
    public static RakNetServerChannel create(final Supplier<? extends DatagramChannel> sockets) {
        try {
            return create(
                    sockets,
                    ProxyProtocolConfig.load(Path.of("config", "raknetify2-proxy-protocol.json")));
        } catch (IOException invalid) {
            throw new UncheckedIOException("Cannot load Raknetify2 PROXY protocol policy", invalid);
        }
    }

    /**
     * Constructs an isolated listener with an already validated policy, also used by socket
     * regression tests.
     *
     * @param sockets platform datagram factory
     * @param config immutable trust policy
     * @return direct listener or a listener that requires validated proxy identities
     */
    static RakNetServerChannel create(
            final Supplier<? extends DatagramChannel> sockets, final ProxyProtocolConfig config) {
        Supplier<? extends DatagramChannel> decorated = VoiceTransport.decorate(sockets);
        if (!config.enabled()) {
            return new RakNetServerChannel(decorated);
        }
        return new ProxiedServer(decorated, config, new ProxySessions(System::nanoTime));
    }

    /**
     * Verifies that removing a platform's TCP-only HAProxy decoder cannot bypass its address
     * requirement.
     *
     * @param channel application channel initialized by the native server or proxy
     * @throws IllegalStateException if a required proxy identity has not been validated
     */
    public static void requireForwarded(final Channel channel) {
        if (!Boolean.TRUE.equals(channel.attr(FORWARDED).get())) {
            throw new IllegalStateException(
                    "Configure trustedProxies in config/raknetify2-proxy-protocol.json before using"
                            + " required PROXY headers with RakNet");
        }
    }

    /** Retains physical routing while exposing the validated address to application channels. */
    private static final class ProxiedServer extends RakNetServerChannel {
        /** Shared identity table used by the original listener and all its connected sockets. */
        private final ProxySessions sessions;

        /**
         * Decorates every physical socket, including sockets created after the handshake completes.
         *
         * @param sockets original transport factory
         * @param config trusted proxy address ranges
         * @param sessions listener-local identity state
         */
        private ProxiedServer(
                final Supplier<? extends DatagramChannel> sockets,
                final ProxyProtocolConfig config,
                final ProxySessions sessions) {
            super(() -> {
                DatagramChannel socket = sockets.get();
                socket.pipeline()
                        .addFirst("raknetify-proxy-v2", new ProxyDatagramHandler(config, sessions));
                return socket;
            });
            this.sessions = sessions;
        }

        /**
         * Pins the decoded identity before registration and releases it when the transport closes.
         *
         * @param remoteAddress actual proxy endpoint used for replies and connected UDP filtering
         * @param localAddress backend listener address
         * @param registerChannel native application registration callback
         * @return child whose application-visible remote address is the original client
         */
        @Override
        protected RakNetChildChannel newChild(
                final InetSocketAddress remoteAddress,
                final InetSocketAddress localAddress,
                final Consumer<Channel> registerChannel) {
            ProxySessions.Session session = sessions.claim(remoteAddress);
            RakNetChildChannel child =
                    new ProxiedChild(
                            ioChannelSupplier,
                            this,
                            remoteAddress,
                            localAddress,
                            registerChannel,
                            session.source);
            child.getApplicationChannel().attr(FORWARDED).set(true);
            child.closeFuture().addListener(ignored -> sessions.release(remoteAddress, session));
            return child;
        }
    }

    /**
     * The inherited physical endpoint still drives datagram writes, binds and connected socket
     * filtering.
     */
    private static final class ProxiedChild extends RakNetChildChannel {
        /** Stable address exposed to native login, IP bans, forwarding and plugin APIs. */
        private final InetSocketAddress client;

        /**
         * Preserves the transport's physical peer while recording the separately validated client
         * endpoint.
         *
         * @param sockets decorated physical socket factory
         * @param parent owning RakNet listener
         * @param proxy physical proxy endpoint
         * @param local backend bind address
         * @param registration application registration callback
         * @param client validated original client endpoint
         */
        private ProxiedChild(
                final Supplier<? extends DatagramChannel> sockets,
                final Channel parent,
                final InetSocketAddress proxy,
                final InetSocketAddress local,
                final Consumer<Channel> registration,
                final InetSocketAddress client) {
            super(sockets, parent, proxy, local, registration);
            this.client = client;
        }

        /**
         * Exposes the client identity without changing the inherited physical socket destination.
         *
         * @return original client endpoint asserted by the trusted proxy
         */
        @Override
        protected SocketAddress remoteAddress0() {
            return client;
        }
    }
}
