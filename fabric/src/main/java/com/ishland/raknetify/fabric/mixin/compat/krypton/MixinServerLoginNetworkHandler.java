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

package com.ishland.raknetify.fabric.mixin.compat.krypton;

import com.ishland.raknetify.fabric.common.util.MultiVersionUtil;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.encryption.NetworkEncryptionException;
import net.minecraft.network.encryption.NetworkEncryptionUtils;
import net.minecraft.network.packet.c2s.login.LoginKeyC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;

import network.ycc.raknet.RakNet;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.security.PrivateKey;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

/** Replaces Krypton's stream ciphers with RakNet frame encryption after vanilla key negotiation. */
@Mixin(ServerLoginNetworkHandler.class)
public class MixinServerLoginNetworkHandler {

    /** Login connection inspected for Krypton cipher handlers. */
    @Shadow @Final public ClientConnection connection;

    /** Server supplying the RSA private key used to recover the negotiated session secret. */
    @Shadow @Final private MinecraftServer server;

    /**
     * Vanilla login challenge retained by the target listener, unused by this compatibility hook.
     */
    @Shadow @Final private byte[] nonce;

    /**
     * Detects Krypton cipher handlers and re-enters Minecraft encryption setup with standard
     * ciphers. The RakNet connection hooks then install frame encryption in their place.
     *
     * @param packet client key response containing the encrypted session secret
     * @param ci injection callback after vanilla encryption setup
     * @throws NetworkEncryptionException if recovering the session key or creating replacement
     *     ciphers fails
     */
    @Inject(
            method = "onKey",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/network/ClientConnection;setupEncryption(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V",
                            shift = At.Shift.AFTER))
    private void afterSetupEncryption(final LoginKeyC2SPacket packet, final CallbackInfo ci)
            throws NetworkEncryptionException {
        final ChannelPipeline pipeline =
                ((IClientConnection) this.connection).getChannel().pipeline();
        if (((IClientConnection) this.connection).getChannel().config() instanceof RakNet.Config) {
            final ChannelHandler decrypt = pipeline.get("decrypt");
            final ChannelHandler encrypt = pipeline.get("encrypt");
            if (decrypt != null
                    && ("me.steinborn.krypton.mod.shared.network.pipeline.MinecraftCipherDecoder"
                            .equals(decrypt.getClass().getName()))
                    && encrypt != null
                    && ("me.steinborn.krypton.mod.shared.network.pipeline.MinecraftCipherEncoder"
                            .equals(encrypt.getClass().getName()))) {
                System.out.println("Raknetify: Krypton detected, applying compatibility");

                pipeline.remove("decrypt");
                pipeline.remove("encrypt");

                // TODO [VanillaCopy]
                PrivateKey privateKey = this.server.getKeyPair().getPrivate();

                SecretKey secretKey = packet.decryptSecretKey(privateKey);
                Cipher cipher = NetworkEncryptionUtils.cipherFromKey(2, secretKey);
                Cipher cipher2 = NetworkEncryptionUtils.cipherFromKey(1, secretKey);

                MultiVersionUtil.setEncrypted(this.connection, false);
                this.connection.setupEncryption(cipher, cipher2);
            }
        }
    }
}
