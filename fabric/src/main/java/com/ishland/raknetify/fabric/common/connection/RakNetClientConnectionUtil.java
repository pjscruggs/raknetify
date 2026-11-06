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

package com.ishland.raknetify.fabric.common.connection;

import com.ishland.raknetify.common.util.ThreadLocalUtil;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import io.netty.channel.ChannelFuture;

import net.minecraft.network.ClientConnection;

import java.net.InetSocketAddress;

/** Scopes RakNet bootstrap flags around the version-specific Minecraft connection factory. */
public class RakNetClientConnectionUtil {

    /** Prevents construction of the client connection-factory adapter. */
    private RakNetClientConnectionUtil() {}

    /**
     * Enables RakNet bootstrap selection for the wrapped connection call and clears both flags
     * afterward.
     *
     * @param address resolved remote server address
     * @param useEpoll vanilla preference for the native Linux transport
     * @param largeMTU whether the server address requested the larger RakNet MTU
     * @param original intercepted Minecraft factory operation
     * @param hasPerformanceLog whether this version's factory accepts the additional
     *     performance-log argument
     * @return Minecraft connection produced by the original factory
     */
    public static ClientConnection connect(
            final InetSocketAddress address,
            final boolean useEpoll,
            final boolean largeMTU,
            final Operation<ClientConnection> original,
            final boolean hasPerformanceLog) {
        System.out.println("aaa");
        try {
            ThreadLocalUtil.setInitializingRaknet(true);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(largeMTU);
            if (hasPerformanceLog) {
                return original.call(address, useEpoll, null);
            } else {
                return original.call(address, useEpoll);
            }
        } finally {
            ThreadLocalUtil.setInitializingRaknet(false);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(false);
        }
    }

    /**
     * Enables RakNet bootstrap selection for the wrapped connection call and clears both flags
     * afterward.
     *
     * @param address resolved remote server address
     * @param backend vanilla event-loop backend selection passed through to the original factory
     * @param largeMTU whether the server address requested the larger RakNet MTU
     * @param original intercepted Minecraft factory operation
     * @return Minecraft connection produced by the original factory
     */
    public static ClientConnection connect(
            final InetSocketAddress address,
            final Object backend,
            final boolean largeMTU,
            final Operation<ClientConnection> original) {
        System.out.println("aaa");
        try {
            ThreadLocalUtil.setInitializingRaknet(true);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(largeMTU);
            return original.call(address, backend, null);
        } finally {
            ThreadLocalUtil.setInitializingRaknet(false);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(false);
        }
    }

    /**
     * Enables RakNet bootstrap selection for the wrapped connection call and clears both flags
     * afterward.
     *
     * @param address resolved remote server address
     * @param useEpoll vanilla preference for the native Linux transport
     * @param largeMTU whether the server address requested the larger RakNet MTU
     * @param original intercepted Minecraft factory operation
     * @param connection existing Minecraft connection to bind to the new channel
     * @return channel future produced by the original factory
     */
    public static ChannelFuture connect(
            final InetSocketAddress address,
            final boolean useEpoll,
            final boolean largeMTU,
            final Operation<ChannelFuture> original,
            final ClientConnection connection) {
        System.out.println("aaaa");
        try {
            ThreadLocalUtil.setInitializingRaknet(true);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(largeMTU);
            return original.call(address, useEpoll, connection);
        } finally {
            ThreadLocalUtil.setInitializingRaknet(false);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(false);
        }
    }

    /**
     * Enables RakNet bootstrap selection for the wrapped connection call and clears both flags
     * afterward.
     *
     * @param address resolved remote server address
     * @param backend vanilla event-loop backend selection passed through to the original factory
     * @param largeMTU whether the server address requested the larger RakNet MTU
     * @param original intercepted Minecraft factory operation
     * @param connection existing Minecraft connection to bind to the new channel
     * @return channel future produced by the original factory
     */
    public static ChannelFuture connect(
            final InetSocketAddress address,
            final Object backend,
            final boolean largeMTU,
            final Operation<ChannelFuture> original,
            final ClientConnection connection) {
        System.out.println("aaaa");
        try {
            ThreadLocalUtil.setInitializingRaknet(true);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(largeMTU);
            return original.call(address, backend, connection);
        } finally {
            ThreadLocalUtil.setInitializingRaknet(false);
            ThreadLocalUtil.setInitializingRaknetLargeMTU(false);
        }
    }
}
