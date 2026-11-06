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

import com.ishland.raknetify.common.connection.TransferSupport;
import com.ishland.raknetify.common.connection.TransferQueryAnswer;
import com.ishland.raknetify.fabric.common.connection.TransferQuery;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Negotiates automatic transfer support after authentication and before vanilla finishes login. */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 1100)
public abstract class MixinTransferNegotiation {

    /** Native connection retains the support flag across login, configuration and play listeners. */
    @Shadow @Final private Connection connection;
    /** Query ownership is private to this listener and cannot authorize admission. */
    @Unique private final TransferSupport.Negotiation raknetify$transfer = new TransferSupport.Negotiation();

    /**
     * Leaves the vanilla verification state and timeout intact while waiting for one optional capability reply.
     * @param callback cancels only this verification attempt, not the outer login tick
     */
    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
    private void raknetify$queryTransferSupport(CallbackInfo callback) {
        if (!TransferSupport.enabled()) return;
        if (this.raknetify$transfer.start()) {
            this.connection.send(new ClientboundCustomQueryPacket(this.raknetify$transfer.transactionId(), new TransferQuery()));
        }
        if (this.raknetify$transfer.waiting()) callback.cancel();
    }

    /**
     * Consumes the owned reply before vanilla or Fabric API treats its transaction ID as unexpected.
     * @param packet native response carrying the original wire-level handled flag
     * @param callback cancels dispatch only when the pending transaction belongs to this feature
     */
    @Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
    private void raknetify$receiveTransferSupport(ServerboundCustomQueryAnswerPacket packet, CallbackInfo callback) {
        if (this.raknetify$transfer.answer(packet.transactionId(), ((TransferQueryAnswer) (Object) packet).raknetify$isTransferSupported(),
                ((IClientConnection) this.connection).getChannel())) callback.cancel();
    }

}
