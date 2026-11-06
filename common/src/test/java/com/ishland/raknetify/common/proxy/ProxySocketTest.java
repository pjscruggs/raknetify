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

import static org.junit.jupiter.api.Assertions.*;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

import network.ycc.raknet.client.RakNetClient;
import network.ycc.raknet.pipeline.UserDataCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Real UDP forwarding checks exercise connected child sockets and native application address
 * visibility.
 */
@Timeout(30)
class ProxySocketTest {
    /**
     * A proxy that sends metadata only once must continue working after RakNet opens its connected
     * socket.
     *
     * @throws Exception if socket setup, delivery, identity verification or bounded cleanup fails
     */
    @Test
    void firstPacketHeaderSurvivesConnectedSocketTransition() throws Exception {
        roundTrip(false, ProxyProtocolTest.CLIENT);
    }

    /**
     * Per-datagram IPv6 identities must be stripped on connected sockets as well as the initial
     * listener.
     *
     * @throws Exception if socket setup, delivery, identity verification or bounded cleanup fails
     */
    @Test
    void repeatedIpv6HeadersSurviveConnectedSocketTransition() throws Exception {
        roundTrip(true, new InetSocketAddress("2001:db8::50", 12345));
    }

    /**
     * Connects through a real loopback relay and exchanges application frames after handshake
     * completion.
     *
     * @param repeated whether every upstream datagram has a PROXY header
     * @param claimed synthetic identity that cannot receive actual socket replies
     * @throws Exception if the peer address, echoed payload or cleanup differs from expectations
     */
    private static void roundTrip(final boolean repeated, final InetSocketAddress claimed)
            throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(2);
        java.util.concurrent.BlockingQueue<SocketAddress> observed = new LinkedBlockingQueue<>();
        java.util.concurrent.BlockingQueue<Integer> replies = new LinkedBlockingQueue<>();
        Channel server = null;
        Channel relay = null;
        Channel client = null;
        try {
            server =
                    new ServerBootstrap()
                            .group(group)
                            .channelFactory(() ->
                                    ProxyProtocol.create(
                                            NioDatagramChannel::new, ProxyProtocolTest.trusted()))
                            .childHandler(serverInitializer(observed))
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            Relay handler = new Relay((InetSocketAddress) server.localAddress(), claimed, repeated);
            relay =
                    new Bootstrap()
                            .group(group)
                            .channel(NioDatagramChannel.class)
                            .handler(handler)
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            client =
                    new Bootstrap()
                            .group(group)
                            .channel(RakNetClient.THREADED_CHANNEL)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                            .handler(clientInitializer(replies))
                            .connect(relay.localAddress())
                            .sync()
                            .channel();
            for (int value = 1; value <= 3; value++) {
                client.writeAndFlush(Unpooled.buffer(4).writeInt(value)).sync();
                assertEquals(value, replies.poll(5, TimeUnit.SECONDS));
            }
            assertEquals(claimed, observed.poll(5, TimeUnit.SECONDS));
            assertTrue(
                    handler.upstreamPackets > 3,
                    "The relay must carry handshake and connected-socket traffic");
        } finally {
            close(client);
            close(relay);
            close(server);
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Captures the native-visible identity and echoes frames through the transport's actual return
     * path.
     *
     * @param observed application-level addresses seen after the RakNet handshake
     * @return fresh per-child application initializer
     */
    private static ChannelInitializer<Channel> serverInitializer(
            final java.util.concurrent.BlockingQueue<SocketAddress> observed) {
        return new ChannelInitializer<>() {
            /**
             * Installs the ordinary RakNet user-data codec and a bounded echo witness.
             *
             * @param channel accepted application channel
             */
            @Override
            protected void initChannel(final Channel channel) {
                ProxyProtocol.requireForwarded(channel);
                observed.add(channel.remoteAddress());
                channel.pipeline().addLast(new UserDataCodec(0xfe), new Echo());
            }
        };
    }

    /**
     * Collects real returned frames without depending on server internals for the result.
     *
     * @param replies client-observed echo payloads
     * @return client application initializer
     */
    private static ChannelInitializer<Channel> clientInitializer(
            final java.util.concurrent.BlockingQueue<Integer> replies) {
        return new ChannelInitializer<>() {
            /**
             * Decodes server frames before recording their exact integer payload.
             *
             * @param channel connected client application channel
             */
            @Override
            protected void initChannel(final Channel channel) {
                channel.pipeline()
                        .addLast(
                                new UserDataCodec(0xfe),
                                new SimpleChannelInboundHandler<ByteBuf>() {
                                    /**
                                     * Captures the echoed value and lets Netty release the received
                                     * frame.
                                     *
                                     * @param context client pipeline
                                     * @param bytes decoded application frame
                                     */
                                    @Override
                                    protected void channelRead0(
                                            final ChannelHandlerContext context,
                                            final ByteBuf bytes) {
                                        replies.add(bytes.readInt());
                                    }
                                });
            }
        };
    }

    /**
     * Closes an owned socket even when setup failed before all fixture endpoints were created.
     *
     * @param channel owned endpoint, or null before its bind/connect succeeded
     */
    private static void close(final Channel channel) {
        if (channel != null) {
            channel.close().awaitUninterruptibly(5000);
        }
    }

    /** Echoes received application frames without retaining extra ownership after the write. */
    private static final class Echo extends SimpleChannelInboundHandler<ByteBuf> {
        /**
         * Sends the original bytes back through the physical proxy rather than the asserted client
         * IP.
         *
         * @param context server application pipeline
         * @param bytes application frame released by this handler after its retained write
         */
        @Override
        protected void channelRead0(final ChannelHandlerContext context, final ByteBuf bytes) {
            context.writeAndFlush(bytes.retain());
        }
    }

    /**
     * One-client UDP relay with an independently encoded PROXY v2 header and an unmodified return
     * path.
     */
    private static final class Relay extends SimpleChannelInboundHandler<DatagramPacket> {
        /** Backend RakNet listener. */
        private final InetSocketAddress server;

        /** Original client address asserted by the fixture proxy. */
        private final InetSocketAddress claimed;

        /** Selects first-only versus per-datagram forwarding metadata. */
        private final boolean repeated;

        /** Physical client discovered from the first incoming datagram. */
        private InetSocketAddress client;

        /** Counts forwarded packets across event-loop and test threads. */
        private volatile int upstreamPackets;

        /**
         * Records one backend and forwarding mode before the relay binds.
         *
         * @param server real backend listener
         * @param claimed synthetic forwarded identity
         * @param repeated whether to repeat the header on every packet
         */
        private Relay(
                final InetSocketAddress server,
                final InetSocketAddress claimed,
                final boolean repeated) {
            this.server = server;
            this.claimed = claimed;
            this.repeated = repeated;
        }

        /**
         * Prepends metadata only upstream and routes unchanged responses to the actual client
         * socket.
         *
         * @param context relay's UDP pipeline
         * @param packet complete physical datagram
         */
        @Override
        protected void channelRead0(
                final ChannelHandlerContext context, final DatagramPacket packet) {
            if (packet.sender().equals(server)) {
                context.writeAndFlush(new DatagramPacket(packet.content().retain(), client));
                return;
            }
            client = packet.sender();
            ByteBuf payload =
                    repeated || upstreamPackets == 0
                            ? ProxyProtocolTest.header(claimed).writeBytes(packet.content())
                            : packet.content().retain();
            upstreamPackets++;
            context.writeAndFlush(new DatagramPacket(payload, server));
        }
    }
}
