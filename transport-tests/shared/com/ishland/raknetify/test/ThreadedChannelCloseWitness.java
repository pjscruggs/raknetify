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

package com.ishland.raknetify.test;

import com.ishland.raknetify.common.connection.GracefulCloseHandler;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;

import network.ycc.raknet.client.RakNetClient;

import java.util.concurrent.TimeUnit;

/**
 * Exercises physical socket closure against the Netty implementation supplied by the running
 * platform.
 */
public final class ThreadedChannelCloseWitness {
    /** Prevents constructing the shared socket lifecycle witness. */
    private ThreadedChannelCloseWitness() {}

    /**
     * A lower transport can close before an application drain has requested its own close.
     *
     * @throws InterruptedException if socket registration or bounded cleanup fails
     * @throws IllegalStateException if the application channel remains open after its physical
     *     socket closes
     */
    public static void verify() throws InterruptedException {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel client = null;
        try {
            client =
                    new Bootstrap()
                            .group(group)
                            .channel(RakNetClient.THREADED_CHANNEL)
                            .handler(initializer())
                            .bind("127.0.0.1", 0)
                            .sync()
                            .channel();
            client.parent().close().sync();
            if (!client.closeFuture().await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Physical close stranded the application channel");
            }
        } finally {
            if (client != null) {
                client.close().awaitUninterruptibly(3000);
            }
            group.shutdownGracefully(0, 3, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Installs the production drain handler before the physical socket is registered.
     *
     * @return independently owned application initializer
     */
    private static ChannelInitializer<Channel> initializer() {
        return new com.ishland.raknetify.test.ChannelSetup(channel -> {
            channel.pipeline().addLast(new GracefulCloseHandler());
        });
    }
}
