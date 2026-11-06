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

package com.ishland.raknetify.fabric.mixin.common;

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.connection.RaknetifyEventLoops;
import com.ishland.raknetify.common.util.ThreadLocalUtil;
import com.ishland.raknetify.fabric.common.connection.RakNetFabricConnectionUtil;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;

import net.minecraft.network.ClientConnection;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;

import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.InetSocketAddress;

/**
 * Substitutes a threaded RakNet channel during Minecraft bootstrap when transport flags request
 * UDP.
 */
@Mixin(ClientConnection.class)
public class MixinCCConnect {

    /**
     * Separates RakNet application work from the vanilla transport event loops while retaining the
     * latter for UDP I/O.
     *
     * @param instance client bootstrap being configured
     * @param eventLoopGroup vanilla transport event loops to retain for the parent UDP channel
     * @param original original bootstrap group call
     * @param address remote address captured from the connection call, unused by group selection
     * @param raknetify$eventLoop shared local carrying the original transport group to channel
     *     creation
     * @return bootstrap configured with application loops for RakNet or the original group for TCP
     */
    // Intermediary method IDs remain stable when Minecraft changes the backend parameter type.
    @Dynamic("method_10753, method_52271 for compat")
    @WrapOperation(
            remap = false,
            method = {
                "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
                "method_52271",
                "method_10753"
            },
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lio/netty/bootstrap/Bootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/AbstractBootstrap;",
                            remap = false),
            require = 1)
    private static AbstractBootstrap<Bootstrap, Channel> redirectGroup(
            final Bootstrap instance,
            final EventLoopGroup eventLoopGroup,
            final Operation<AbstractBootstrap<Bootstrap, Channel>> original,
            final InetSocketAddress address,
            @Share("raknetify$eventLoop") final LocalRef<EventLoopGroup> raknetify$eventLoop) {
        raknetify$eventLoop.set(eventLoopGroup);
        return ThreadLocalUtil.isInitializingRaknet()
                ? original.call(instance, RaknetifyEventLoops.DEFAULT_CLIENT_EVENT_LOOP_GROUP.get())
                : original.call(instance, eventLoopGroup);
    }

    /**
     * Creates a RakNet channel with a UDP backend matching vanilla's chosen socket implementation.
     * The selected MTU controls receive allocation and transport negotiation. TCP bootstrap remains
     * unchanged.
     *
     * @param instance client bootstrap being configured
     * @param aClass vanilla socket class used to choose the corresponding UDP backend
     * @param original original bootstrap channel-class call
     * @param address remote address captured from the connection call, unused by channel selection
     * @param raknetify$eventLoop shared local containing the retained vanilla transport event loops
     * @return bootstrap using the RakNet factory or the original TCP channel class
     */
    // Intermediary method IDs remain stable when Minecraft changes the backend parameter type.
    @Dynamic("method_10753, method_52271 for compat")
    @WrapOperation(
            remap = false,
            method = {
                "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
                "method_52271",
                "method_10753"
            },
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;",
                            remap = false),
            require = 1)
    private static AbstractBootstrap<Bootstrap, Channel> redirectChannel(
            final Bootstrap instance,
            final Class<? extends SocketChannel> aClass,
            final Operation<AbstractBootstrap<Bootstrap, Channel>> original,
            final InetSocketAddress address,
            @Share("raknetify$eventLoop") final LocalRef<EventLoopGroup> raknetify$eventLoop) {
        EventLoopGroup eventLoopGroup = raknetify$eventLoop.get();
        return ThreadLocalUtil.isInitializingRaknet()
                ? instance.channelFactory(() -> {
                    final boolean initializingRaknetLargeMTU =
                            ThreadLocalUtil.isInitializingRaknetLargeMTU();
                    final RakNetClientThreadedChannel channel =
                            new RakNetClientThreadedChannel(() -> {
                                final DatagramChannel channel1 =
                                        RakNetFabricConnectionUtil.fromSocketChannel(aClass);
                                channel1.config()
                                        .setOption(
                                                ChannelOption.IP_TOS,
                                                RakNetConnectionUtil.DEFAULT_IP_TOS);
                                if (initializingRaknetLargeMTU) {
                                    channel1.config()
                                            .setRecvByteBufAllocator(
                                                    new FixedRecvByteBufAllocator(
                                                                    Constants.LARGE_MTU + 512)
                                                            .maxMessagesPerRead(128));
                                } else {
                                    channel1.config()
                                            .setRecvByteBufAllocator(
                                                    new FixedRecvByteBufAllocator(
                                                                    Constants.DEFAULT_MTU + 512)
                                                            .maxMessagesPerRead(128));
                                }
                                return channel1;
                            });
                    RakNet.config(channel)
                            .setMTU(
                                    initializingRaknetLargeMTU
                                            ? Constants.LARGE_MTU
                                            : Constants.DEFAULT_MTU);
                    channel.setProvidedParentEventLoop(eventLoopGroup.next());
                    return channel;
                })
                : original.call(instance, aClass);
    }
}
