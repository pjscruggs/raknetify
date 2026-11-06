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

import com.ishland.raknetify.common.connection.SimpleMetricsLogger;

import io.netty.channel.Channel;

import net.minecraft.network.BandwidthDebugMonitor;
import net.minecraft.network.Connection;

import network.ycc.raknet.RakNet;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds RakNet byte counters into Minecraft's bandwidth debug sampler. */
@Mixin(Connection.class)
public class MixinClientConnection1_20_2 {

    /** Vanilla application channel whose transport metrics supply received-byte totals. */
    @Shadow private Channel channel;

    /** Optional vanilla bandwidth sampler, absent when debug sampling is disabled. */
    @Shadow private @Nullable BandwidthDebugMonitor bandwidthDebugMonitor;

    /** Received transport-byte total at the previous sample, used to submit only the new bytes. */
    @Unique private long raknetify$lastBytesIn;

    /**
     * Adds the latest RakNet received-byte delta immediately before vanilla advances its sampler.
     *
     * @param ci injection callback, left uncancelled so vanilla records the sample normally
     */
    @Inject(
            method = "tick",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/network/BandwidthDebugMonitor;tick()V"))
    private void onPacketLoggerPush(final CallbackInfo ci) {
        BandwidthDebugMonitor logger = this.bandwidthDebugMonitor;
        if (logger != null
                && this.channel.config() instanceof RakNet.Config config
                && config.getMetrics() instanceof SimpleMetricsLogger simpleMetricsLogger) {
            long bytesIn = simpleMetricsLogger.getBytesIn();
            logger.onReceive((int) (bytesIn - this.raknetify$lastBytesIn));
            this.raknetify$lastBytesIn = bytesIn;
        }
    }
}
