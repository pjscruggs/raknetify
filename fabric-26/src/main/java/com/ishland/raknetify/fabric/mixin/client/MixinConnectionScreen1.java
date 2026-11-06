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

package com.ishland.raknetify.fabric.mixin.client;

import com.ishland.raknetify.common.util.PrefixUtil;
import com.ishland.raknetify.fabric.common.client.ServerAddressUtil;
import com.ishland.raknetify.fabric.common.connection.RakNetClientConnectionUtil;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import io.netty.channel.ChannelFuture;

import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.server.network.EventLoopGroupHolder;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;

/**
 * Carries a saved server's RakNet selection into the connection thread after stripping its hostname
 * prefix.
 */
@Mixin(targets = "net/minecraft/client/gui/screens/ConnectScreen$1")
public class MixinConnectionScreen1 extends Thread {

    /**
     * Address captured by the vanilla connection thread, replaced with its unprefixed equivalent.
     */
    @Mutable @Shadow @Final ServerAddress val$hostAndPort;

    /** Whether the original server address selected RakNet for this connection attempt. */
    @Unique private boolean isRaknet;

    /** Whether the original server address selected the opt-in larger MTU. */
    @Unique private boolean raknetLargeMTU;

    /**
     * Captures transport flags and removes the prefix before the connection thread resolves the
     * hostname.
     *
     * @param ci constructor-return callback after vanilla captures the target address
     */
    @Inject(method = "<init>*", at = @At("RETURN"), remap = false)
    private void onInit(final CallbackInfo ci) {
        final PrefixUtil.Info info = PrefixUtil.getInfo(this.val$hostAndPort.getHost());
        if (info.useRakNet()) {
            this.isRaknet = true;
            this.raknetLargeMTU = info.largeMTU();
            this.val$hostAndPort =
                    ServerAddressUtil.withAddress(
                            this.val$hostAndPort, info.stripped(), this.val$hostAndPort.getPort());
        }
    }

    /**
     * Selects the guarded RakNet factory only when this connection attempt requested UDP.
     *
     * @param address resolved remote Minecraft address
     * @param backend vanilla event-loop backend selection
     * @param connection existing Minecraft connection receiving the new channel
     * @param original intercepted vanilla connection factory
     * @return channel future for the selected transport
     */
    @WrapOperation(
            method = "run()V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/Connection;connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;"),
            require = 0)
    private ChannelFuture connectRaknet(
            final InetSocketAddress address,
            final EventLoopGroupHolder backend,
            final Connection connection,
            final Operation<ChannelFuture> original) {
        return this.isRaknet
                ? RakNetClientConnectionUtil.connect(
                        address, backend, this.raknetLargeMTU, original, connection)
                : original.call(address, backend, connection);
    }
}
