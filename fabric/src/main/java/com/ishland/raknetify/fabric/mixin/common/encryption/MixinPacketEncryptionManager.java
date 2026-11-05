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

import com.ishland.raknetify.fabric.common.connection.encryption.PacketEncryptionManagerInterface;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.encryption.PacketEncryptionManager;
import network.ycc.raknet.RakNet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.ShortBufferException;

/**
 * Uses independent cipher finalization for RakNet payloads while retaining streaming updates for TCP.
 */
@Mixin(PacketEncryptionManager.class)
public class MixinPacketEncryptionManager implements PacketEncryptionManagerInterface {

    /**
     * Most recent cipher handler context, supplied immediately before encryption or decryption.
     */
    @Unique
    private ChannelHandlerContext ctx;

    /**
     * Records the channel used to select frame-based or byte-stream cipher behavior.
     *
     * @param ctx active encryption or decryption handler context
     */
    @Override
    public void setContext(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Transforms one inbound payload, resetting cipher state per RakNet frame through doFinal.
     * TCP continues using Cipher.update so its byte-stream state remains continuous.
     *
     * @param instance initialized vanilla cipher
     * @param input source array containing this payload
     * @param inputOffset first payload byte in the source array
     * @param inputLen number of source bytes to process
     * @param output destination array for transformed bytes
     * @param outputOffset first destination index available for transformed bytes
     * @return number of bytes written to the destination array
     * @throws ShortBufferException if the destination has insufficient space
     * @throws IllegalBlockSizeException if finalizing the payload violates the cipher's block-size requirements
     * @throws BadPaddingException if finalizing decryption rejects the payload padding
     */
    @Redirect(method = "decrypt", at = @At(value = "INVOKE", target = "Ljavax/crypto/Cipher;update([BII[BI)I"))
    private int redirectDecrypt(Cipher instance, byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
        if (ctx.channel().config() instanceof RakNet.Config) {
            return instance.doFinal(input, inputOffset, inputLen, output, outputOffset);
        } else {
            return instance.update(input, inputOffset, inputLen, output, outputOffset);
        }
    }

    /**
     * Transforms one outbound payload, resetting cipher state per RakNet frame through doFinal.
     * TCP continues using Cipher.update so its byte-stream state remains continuous.
     *
     * @param instance initialized vanilla cipher
     * @param input source array containing this payload
     * @param inputOffset first payload byte in the source array
     * @param inputLen number of source bytes to process
     * @param output destination array for transformed bytes
     * @return number of bytes written to the destination array
     * @throws ShortBufferException if the destination has insufficient space
     * @throws IllegalBlockSizeException if finalizing the payload violates the cipher's block-size requirements
     * @throws BadPaddingException if finalizing decryption rejects the payload padding
     */
    @Redirect(method = "encrypt", at = @At(value = "INVOKE", target = "Ljavax/crypto/Cipher;update([BII[B)I"))
    private int redirectEncrypt(Cipher instance, byte[] input, int inputOffset, int inputLen, byte[] output) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
        if (ctx.channel().config() instanceof RakNet.Config) {
            return instance.doFinal(input, inputOffset, inputLen, output);
        } else {
            return instance.update(input, inputOffset, inputLen, output);
        }
    }

}
