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

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import network.ycc.raknet.RakNet;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Handles both legacy player-join signatures when logging and starting RakNet multichannel delivery.
 */
@Mixin(PlayerManager.class)
public class MixinPlayerManager1_20_1 {

    /**
     * Logs the negotiated RakNet MTU when a player enters the server. TCP joins are left unchanged.
     *
     * @param connection player's initialized Minecraft connection
     * @param player server player entering the world
     * @param latency legacy join latency argument, unused by the transport log
     * @param ci injection callback, left uncancelled so vanilla join processing is unchanged
     */
    // The two literal selectors cover named development and intermediary production runtimes.
    @Dynamic
    @Inject(remap = false, method = {"onPlayerConnect", "method_12975"}, at = @At("HEAD"), require = 0)
    private void onJoin(ClientConnection connection, ServerPlayerEntity player, int latency, CallbackInfo ci) {
        if (((IClientConnection) connection).getChannel().config() instanceof RakNet.Config config) {
            System.out.println(String.format("Raknetify: %s logged in via RakNet, mtu %d", player.getName().getString(), config.getMTU()));
        }
    }

    /**
     * Logs the negotiated RakNet MTU when a player enters the server. TCP joins are left unchanged.
     * This surrogate serves Minecraft versions without the explicit latency argument.
     *
     * @param connection player's initialized Minecraft connection
     * @param player server player entering the world
     * @param ci injection callback, left uncancelled so vanilla join processing is unchanged
     */
    @Surrogate
    private void onJoin(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
        if (((IClientConnection) connection).getChannel().config() instanceof RakNet.Config config) {
            System.out.println(String.format("Raknetify: %s logged in via RakNet, mtu %d", player.getName().getString(), config.getMTU()));
        }
    }

    /**
     * Requests multichannel negotiation after vanilla finishes sending the player's initial join state.
     *
     * @param connection player's initialized Minecraft connection
     * @param player server player entering the world
     * @param latency legacy join latency argument, unused by channel negotiation
     * @param ci injection callback, left uncancelled so vanilla join processing is unchanged
     */
    // The two literal selectors cover named development and intermediary production runtimes.
    @Dynamic
    @Inject(remap = false, method = {"onPlayerConnect", "method_12975"}, at = @At("RETURN"), require = 0)
    private void postJoin(ClientConnection connection, ServerPlayerEntity player, int latency, CallbackInfo ci) {
        final Channel channel = ((IClientConnection) connection).getChannel();
        if (channel == null) {
            //noinspection RedundantStringFormatCall
            System.err.println("Raknetify: Warning: %s don't have valid channel when logged in, not sending sync packet".formatted(this));
            return;
        }
        channel.write(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
    }

    /**
     * Requests multichannel negotiation after vanilla finishes sending the player's initial join state.
     * This surrogate serves Minecraft versions without the explicit latency argument.
     *
     * @param connection player's initialized Minecraft connection
     * @param player server player entering the world
     * @param ci injection callback, left uncancelled so vanilla join processing is unchanged
     */
    @Surrogate
    private void postJoin(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
        final Channel channel = ((IClientConnection) connection).getChannel();
        if (channel == null) {
            //noinspection RedundantStringFormatCall
            System.err.println("Raknetify: Warning: %s don't have valid channel when logged in, not sending sync packet".formatted(this));
            return;
        }
        channel.write(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
    }

}
