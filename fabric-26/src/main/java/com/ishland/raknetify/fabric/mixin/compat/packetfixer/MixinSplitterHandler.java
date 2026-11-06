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

package com.ishland.raknetify.fabric.mixin.compat.packetfixer;

import com.ishland.raknetify.common.connection.ReplacedBufferCleanup;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Releases helper buffers abandoned by PacketFixer's native TCP frame-size adjustment. */
@Mixin(Varint21FrameDecoder.class)
public abstract class MixinSplitterHandler {

    /** Minecraft's constructor-owned buffer, which PacketFixer may replace during decoding. */
    @Shadow @Final private ByteBuf helperBuf;

    /**
     * Retains ordinary frame decoding and closes only an unreleased replacement ownership gap.
     * The plugin enables this hook only when PacketFixer is installed.
     * @param context active native decoder context
     * @param input incoming TCP stream bytes
     * @param output native decoder's completed frames
     * @param original transformed decoder, including PacketFixer's size adjustment
     */
    @WrapMethod(method = "decode")
    private void raknetify$releaseReplacedHelper(ChannelHandlerContext context, ByteBuf input,
                                                List<Object> output, Operation<Void> original) {
        ByteBuf previous = this.helperBuf;
        int references = previous.refCnt();
        try {
            original.call(context, input, output);
        } finally {
            ReplacedBufferCleanup.releaseAbandoned(previous, this.helperBuf, references);
        }
    }
}
