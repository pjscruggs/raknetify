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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real loopback sockets to verify listener ownership after normal and failed plugin startup. */
class SpigotListenersTest {
    /**
     * Plugin disable releases UDP for a subsequent listener while retaining the native TCP channel.
     * @throws Exception if the fixture cannot bind its disposable loopback sockets
     */
    @Test
    void disableReleasesUdpAndPreservesTcp() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ChannelFuture tcp = tcpListener(group);
        SpigotListeners listeners = new SpigotListeners(new ServerAccess(new CraftFacade(tcp)));
        try {
            listeners.bind();
            assertTrue(tcp.channel().isActive());
            listeners.close();
            assertTrue(tcp.channel().isActive());
            try (DatagramSocket replacement = new DatagramSocket(null)) {
                replacement.bind(tcp.channel().localAddress());
                assertEquals(((InetSocketAddress) tcp.channel().localAddress()).getPort(), replacement.getLocalPort());
            }
        } finally {
            listeners.close();
            tcp.channel().close().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * A conflicting UDP service stays bound and native TCP remains usable after partial startup cleanup.
     * @throws Exception if the fixture cannot bind its disposable loopback sockets
     */
    @Test
    void bindFailureDoesNotCloseAnotherServicesSocket() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        ChannelFuture tcp = tcpListener(group);
        SpigotListeners listeners = new SpigotListeners(new ServerAccess(new CraftFacade(tcp)));
        try (DatagramSocket occupied = new DatagramSocket(null)) {
            occupied.bind(tcp.channel().localAddress());
            assertThrows(Exception.class, listeners::bind);
            listeners.close();
            assertTrue(tcp.channel().isActive());
            assertFalse(occupied.isClosed());
        } finally {
            listeners.close();
            tcp.channel().close().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Creates the same Netty acceptor structure inspected by production listener discovery.
     * @param group fixture-owned I/O loop
     * @return completed bind future retaining the native TCP channel
     * @throws InterruptedException if socket registration is interrupted
     */
    private static ChannelFuture tcpListener(NioEventLoopGroup group) throws InterruptedException {
        return new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    /**
                     * Closes incidental test connections without creating a Minecraft login session.
                     * @param channel accepted fixture TCP child
                     */
                    @Override
                    protected void initChannel(Channel channel) { channel.close(); }
                }).bind("127.0.0.1", 0).sync();
    }

    /** Minimal CraftServer accessor contract around a fixture-owned native listener. */
    public static final class CraftFacade {
        /** Native facade retaining the fixture's actual TCP channel. */
        private final NativeFacade nativeServer;
        /**
         * Wraps the native listener metadata used for this test only.
         * @param listener successfully bound TCP listener
         * @throws Exception if the synthetic login key cannot be generated
         */
        CraftFacade(ChannelFuture listener) throws Exception { nativeServer = new NativeFacade(listener); }
        /**
         * Exposes the same public native-server accessor used on Spigot and Paper.
         * @return fixture server with no players or worlds
         */
        public NativeFacade getServer() { return nativeServer; }
    }

    /** Supplies only the verified server metadata needed before opening a companion UDP listener. */
    public static final class NativeFacade {
        /** Immutable fixture listener inventory. */
        private final ListenerFacade connection;
        /** Synthetic RSA key unrelated to any account or production server. */
        private final KeyPair key;
        /**
         * Builds a server metadata facade around one real loopback listener.
         * @param listener successfully bound fixture TCP listener
         * @throws Exception if RSA key generation is unavailable
         */
        NativeFacade(ChannelFuture listener) throws Exception {
            connection = new ListenerFacade(listener);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            key = generator.generateKeyPair();
        }
        /**
         * Identifies the tested native server version independently of Bukkit API version formatting.
         * @return supported Minecraft release
         */
        public String getServerVersion() { return "26.3"; }
        /**
         * Supplies the same key ownership contract as the actual Minecraft server.
         * @return synthetic login key
         */
        public KeyPair getKeyPair() { return key; }
        /**
         * Returns the object owning native listener futures.
         * @return fixture listener inventory
         */
        public ListenerFacade getConnection() { return connection; }
    }

    /** Matches the native listener field layout while retaining real Netty channels. */
    public static final class ListenerFacade {
        /** Native TCP futures that must never be closed by UDP cleanup. */
        private final List<ChannelFuture> channels;
        /**
         * Keeps the bound listener available for reflective discovery.
         * @param listener fixture TCP bind future
         */
        ListenerFacade(ChannelFuture listener) { channels = List.of(listener); }
    }
}
