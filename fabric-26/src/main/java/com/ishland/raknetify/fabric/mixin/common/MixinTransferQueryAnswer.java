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

import com.ishland.raknetify.common.connection.TransferQueryAnswer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Observes the handled flag without consuming bytes or interfering with other login payload decoders. */
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public abstract class MixinTransferQueryAnswer implements TransferQueryAnswer {

    /** True only for the exact empty handled response used by the transfer capability contract. */
    @Unique private boolean raknetify$transferSupported;

    /**
     * Preserves the nullable wire marker before vanilla replaces unknown response data with a discard marker.
     * @param buffer native login response beginning with its transaction VarInt
     * @param original complete existing decoder, including other mods' payload hooks
     * @return the original native packet with an additional private capability observation
     */
    @WrapMethod(method = "read")
    private static ServerboundCustomQueryAnswerPacket raknetify$observeAnswer(FriendlyByteBuf buffer, Operation<ServerboundCustomQueryAnswerPacket> original) {
        int start = buffer.readerIndex();
        final boolean supported;
        try {
            buffer.readVarInt();
            supported = buffer.readableBytes() == 1 && buffer.getUnsignedByte(buffer.readerIndex()) == 1;
        } finally {
            buffer.readerIndex(start);
        }
        ServerboundCustomQueryAnswerPacket packet = original.call(buffer);
        ((TransferQueryAnswer) (Object) packet).raknetify$setTransferSupported(supported);
        return packet;
    }

    /**
     * Returns the observation belonging to this decoded response, never a result from another connection.
     * @return whether the response has the exact handled empty form
     */
    @Override public boolean raknetify$isTransferSupported() {
        return this.raknetify$transferSupported;
    }

    /**
     * Stores the observed flag after the original decoder completes successfully.
     * @param supported decoded handled-empty response flag
     */
    @Override public void raknetify$setTransferSupported(boolean supported) {
        this.raknetify$transferSupported = supported;
    }
}
