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

import com.ishland.raknetify.common.connection.TransferDestinations;
import com.ishland.raknetify.common.connection.TransferSupport;
import io.netty.channel.Channel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Rewrites configured transfer destinations at the shared send path, including transfers with completion callbacks. */
@Mixin(ClientConnection.class)
public abstract class MixinTransferDestination {

    /** Connection-specific capability is retained on the native Netty channel. */
    @Shadow private Channel channel;

    /**
     * Substitutes only the native transfer packet and retains the caller's original send completion behavior.
     * @param packet outgoing packet after all public send overloads converge
     * @return original packet or an equivalent transfer selecting the configured RakNet endpoint
     */
    // The intermediary ID spans both PacketCallbacks and ChannelFutureListener signatures.
    @Dynamic
    @ModifyVariable(remap = false, method = {"sendImmediately", "method_10764"},
            at = @At("HEAD"), argsOnly = true, require = 1)
    private Packet<?> raknetify$selectTransferDestination(Packet<?> packet) {
        if (!(packet instanceof ServerTransferS2CPacket transfer)) return packet;
        TransferDestinations.Endpoint selected = TransferSupport.select(this.channel, transfer.host(), transfer.port());
        return selected == null ? packet : new ServerTransferS2CPacket(selected.host(), selected.port());
    }
}
