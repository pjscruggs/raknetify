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

package com.ishland.raknetify.fabric.mixin.common.encryption;

import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;

import io.netty.channel.Channel;

import net.minecraft.network.Connection;

import network.ycc.raknet.RakNet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.crypto.Cipher;

/**
 * Handles snapshot encryption setup where Minecraft enables inbound and outbound ciphers
 * separately.
 */
@Mixin(Connection.class)
public class MixinClientConnection26_4 {
    /** Minecraft application channel receiving direction-specific frame encryption handlers. */
    @Shadow private Channel channel;

    /**
     * Installs only frame decryption when the snapshot enables inbound encryption on a RakNet
     * connection.
     *
     * @param cipher initialized decryption cipher supplied by Minecraft
     * @param ci cancellable callback suppressing the corresponding vanilla stream handler
     */
    @Inject(method = "setInboundEncryptionKey", remap = false, at = @At("HEAD"), cancellable = true)
    private void enableInboundEncryption(final Cipher cipher, final CallbackInfo ci) {
        if (this.channel.config() instanceof RakNet.Config) {
            ci.cancel();
            this.channel
                    .pipeline()
                    .addBefore(
                            MultiChannelingStreamingCompression.NAME,
                            MultiChannellingEncryption.NAME + "-inbound",
                            MultiChannellingEncryption.inbound(cipher));
        }
    }

    /**
     * Installs only frame encryption after compression in outbound pipeline traversal.
     *
     * @param cipher initialized encryption cipher supplied by Minecraft
     * @param ci cancellable callback suppressing the corresponding vanilla stream handler
     */
    @Inject(
            method = "setOutboundEncryptionKey",
            remap = false,
            at = @At("HEAD"),
            cancellable = true)
    private void enableOutboundEncryption(final Cipher cipher, final CallbackInfo ci) {
        if (this.channel.config() instanceof RakNet.Config) {
            ci.cancel();
            // Outbound traversal runs in reverse, so addBefore encrypts the frames after streaming
            // compression.
            this.channel
                    .pipeline()
                    .addBefore(
                            MultiChannelingStreamingCompression.NAME,
                            MultiChannellingEncryption.NAME + "-outbound",
                            MultiChannellingEncryption.outbound(cipher));
        }
    }
}
