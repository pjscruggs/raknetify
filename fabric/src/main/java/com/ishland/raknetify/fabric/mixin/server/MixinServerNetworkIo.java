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

package com.ishland.raknetify.fabric.mixin.server;

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.proxy.ProxyProtocol;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.connection.RaknetifyEventLoops;
import com.ishland.raknetify.common.util.ThreadLocalUtil;
import com.ishland.raknetify.common.util.NetworkInterfaceListener;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerNetworkIo;
import network.ycc.raknet.server.channel.RakNetServerChannel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Creates UDP companions to vanilla server listeners using a guarded recursive bootstrap call.
 * Wildcard listeners expand to local interface addresses and follow subsequent address changes.
 */
@Mixin(ServerNetworkIo.class)
public abstract class MixinServerNetworkIo {

    /**
     * Optional UDP-only listener port read from raknetify.fabric.portOverride, with invalid values ignored.
     */
    @Unique
    private static final int raknetify$portOverride = Integer.getInteger("raknetify.fabric.portOverride", -1);

    /**
     * Vanilla server supplying native-transport preference and interface-change scheduling.
     */
    @Shadow
    @Final
    private MinecraftServer server;

    /**
     * Invokes vanilla listener creation, with transport selection controlled by the thread-local bootstrap flag.
     *
     * @param address local interface address, or null for a wildcard listener
     * @param port local port to bind
     * @throws IOException if the listener cannot be created
     */
    @Shadow
    public abstract void bind(@Nullable InetAddress address, int port) throws IOException;

    /**
     * Vanilla listener lifecycle flag checked before reacting to an interface change.
     */
    @Shadow
    public volatile boolean active;

    /**
     * Vanilla list of bound channels, synchronized while removing listeners for a departed address.
     */
    @Shadow
    @Final
    private List<ChannelFuture> channels;
    /**
     * Per-server interface-change callback, created once for wildcard binding.
     */
    @Unique
    private Consumer<NetworkInterfaceListener.InterfaceAddressChangeEvent> raknetify$eventListener = null;

    /**
     * Creates the requested UDP endpoints before the original TCP bind continues.
     * The thread-local guard makes recursive calls select RakNet without creating another companion listener.
     *
     * @param address requested local address, or null to enumerate every interface
     * @param port vanilla Minecraft port, used unless a valid UDP override is configured
     * @param ci injection callback, left uncancelled so the original TCP bind continues
     * @throws IOException if interface enumeration or an explicit-address UDP bind fails
     */
    @Inject(method = "bind", at = @At("HEAD"))
    private void bindUdp(InetAddress address, int port, CallbackInfo ci) throws IOException {
        if (!ThreadLocalUtil.isInitializingRaknet()) {
            try {
                ThreadLocalUtil.setInitializingRaknet(true);
                final boolean hasPortOverride = raknetify$portOverride > 0 && raknetify$portOverride < 65535;
                final int udpPort = hasPortOverride ? raknetify$portOverride : port;
                if (address == null) {
                    for (NetworkInterface networkInterface : NetworkInterface.networkInterfaces().toList()) {
                        final Iterator<InetAddress> iterator = networkInterface.getInetAddresses().asIterator();
                        while (iterator.hasNext()) {
                            final InetAddress inetAddress = iterator.next();
                            System.out.println("Starting raknetify server on %s".formatted(inetAddress));
                            try {
                                bind(inetAddress, udpPort);
                            } catch (IOException t) {
                                System.out.println("**** FAILED TO BIND TO PORT! %s".formatted(t.getMessage()));
                            }
                        }
                    }

                    if (this.raknetify$eventListener == null) {
                        this.raknetify$eventListener = event -> {
                            raknetify$onInterfaceChange(event, udpPort);
                        };
                        NetworkInterfaceListener.addListener(event -> this.server.submit(() -> raknetify$eventListener.accept(event)));
                    }
                } else {
                    System.out.println("Starting raknetify server on %s".formatted(address));
                    bind(address, udpPort);
                }
            } finally {
                ThreadLocalUtil.setInitializingRaknet(false);
            }
        }
    }


