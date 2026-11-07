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

package com.ishland.raknetify.test.mixin;

import com.ishland.raknetify.test.EncryptionExchange;
import com.mojang.authlib.GameProfile;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.security.PublicKey;
import java.util.UUID;

import javax.crypto.SecretKey;

/** Exercises the snapshot's native options acknowledgement after real RSA challenge validation. */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 2000)
public abstract class EncryptionLogin26_4Mixin {
    /** Native connection receiving the options acknowledgement and installing the real ciphers. */
    @Shadow @Final public Connection connection;

    /**
     * Secret already decrypted and challenge-validated by Minecraft before the digest invocation.
     */
    @Unique private @org.jspecify.annotations.Nullable SecretKey fixtureSecret;

    /**
     * Invokes the native options request instead of fabricating a cipher installation in the test.
     *
     * @param profile disposable fixture identity that is never admitted to the game
     * @param secret native validated secret retained until the options acknowledgement
     */
    @Invoker("applyConnectionEncryption")
    protected abstract void requestOptions(GameProfile profile, SecretKey secret);

    /**
     * Observes the validated secret while preserving Minecraft's actual session digest computation.
     *
     * @param serverId vanilla server identity passed to the digest
     * @param publicKey native server public key
     * @param secret secret whose challenge Minecraft has already validated
     * @return unchanged native session digest
     * @throws CryptException if Minecraft's digest operation fails
     */
    @Redirect(
            method = "handleKey",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/util/Crypt;digestData(Ljava/lang/String;Ljava/security/PublicKey;Ljavax/crypto/SecretKey;)[B"))
    private byte[] captureSecret(
            final String serverId, final PublicKey publicKey, final SecretKey secret)
            throws CryptException {
        fixtureSecret = secret;
        return Crypt.digestData(serverId, publicKey, secret);
    }

    /**
     * Substitutes a disposable profile before the external account lookup thread can start.
     *
     * @param callback stops this isolated login from contacting the account service
     * @throws ReflectiveOperationException if the native Netty channel cannot be inspected
     */
    @Inject(
            method = "handleKey",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;start()V"),
            cancellable = true)
    private void requestFixtureOptions(final CallbackInfo callback)
            throws ReflectiveOperationException {
        EncryptionExchange.requireTestConnection(EncryptionExchange.channel(connection));
        requestOptions(
                new GameProfile(new UUID(0, 1), "EncryptionProbe"),
                java.util.Objects.requireNonNull(
                        fixtureSecret, "Native login secret was not captured"));
        fixtureSecret = null;
        callback.cancel();
    }

    /**
     * Installs the challenge only after the client acknowledgement causes native cipher setup.
     *
     * @param callback prevents transition to player verification or admission
     * @throws ReflectiveOperationException if the native Netty channel cannot be inspected
     */
    @Inject(
            method = "handleOptionsEnabled",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;applyConnectionOptions(B)V",
                            shift = At.Shift.AFTER),
            cancellable = true)
    private void afterOptions(final CallbackInfo callback) throws ReflectiveOperationException {
        EncryptionExchange.install(EncryptionExchange.channel(connection));
        callback.cancel();
    }
}
