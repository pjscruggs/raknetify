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

package com.ishland.raknetify.forge.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Gives Forge's retained custom payloads GC-owned bytes instead of an allocator buffer with no release owner. */
@Mixin(value = ForgeHooks.class, remap = false)
public abstract class MixinForgePayloadStorage {
    /**
     * Copies into an independent byte array because Forge payload events may retain data after dispatch.
     * Forge has no final-release callback for those events. A pooled or direct allocation therefore leaks,
     * while releasing after dispatch would invalidate deferred readers. The ordinary wrapper is kept by Forge.
     * @param source inbound buffer whose lifetime still belongs to the packet decoder
     * @param length byte count already validated against Forge's payload size limit
     * @return independent heap storage reclaimed with the payload, including when dispatch throws
     */
    @Redirect(method = "lambda$getCustomPayloadCodec$1", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;readBytes(I)Lio/netty/buffer/ByteBuf;"), require = 1)
    private static ByteBuf copyPayloadBytes(FriendlyByteBuf source, int length) {
        byte[] bytes = new byte[length];
        source.readBytes(bytes);
        return Unpooled.wrappedBuffer(bytes);
    }
}