    /**
     * Rebinds UDP listeners after an interface change while retaining the recursive bind guard.
     *
     * @param event address added to or removed from a local network interface
     * @param port effective UDP port captured when the listener was first registered
     */
    @Unique
    private void raknetify$onInterfaceChange(NetworkInterfaceListener.InterfaceAddressChangeEvent event, int port) {
        if (!this.active) {
            NetworkInterfaceListener.removeListener(this.raknetify$eventListener);
            return;
        }
        try {
            ThreadLocalUtil.setInitializingRaknet(true);
            final InetAddress inetAddress = event.address();
            if (event.added()) {
                System.out.println("Starting raknetify server on %s".formatted(inetAddress));
                try {
                    bind(inetAddress, port);
                } catch (IOException t) {
                    System.out.println("**** FAILED TO BIND TO PORT! %s".formatted(t.getMessage()));
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } else {
                synchronized (this.channels) {
                    for (Iterator<ChannelFuture> iter = this.channels.iterator(); iter.hasNext(); ) {
                        ChannelFuture channel = iter.next();
                        final SocketAddress socketAddress = channel.channel().localAddress();
                        if (socketAddress instanceof InetSocketAddress channelAddress) {
                            if (inetAddress.equals(channelAddress.getAddress())) {
                                System.out.println("Stopping raknetify server on %s".formatted(inetAddress));
                                channel.channel().close();
                                iter.remove();
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            ThreadLocalUtil.setInitializingRaknet(false);
        }
    }

    /**
     * Selects shared RakNet transport event loops during a guarded UDP bind.
     *
     * @param instance vanilla server bootstrap being configured
     * @param group original vanilla event-loop group
     * @param original intercepted bootstrap group call
     * @return bootstrap configured for RakNet NIO or epoll, or the original TCP group
     */
    @WrapOperation(method = "bind", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;group(Lio/netty/channel/EventLoopGroup;)Lio/netty/bootstrap/ServerBootstrap;", remap = false))
    private ServerBootstrap redirectGroup(ServerBootstrap instance, EventLoopGroup group, Operation<ServerBootstrap> original) {
        final boolean useEpoll = Epoll.isAvailable() && this.server.isUsingNativeTransport();
        return ThreadLocalUtil.isInitializingRaknet()
                ? original.call(instance, useEpoll ? RaknetifyEventLoops.EPOLL_EVENT_LOOP_GROUP.get() : RaknetifyEventLoops.NIO_EVENT_LOOP_GROUP.get())
                : original.call(instance, group);
    }

    /**
     * Substitutes a RakNet server channel with its own application loop during a guarded UDP bind.
     *
     * @param instance vanilla server bootstrap being configured
     * @param aClass original TCP server socket class
     * @param original intercepted bootstrap channel-class call
     * @return bootstrap using the UDP-backed RakNet factory or the original TCP channel class
     */
    @WrapOperation(method = "bind", at = @At(value = "INVOKE", target = "Lio/netty/bootstrap/ServerBootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;", remap = false))
    private AbstractBootstrap<ServerBootstrap, ServerChannel> redirectChannel(ServerBootstrap instance, Class<? extends ServerSocketChannel> aClass, Operation<AbstractBootstrap<ServerBootstrap, ServerChannel>> original) {
        final boolean useEpoll = Epoll.isAvailable() && this.server.isUsingNativeTransport();
        return ThreadLocalUtil.isInitializingRaknet()
                ? instance.channelFactory(() -> {
            RakNetServerChannel channel = ProxyProtocol.create(() -> {
                final DatagramChannel channel1 = useEpoll ? new EpollDatagramChannel() : new NioDatagramChannel();
                channel1.config().setOption(ChannelOption.SO_REUSEADDR, true);
                channel1.config().setOption(ChannelOption.IP_TOS, RakNetConnectionUtil.DEFAULT_IP_TOS);
                channel1.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(Constants.LARGE_MTU + 512).maxMessagesPerRead(128));
                return channel1;
            });
            channel.setProvidedApplicationEventLoop(RaknetifyEventLoops.DEFAULT_EVENT_LOOP_GROUP.get().next());
            return channel;
        })
                : original.call(instance, aClass);
    }

}
