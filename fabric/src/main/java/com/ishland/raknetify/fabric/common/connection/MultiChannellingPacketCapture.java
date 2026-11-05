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

package com.ishland.raknetify.fabric.common.connection;

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;

/**
 * Carries the outgoing packet's Java class through synchronous encoding into the frame classifier.
 * Each connection uses its own instance. The temporary capture is cleared when the write returns.
 */
@ChannelHandler.Sharable
public class MultiChannellingPacketCapture extends ChannelOutboundHandlerAdapter {

    /**
     * Class of the packet currently traversing the encoder, or null outside a captured write.
     */
    private Class<?> packetClass = null;

    /**
     * Exposes the decoded packet class while downstream encoding runs and clears it in a finally block.
     *
     * @param ctx outbound context positioned before packet encoding in write order
     * @param msg decoded Minecraft packet being serialized
     * @param promise completion notification forwarded with the original packet
     * @throws Exception reserved by the Netty write callback contract
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        this.packetClass = msg.getClass();
        try {
            ctx.write(msg, promise);
        } finally {
            this.packetClass = null;
        }
    }

    /**
     * Reads the packet class currently available to encoding-time classifiers.
     *
     * @return active packet class, or null outside a captured write
     */
    public Class<?> getPacketClass() {
        return this.packetClass;
    }

    /**
     * Sets the capture explicitly for integration code that controls the encoding boundary.
     *
     * @param packetClass current decoded packet class, or null to clear the capture
     */
    public void setPacketClass(Class<?> packetClass) {
        this.packetClass = packetClass;
    }

    /**
     * Creates a classifier that applies the vanilla class-to-channel table to this capture.
     *
     * @return classifier tied to this capture instance's current packet class
     */
    public RakNetSimpleMultiChannelCodec.OverrideHandler getCaptureBasedHandler() {
        return new CaptureBasedHandler();
    }

    /**
     * Creates an identifier-aware classifier that activates only for captured custom-payload packets.
     *
     * @return custom-payload classifier tied to this capture instance
     */
    public RakNetSimpleMultiChannelCodec.OverrideHandler getCustomPayloadHandler() {
        return new CustomPayloadChannel.OverrideHandler(value -> packetClass == CustomPayloadS2CPacket.class || packetClass == CustomPayloadC2SPacket.class);
    }

    /**
     * Reads the enclosing capture instead of decoding a protocol-dependent packet ID.
     */
    private class CaptureBasedHandler implements RakNetSimpleMultiChannelCodec.OverrideHandler {

        /**
         * Classifies the captured packet while leaving the encoded payload untouched.
         *
         * @param buf encoded Minecraft payload, unused because the packet class is already known
         * @param suppressWarning whether unknown classes should avoid a diagnostic
         * @return ordering channel or special delivery policy from the vanilla class table
         */
        @Override
        public int getChannelOverride(ByteBuf buf, boolean suppressWarning) {
            return RakNetMultiChannel.getPacketChannelOverride(packetClass, suppressWarning);
        }

    }

}
