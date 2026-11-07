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

package com.ishland.raknetify.common.voice;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;

import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Routes the reserved voice datagram prefix before RakNet decoding without loading an optional mod
 * API.
 */
public final class VoiceTransport {
    /** Only primary bound listeners are candidates, never their connected child sockets. */
    private static final Set<VoiceHub> LISTENERS = new HashSet<>();

    /** Prevents instances of the shared listener registry. */
    private VoiceTransport() {}

    /**
     * Gives one listener and its connected sockets the same bounded voice inbox.
     *
     * @param factory platform datagram factory preserving native socket options
     * @return factory that separates voice bytes before RakNet can interpret them
     */
    public static Supplier<? extends DatagramChannel> decorate(
            final Supplier<? extends DatagramChannel> factory) {
        VoiceHub hub = new VoiceHub();
        AtomicBoolean primary = new AtomicBoolean(true);
        return () -> {
            DatagramChannel socket = factory.get();
            socket.pipeline()
                    .addFirst("raknetify-voice", new Handler(hub, primary.getAndSet(false)));
            return socket;
        };
    }

    /**
     * Waits for deferred server startup without blocking any Netty event loop.
     *
     * @param port requested voice port, which must be the RakNet listener's port
     * @param address requested interface or wildcard address
     * @param cancelled whether the requesting voice socket closed during startup
     * @return unique listener serving that endpoint
     * @throws SocketException if no matching listener starts or the bind request is ambiguous
     * @throws InterruptedException if shutdown interrupts the voice startup thread
     */
    static synchronized VoiceHub await(
            final int port, final InetAddress address, final BooleanSupplier cancelled)
            throws SocketException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new SocketException("Voice socket closed during startup");
            }
            VoiceHub match = find(port, address);
            if (match != null) {
                return match;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new SocketException(
                        "No RakNet listener matches the voice bind address and port " + port);
            }
            TimeUnit.NANOSECONDS.timedWait(VoiceTransport.class, remaining);
        }
    }

    /** Wakes deferred startup so a closed voice socket does not wait for the listener timeout. */
    static synchronized void wakeWaiters() {
        VoiceTransport.class.notifyAll();
    }

    /**
     * Rejects ambiguity rather than choosing another server's listener in the same JVM.
     *
     * @param port requested local UDP port
     * @param address requested interface
     * @return sole matching hub or null while startup is pending
     * @throws SocketException if multiple interfaces match a wildcard request
     */
    private static @Nullable VoiceHub find(final int port, final InetAddress address)
            throws SocketException {
        VoiceHub result = null;
        for (VoiceHub hub : LISTENERS) {
            InetSocketAddress local = (InetSocketAddress) hub.channel().localAddress();
            if (local == null || local.getPort() != port || !matches(address, local.getAddress())) {
                continue;
            }
            if (result != null) {
                throw new SocketException("Multiple RakNet listeners match the voice bind address");
            }
            result = hub;
        }
        return result;
    }

    /**
     * Prevents a voice bind to one interface from silently exposing it on every interface.
     *
     * @param requested voice configuration bind address
     * @param actual existing RakNet bind address
     * @return whether using this socket preserves the requested bind scope
     */
    private static boolean matches(final InetAddress requested, final InetAddress actual) {
        return requested.isAnyLocalAddress() || requested.equals(actual);
    }

    /**
     * Publishes a successfully bound primary socket and wakes deferred voice startup.
     *
     * @param hub listener whose bind has completed
     */
    private static synchronized void register(final VoiceHub hub) {
        LISTENERS.add(hub);
        VoiceTransport.class.notifyAll();
    }

    /**
     * Removes closed listeners before another server can reuse their UDP port.
     *
     * @param hub closing listener
     */
    private static synchronized void unregister(final VoiceHub hub) {
        LISTENERS.remove(hub);
        hub.shutdown();
    }

    /** Separates protocols on both the initial socket and RakNet's connected child sockets. */
    private static final class Handler extends SimpleChannelInboundHandler<DatagramPacket> {
        /** Shared inbox owned by the primary listener. */
        private final VoiceHub hub;

        /** Only the initial socket controls registry membership and shutdown. */
        private final boolean primary;

        /**
         * Records listener ownership before registration and binding.
         *
         * @param hub listener-local voice dispatcher
         * @param primary whether this is the unconnected listening socket
         */
        private Handler(final VoiceHub hub, final boolean primary) {
            this.hub = hub;
            this.primary = primary;
        }

        /**
         * Makes the actual bound port available without guessing ephemeral port assignments.
         *
         * @param context physical datagram pipeline
         */
        @Override
        public void channelActive(final ChannelHandlerContext context) {
            if (primary) {
                hub.start((DatagramChannel) context.channel());
                register(hub);
            }
            context.fireChannelActive();
        }

        /**
         * Stops voice reads only when the primary listener closes, not when one player disconnects.
         *
         * @param context closing datagram pipeline
         */
        @Override
        public void channelInactive(final ChannelHandlerContext context) {
            if (primary) {
                unregister(hub);
            }
            context.fireChannelInactive();
        }

        /**
         * Copies bounded voice datagrams and leaves every other packet's bytes and ownership
         * unchanged.
         *
         * @param context physical datagram pipeline after any required proxy validation
         * @param packet owned incoming envelope released automatically after dispatch
         */
        @Override
        protected void channelRead0(
                final ChannelHandlerContext context, final DatagramPacket packet) {
            var bytes = packet.content();
            if (bytes.isReadable() && bytes.getUnsignedByte(bytes.readerIndex()) == 0xff) {
                hub.receive(packet);
            } else {
                context.fireChannelRead(packet.retain());
            }
        }
    }
}
