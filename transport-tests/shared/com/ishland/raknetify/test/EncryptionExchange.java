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

package com.ishland.raknetify.test;

import com.ishland.raknetify.common.connection.FrameDataBlocker;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import network.ycc.raknet.RakNet;
import network.ycc.raknet.frame.FrameData;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;

/** Exchanges known plaintext through the real server ciphers without admitting a test account. */
public final class EncryptionExchange extends SimpleChannelInboundHandler<FrameData> {

    /** Plaintext challenge that must survive client encryption and server decryption exactly. */
    private static final long CHALLENGE = 0x5d37a91b046ce28fL;

    /** Prevents installation except through the guarded loopback fixture entry point. */
    private EncryptionExchange() {
    }

    /**
     * Finds the connection's Netty channel without depending on version-specific field names.
     *
     * @param connection native Minecraft connection with its declared Channel field
     * @return connected channel owned by Minecraft
     * @throws ReflectiveOperationException if the native field layout changes
     * @throws NoSuchFieldException if no Channel field is declared
     */
    public static Channel channel(Object connection) throws ReflectiveOperationException {
        for (Field field : connection.getClass().getDeclaredFields()) {
            if (field.getType() != Channel.class) continue;
            field.setAccessible(true);
            return (Channel) field.get(connection);
        }
        throw new NoSuchFieldException("Minecraft connection channel");
    }

    /**
     * Requires explicit test mode, a loopback RakNet peer, and the real frame encryption handler.
     * This fixture never installs replacement ciphers or alters authentication in distributed jars.
     *
     * @param channel disposable native server connection after its encryption setup
     * @throws IllegalStateException if the isolated fixture or real frame encryption is missing
     */
    public static void install(Channel channel) {
        if (!Boolean.getBoolean("raknetify.test.encryption")) throw new IllegalStateException("Test mode required");
        if (!(channel.config() instanceof RakNet.Config)) throw new IllegalStateException("RakNet required");
        InetSocketAddress peer = (InetSocketAddress) channel.remoteAddress();
        if (!peer.getAddress().isLoopbackAddress()) throw new IllegalStateException("Loopback required");
        if (channel.pipeline().get(MultiChannellingEncryption.class) == null) {
            throw new IllegalStateException("Frame encryption was not installed");
        }
        String blocker = channel.pipeline().context(FrameDataBlocker.class).name();
        channel.pipeline().addBefore(blocker, "encryption-test-exchange", new EncryptionExchange());
    }

    /**
     * Validates the decrypted challenge and replies through the real outbound cipher before closing.
     *
     * @param context pipeline context after frame decryption and before unsupported-frame disposal
     * @param frame owned inbound test frame, released by the superclass
     * @throws IllegalArgumentException if the decrypted request has an unexpected identifier or payload
     */
    @Override
    protected void channelRead0(ChannelHandlerContext context, FrameData frame) {
        if (frame.getPacketId() != 0xF7) throw new IllegalArgumentException("Unexpected test frame");
        ByteBuf bytes = frame.createData();
        try {
            bytes.skipBytes(1);
            if (bytes.readableBytes() != 8 || bytes.readLong() != CHALLENGE) {
                throw new IllegalArgumentException("Encrypted challenge mismatch");
            }
            bytes.clear().writeLong(~CHALLENGE);
            FrameData reply = FrameData.create(context.alloc(), 0xF8, bytes);
            context.writeAndFlush(reply).addListener(ChannelFutureListener.CLOSE);
            System.out.println("PASS native encrypted challenge exchange");
        } finally {
            bytes.release();
        }
    }
}
