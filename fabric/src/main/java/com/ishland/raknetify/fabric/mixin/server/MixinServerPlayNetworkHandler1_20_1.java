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

import com.ishland.raknetify.fabric.common.util.MultiVersionUtil;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.WrapWithCondition;

import io.netty.channel.Channel;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import network.ycc.raknet.RakNet;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adapts legacy play-listener keepalives and the older player ping field to RakNet measurements.
 */
@Mixin(ServerPlayNetworkHandler.class)
public class MixinServerPlayNetworkHandler1_20_1 {

    /** Legacy server player whose ping field receives the transport latency estimate. */
    @Shadow public ServerPlayerEntity player;

    /**
     * Keeps vanilla's keepalive interval from expiring on connections already monitored by RakNet.
     *
     * @param original vanilla timestamp of the last keepalive check
     * @return current monotonic time for RakNet, or the original timestamp for TCP
     */
    @Dynamic
    @ModifyExpressionValue(
            method = "tick",
            at =
                    @At(
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/server/network/ServerPlayNetworkHandler;field_14136:J",
                            opcode = Opcodes.GETFIELD))
    private long disableKeepAlive(final long original) {
        if (!(((IClientConnection)
                                MultiVersionUtil.ServerPlayNetworkHandler$connection.get(
                                        (ServerPlayNetworkHandler) (Object) this))
                        .getChannel()
                        .config()
                instanceof RakNet.Config)) {
            return original;
        }
        return Util.getMeasuringTimeMs();
    }

    /**
     * Leaves connection timeout decisions to RakNet instead of vanilla keepalive replies.
     *
     * @param instance vanilla listener about to disconnect the player
     * @param reason vanilla keepalive disconnect reason
     * @return false for RakNet to suppress this disconnect, true for ordinary TCP behavior
     */
    @Dynamic
    @WrapWithCondition(
            method = "method_12082",
            remap = false,
            at =
                    @At(
                            remap = true,
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/ServerPlayNetworkHandler;method_14367(Lnet/minecraft/text/Text;)V"))
    private boolean stopTimeoutPlayersOnKeepAlive(
            final ServerPlayNetworkHandler instance, final Text reason) {
        return !(((IClientConnection)
                                MultiVersionUtil.ServerPlayNetworkHandler$connection.get(
                                        (ServerPlayNetworkHandler) (Object) this))
                        .getChannel()
                        .config()
                instanceof RakNet.Config);
    }

    /**
     * Prevents vanilla keepalive timing from overwriting transport-derived ping.
     *
     * @param instance vanilla object whose latency field would be updated
     * @param value latency measured from the vanilla keepalive exchange
     * @return false for RakNet, true when vanilla should store the TCP measurement
     */
    @Dynamic
    @WrapWithCondition(
            method = "method_12082",
            remap = false,
            at =
                    @At(
                            remap = true,
                            value = "FIELD",
                            target =
                                    "Lnet/minecraft/server/network/ServerPlayerEntity;field_13967:I",
                            opcode = Opcodes.PUTFIELD))
    private boolean redirectPingStoring(final ServerPlayerEntity instance, final int value) {
        return !(((IClientConnection)
                                MultiVersionUtil.ServerPlayNetworkHandler$connection.get(
                                        (ServerPlayNetworkHandler) (Object) this))
                        .getChannel()
                        .config()
                instanceof RakNet.Config);
    }

    /**
     * Publishes RakNet RTT plus its standard deviation as the player latency in milliseconds.
     *
     * @param ci tick injection callback, left uncancelled so other vanilla maintenance continues
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(final CallbackInfo ci) {
        final Channel channel =
                ((IClientConnection)
                                MultiVersionUtil.ServerPlayNetworkHandler$connection.get(
                                        (ServerPlayNetworkHandler) (Object) this))
                        .getChannel();
        if (channel != null && channel.config() instanceof RakNet.Config config) {
            assert MultiVersionUtil.ServerPlayerEntity$pingMillis1_20_1 != null;
            MultiVersionUtil.ServerPlayerEntity$pingMillis1_20_1.set(
                    this.player,
                    (int) ((config.getRTTNanos() + config.getRTTStdDevNanos()) / 1_000_000));
        }
    }
}
