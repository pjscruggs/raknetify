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

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.SynchronizationLayer;
import com.ishland.raknetify.fabric.mixin.RaknetifyFabricMixinPlugin;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

import net.minecraft.network.handler.NetworkStateTransitions;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.AcknowledgeReconfigurationC2SPacket;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import net.minecraft.network.packet.s2c.play.EnterReconfigurationS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;

import network.ycc.raknet.RakNet;

/**
 * Coordinates RakNet ordering with Minecraft world changes, command availability, and codec
 * transitions.
 */
public class RakNetFabricChannelEventListener extends ChannelDuplexHandler {

    /** Pipeline name for the Minecraft packet and protocol-transition observer. */
    public static final String NAME = "raknetify-fabric-event-listener";

    /** Whether the running legacy Minecraft version contains the reconfiguration packet classes. */
    private static final boolean isReconfigurationSupported;

    static {
        boolean isReconfigurationSupported0;
        try {
            isReconfigurationSupported0 =
                    Packet.class.isAssignableFrom(EnterReconfigurationS2CPacket.class);
        } catch (NoClassDefFoundError e) {
            isReconfigurationSupported0 = false;
        }
        isReconfigurationSupported = isReconfigurationSupported0;
    }

    /**
     * Sends ordering-reset markers at world or configuration boundaries and starts multichannel
     * delivery at commands. Codec-transition tasks are wrapped so packet capture remains adjacent
     * to the replacement encoder.
     *
     * @param ctx outbound Minecraft context
     * @param msg decoded packet or codec-configuration task
     * @param promise completion notification forwarded with the packet or wrapped task
     * @throws Exception if superclass outbound forwarding fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        if (msg instanceof PlayerRespawnS2CPacket || msg instanceof GameJoinS2CPacket) {
            ctx.write(SynchronizationLayer.SYNC_REQUEST_OBJECT)
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER);
        }
        if (isReconfigurationSupported) {
            if (msg instanceof EnterReconfigurationS2CPacket
                    || msg instanceof AcknowledgeReconfigurationC2SPacket) {
                ctx.write(SynchronizationLayer.SYNC_REQUEST_OBJECT)
                        .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            }
        }
        if (msg instanceof CommandTreeS2CPacket) {
            ctx.write(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL)
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER);
        }
        if (RaknetifyFabricMixinPlugin.AFTER_1_20_5) {
            if (msg instanceof NetworkStateTransitions.DecoderTransitioner
                    || msg instanceof NetworkStateTransitions.EncoderTransitioner) {
                super.write(ctx, RakNetNetworkTransitionUtil.handleTransition(msg), promise);
                return;
            }
        }
        super.write(ctx, msg, promise);
    }
}
