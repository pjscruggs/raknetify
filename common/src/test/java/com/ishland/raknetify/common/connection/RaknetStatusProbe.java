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

package com.ishland.raknetify.common.connection;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;

import network.ycc.raknet.client.RakNetClient;
import network.ycc.raknet.pipeline.UserDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Queries Minecraft status over a connected RakNet session using the original single-channel wire
 * format.
 */
public final class RaknetStatusProbe {

    /** Prevents construction of the command-line integration probe. */
    private RaknetStatusProbe() {}

    /**
     * Connects to a disposable loopback server and writes UTF-8 status JSON after receiving a game
     * frame.
     *
     * @param args local server port and Minecraft protocol number
     * @throws Exception if connection, status decoding, or bounded shutdown fails
     */
    public static void main(final String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int protocol = Integer.parseInt(args[1]);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = null;
        CompletableFuture<String> response = new CompletableFuture<>();
        try {
            channel =
                    new Bootstrap()
                            .group(group)
                            .channel(RakNetClient.THREADED_CHANNEL)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                            .handler(initializer(response))
                            .connect("127.0.0.1", port)
                            .sync()
                            .channel();
            channel.writeAndFlush(handshake(port, protocol, 1)).sync();
            channel.writeAndFlush(Unpooled.buffer(1).writeByte(0)).sync();
            System.out.write(
                    (response.get(10, TimeUnit.SECONDS) + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        } finally {
            if (channel != null) {
                channel.close().sync();
            }
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Builds the minimal legacy framing pipeline without negotiating compression or multiple
     * channels.
     *
     * @param response destination for the first Minecraft status response or pipeline error
     * @return initializer shared by no other connection
     */
    private static ChannelInitializer<Channel> initializer(
            final CompletableFuture<String> response) {
        return new ChannelInitializer<>() {
            /**
             * Installs game-frame decoding and consumes unsupported extension frames safely.
             *
             * @param channel newly registered probe connection
             */
            @Override
            protected void initChannel(final Channel channel) {
                channel.pipeline().addLast(new UserDataCodec(0xFD));
                channel.pipeline()
                        .addLast(
                                new SimpleChannelInboundHandler<Object>() {
                                    /**
                                     * Accepts the status payload while automatically releasing
                                     * every inbound message.
                                     *
                                     * @param context inbound pipeline context
                                     * @param message decoded Minecraft bytes or an unsupported
                                     *     extension frame
                                     */
                                    @Override
                                    protected void channelRead0(
                                            final ChannelHandlerContext context,
                                            final Object message) {
                                        if (message instanceof ByteBuf buffer) {
                                            response.complete(readStatus(buffer));
                                        }
                                    }

                                    /**
                                     * Reports protocol failures immediately instead of waiting for
                                     * the response deadline.
                                     *
                                     * @param context failing connection context
                                     * @param cause decode or transport failure
                                     */
                                    @Override
                                    public void exceptionCaught(
                                            final ChannelHandlerContext context,
                                            final Throwable cause) {
                                        response.completeExceptionally(cause);
                                        context.close();
                                    }
                                });
            }
        };
    }

    /**
     * Encodes the Minecraft intention packet without TCP's outer length prefix.
     *
     * @param port loopback server port
     * @param protocol native Minecraft protocol number
     * @param intention status or login connection state
     * @return owned buffer transferred to the channel by the caller
     */
    static ByteBuf handshake(final int port, final int protocol, final int intention) {
        ByteBuf buffer = Unpooled.buffer();
        writeVarint(buffer, 0);
        writeVarint(buffer, protocol);
        byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        writeVarint(buffer, host.length);
        buffer.writeBytes(host).writeShort(port).writeByte(intention);
        return buffer;
    }

    /**
     * Encodes all 32 bits, including snapshot protocol identifiers, as a Minecraft VarInt.
     *
     * @param buffer destination packet buffer
     * @param value protocol integer to encode
     */
    static void writeVarint(final ByteBuf buffer, final int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            buffer.writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer.writeByte(remaining);
    }

    /**
     * Reads a bounded Minecraft VarInt without accepting malformed continuation chains.
     *
     * @param buffer packet whose reader index advances past the integer
     * @return decoded integer
     * @throws IllegalArgumentException if the integer exceeds five bytes
     */
    static int readVarint(final ByteBuf buffer) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int part = buffer.readUnsignedByte();
            value |= (part & 0x7F) << shift;
            if ((part & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("Oversized VarInt");
    }

    /**
     * Checks the status packet identifier and exact string length before decoding its UTF-8 JSON.
     *
     * @param buffer received Minecraft packet without its RakNet frame identifier
     * @return status JSON for validation by the version-aware server test runner
     * @throws IllegalArgumentException if the payload is not a complete status response
     */
    private static String readStatus(final ByteBuf buffer) {
        if (readVarint(buffer) != 0) {
            throw new IllegalArgumentException("Unexpected status packet");
        }
        int length = readVarint(buffer);
        if (length < 0 || length != buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid status string length");
        }
        return buffer.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }
}
