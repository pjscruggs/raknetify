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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Exercises proxy status, pong and early login rejection over a real RakNet connection. */
public final class RaknetProxyProbe {

    /** Fixed nonce that must survive the proxy's Minecraft ping round trip unchanged. */
    private static final long NONCE = 0x1020304050607080L;

    /** Prevents construction of the command-line transport witness. */
    private RaknetProxyProbe() {
    }

    /**
     * Queries a loopback proxy and fails if it omits or corrupts the requested response.
     *
     * @param args port, protocol number and status, reject, ban or ban-drop
     * @throws Exception if transport setup, response validation or bounded shutdown fails
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int protocol = Integer.parseInt(args[1]);
        String mode = args[2];
        LinkedBlockingQueue<byte[]> packets = new LinkedBlockingQueue<>(16);
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = null;
        try {
            channel = new Bootstrap().group(group).channel(RakNetClient.THREADED_CHANNEL)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(initializer(packets)).connect("127.0.0.1", port).sync().channel();
            if (mode.equals("ban-drop")) installLoss(channel);
            channel.writeAndFlush(RaknetStatusProbe.handshake(port, protocol, mode.equals("status") ? 1 : 2)).sync();
            if (mode.startsWith("ban")) {
                bannedLogin(channel, packets, protocol);
            } else if (mode.equals("reject")) {
                rejection(channel, packets);
            } else {
                status(channel, packets);
            }
        } finally {
            if (channel != null) channel.close().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Drops exactly one fragmented application datagram before reliability processing can acknowledge it.
     * @param channel connected threaded client whose parent owns the RakNet reliability pipeline
     * @throws InterruptedException if installing the deterministic impairment is interrupted
     */
    private static void installLoss(Channel channel) throws InterruptedException {
        Channel transport = channel.parent();
        transport.eventLoop().submit(() -> transport.pipeline().addBefore(
                network.ycc.raknet.pipeline.ReliabilityHandler.NAME, "fixture-loss", new DropFirstFragment())).sync();
    }

    /**
     * Uses vanilla's banned-player login path and requires the complete server-supplied reason over RakNet.
     * @param channel connected application channel
     * @param packets decoded Minecraft packet queue
     * @param protocol server protocol, used for the pre-1.20.2 optional UUID field
     * @throws Exception if login framing or rejection delivery fails
     * @throws IllegalArgumentException if the expected native ban reason is missing
     */
    private static void bannedLogin(Channel channel, LinkedBlockingQueue<byte[]> packets, int protocol) throws Exception {
        ByteBuf hello = Unpooled.buffer();
        hello.writeByte(0);
        byte[] name = "DisconnectProbe".getBytes(StandardCharsets.UTF_8);
        RaknetStatusProbe.writeVarint(hello, name.length);
        hello.writeBytes(name);
        if (protocol < 764) hello.writeBoolean(true);
        hello.writeLong(0x1234567812345678L).writeLong(0x1234567812345678L);
        channel.writeAndFlush(hello).sync();
        String reason = textPacket(next(packets));
        if (!reason.contains("Transport rejection fixture") || !reason.contains("END_OF_REASON")) {
            throw new IllegalArgumentException("Missing or truncated native ban reason");
        }
        System.out.println("BAN_REASON_OK");
    }

    /**
     * Copies only application frames into a bounded queue and releases inbound reference counts.
     *
     * @param packets response queue consumed by the probe thread
     * @return pipeline initializer for this connection
     */
    private static ChannelInitializer<Channel> initializer(LinkedBlockingQueue<byte[]> packets) {
        return new ChannelInitializer<>() {
            /**
             * Installs legacy RakNet game framing without multichannel negotiation.
             *
             * @param channel newly accepted client channel
             */
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast(new UserDataCodec(0xFD));
                channel.pipeline().addLast(new SimpleChannelInboundHandler<Object>() {
                    /**
                     * Retains packet bytes independently of Netty's message lifetime.
                     *
                     * @param context inbound transport context
                     * @param message game bytes or an ignored extension frame
                     */
                    @Override
                    protected void channelRead0(ChannelHandlerContext context, Object message) {
                        if (message instanceof ByteBuf buffer) {
                            byte[] copy = new byte[buffer.readableBytes()];
                            buffer.readBytes(copy);
                            packets.add(copy);
                        }
                    }
                });
            }
        };
    }

    /**
     * Requires status JSON followed by an exact pong on the same connected session.
     *
     * @param channel connected RakNet client
     * @param packets decoded application responses
     * @throws Exception if either response is absent, malformed or has the wrong nonce
     * @throws IllegalArgumentException if the pong identifier, length or nonce differs
     */
    private static void status(Channel channel, LinkedBlockingQueue<byte[]> packets) throws Exception {
        channel.writeAndFlush(Unpooled.buffer(1).writeByte(0)).sync();
        System.out.println(textPacket(next(packets)));
        channel.writeAndFlush(Unpooled.buffer(9).writeByte(1).writeLong(NONCE)).sync();
        ByteBuf pong = Unpooled.wrappedBuffer(next(packets));
        try {
            if (pong.readableBytes() != 9 || pong.readByte() != 1 || pong.readLong() != NONCE) {
                throw new IllegalArgumentException("Incorrect Minecraft pong");
            }
        } finally {
            pong.release();
        }
        System.out.println("PONG_OK");
    }

    /**
     * Requires a valid login disconnect carrying the maintenance message before any login start.
     *
     * @param channel client rejected by the proxy handshake handler
     * @param packets decoded application responses
     * @throws Exception if rejection is missing or malformed
     * @throws IllegalArgumentException if the configured maintenance reason was not received
     */
    private static void rejection(Channel channel, LinkedBlockingQueue<byte[]> packets) throws Exception {
        byte[] response = next(packets);
        if (!textPacket(response).contains("Transport maintenance fixture")) {
            throw new IllegalArgumentException("Missing maintenance rejection");
        }
        System.out.println("REJECTED");
    }

    /**
     * Waits for one complete application packet without allowing a missing response to pass.
     *
     * @param packets received Minecraft frames
     * @return next frame owned by this caller
     * @throws InterruptedException if the test thread is interrupted
     * @throws IllegalStateException if no packet arrives within the fixed deadline
     */
    private static byte[] next(LinkedBlockingQueue<byte[]> packets) throws InterruptedException {
        byte[] response = packets.poll(10, TimeUnit.SECONDS);
        if (response == null) throw new IllegalStateException("Proxy response timed out");
        return response;
    }

    /**
     * Decodes a status packet and rejects TCP length prefixes or trailing bytes inside a RakNet frame.
     *
     * @param bytes complete Minecraft response frame
     * @return exact UTF-8 JSON supplied by the proxy
     * @throws IllegalArgumentException if the response ID or string boundary is invalid
     */
    private static String textPacket(byte[] bytes) {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            if (RaknetStatusProbe.readVarint(buffer) != 0) throw new IllegalArgumentException("Wrong response ID");
            int length = RaknetStatusProbe.readVarint(buffer);
            if (length != buffer.readableBytes()) throw new IllegalArgumentException("Incorrect string boundary");
            return buffer.readCharSequence(length, StandardCharsets.UTF_8).toString();
        } finally {
            buffer.release();
        }
    }
}
