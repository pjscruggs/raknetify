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

package com.ishland.raknetify.fabric.mixin.client;

import com.ishland.raknetify.common.connection.TransferSupport;

import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.login.LoginQueryResponseC2SPacket;
import net.minecraft.network.packet.s2c.login.LoginQueryRequestS2CPacket;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advertises transfer-address support even when the initial connection uses ordinary TCP. */
@Mixin(value = ClientLoginNetworkHandler.class, priority = 1100)
public abstract class MixinTransferCapability {

    /** Native login connection used to answer only the recognized capability query. */
    @Shadow @Final private ClientConnection connection;

    /**
     * Sends a handled empty reply for version one and leaves every other login query to its normal
     * handler.
     *
     * @param packet decoded native query, including unknown payload identifiers preserved by
     *     Minecraft
     * @param callback cancels the fallback unhandled response after sending our single reply
     */
    @Inject(method = "onQueryRequest", at = @At("HEAD"), cancellable = true)
    private void raknetify$answerTransferQuery(
            final LoginQueryRequestS2CPacket packet, final CallbackInfo callback) {
        if (!TransferSupport.QUERY_ID.equals(packet.payload().id().toString())) {
            return;
        }
        this.connection.send(new LoginQueryResponseC2SPacket(packet.queryId(), buffer -> {}));
        callback.cancel();
    }
}
