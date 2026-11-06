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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import network.ycc.raknet.server.channel.RakNetServerChannel;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Keeps UDP resources separate from server-owned TCP channels and shuts them down together. */
final class SpigotListeners implements AutoCloseable {
    /** Native listener snapshot and server login key used by each accepted connection. */
    private final ServerAccess access;
    /** Datagram I/O loop owned exclusively by this plugin instance. */
    private final EventLoopGroup transport = new NioEventLoopGroup(1);
    /** Application loop keeps Minecraft packet processing off the datagram I/O loop. */
    private final EventLoopGroup application = new DefaultEventLoopGroup(1);
    /** Bound or pending sockets retained immediately so a failed bind cannot leak a channel. */
    private final List<Channel> channels = new ArrayList<>();

    /**
     * Creates isolated loop ownership after the server layout has been validated.
     * @param access tested server listener metadata
     */
    SpigotListeners(ServerAccess access) { this.access = access; }

    /**
     * Binds a companion UDP socket for every ordinary IP-based Minecraft TCP listener.
     * @throws IllegalStateException if no usable listener exists
     */
    void bind() {
        for (Channel listener : access.listeners()) {
            if (listener.localAddress() instanceof InetSocketAddress) bind(listener);
        }
        if (channels.isEmpty()) throw new IllegalStateException("No Minecraft TCP listener found");
    }

    /**
     * Copies the original initializer while retaining ownership of the new socket even if binding fails.
     * @param listener existing TCP listener supplying the bind address and child configuration
     */
    private void bind(Channel listener) {
        ChannelHandler acceptor = ServerAccess.acceptor(listener);
        var future = new ServerBootstrap().group(transport)
                .channelFactory(this::newServerChannel)
                .childHandler(new ChannelInitializer<Channel>() {
                    /**
                     * Adds framing around the server's original initializer and preserves its authentication handlers.
                     * @param channel newly accepted RakNet application channel
                     * @throws Exception if either transport or native pipeline setup fails
                     */
                    @Override
                    protected void initChannel(Channel channel) throws Exception {
                        SpigotConnection.initialize(channel);
                        ServerAccess.initialize(acceptor, channel);
                        SpigotConnection.finish(channel, access.privateKey());
                    }
                }).localAddress(listener.localAddress()).bind();
        channels.add(future.channel());
        future.syncUninterruptibly();
    }

    /**
     * Constructs an NIO datagram endpoint and assigns its separate application loop before registration.
     * @return unregistered RakNet server channel
     */
    private RakNetServerChannel newServerChannel() {
        RakNetServerChannel channel = new RakNetServerChannel(() -> {
            NioDatagramChannel datagram = new NioDatagramChannel();
            datagram.config().setOption(ChannelOption.IP_TOS, RakNetConnectionUtil.DEFAULT_IP_TOS);
            datagram.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(Constants.LARGE_MTU + 512));
            return datagram;
        });
        channel.setProvidedApplicationEventLoop(application.next());
        return channel;
    }

    /** Waits for owned channels to close before terminating their event loops and leaves TCP untouched. */
    @Override
    public void close() {
        for (Channel channel : channels) channel.close().syncUninterruptibly();
        channels.clear();
        transport.shutdownGracefully().syncUninterruptibly();
        application.shutdownGracefully().syncUninterruptibly();
    }
}
