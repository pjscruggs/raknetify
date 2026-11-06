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

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops the disposable login after real key negotiation and before external account authentication. */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 2000)
public class EncryptionLoginMixin {

    /** Native connection whose pipeline was configured by the installed networking mods. */
    @Shadow @Final public Connection connection;

    /**
     * Replaces the rest of this test login with a bounded encrypted challenge exchange.
     * Higher mixin priority places this callback after the compatibility hook at the same invocation.
     *
     * @param packet native RSA key response already validated by Minecraft
     * @param callback cancellation prevents account lookup and player admission
     * @throws ReflectiveOperationException if the native connection has no accessible Netty channel
     */
    @Inject(method = "handleKey", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/Connection;setEncryptionKey(Ljavax/crypto/Cipher;Ljavax/crypto/Cipher;)V",
            shift = At.Shift.AFTER), cancellable = true)
    private void afterEncryption(ServerboundKeyPacket packet, CallbackInfo callback) throws ReflectiveOperationException {
        EncryptionExchange.install(EncryptionExchange.channel(this.connection));
        callback.cancel();
    }
}
