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
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.pipeline.UserDataCodec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Performs native RSA negotiation and a bidirectional AES challenge against the isolated server fixture. */
public final class RaknetEncryptionProbe extends SimpleChannelInboundHandler<Object> {

    /** Fixed test-only AES key makes the challenge reproducible without involving an account secret. */
    private static final SecretKey KEY = new SecretKeySpec(new byte[16], "AES");

    /** Known payload checked after the server's real frame decryption. */
    private static final long CHALLENGE = 0x5d37a91b046ce28fL;

    /** Negotiated native protocol determines the historical login packet fields. */
    private final int protocol;

    /** Completes only after the encrypted server reply is decrypted and checked. */
    private final CompletableFuture<Void> result = new CompletableFuture<>();

    /**
     * Creates state owned by one probe connection.
     *
     * @param protocol native Minecraft protocol number
     */
    private RaknetEncryptionProbe(int protocol) {
        this.protocol = protocol;
    }

    /**
     * Connects only to loopback and fails if the encrypted exchange does not complete within ten seconds.
     *
     * @param args local server port and native protocol number
     * @throws Exception if transport, RSA, AES, or challenge validation fails
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        RaknetEncryptionProbe probe = new RaknetEncryptionProbe(Integer.parseInt(args[1]));
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        Channel channel = null;
        try {
            channel = new Bootstrap().group(group).channel(RakNetClient.THREADED_CHANNEL)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .handler(probe.initializer()).connect("127.0.0.1", port).sync().channel();
            channel.writeAndFlush(RaknetStatusProbe.handshake(port, probe.protocol, 2)).sync();
            channel.writeAndFlush(probe.hello()).sync();
            probe.result.get(10, TimeUnit.SECONDS);
            System.out.println("PASS native RSA and bidirectional RakNet encryption");
        } finally {
            if (channel != null) channel.close().sync();
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Installs the original single-channel game codec and the bounded login responder.
     *
     * @return initializer used for this connection only
     */
    private ChannelInitializer<Channel> initializer() {
        return new ChannelInitializer<>() {
            /**
             * Adds the unencrypted framing used before Minecraft's key exchange.
             *
             * @param channel newly registered loopback connection
             */
            @Override
            protected void initChannel(Channel channel) {
                channel.pipeline().addLast("game", new UserDataCodec(0xFD));
                channel.pipeline().addLast("probe", RaknetEncryptionProbe.this);
            }
        };
    }

    /**
     * Encodes the unsigned test identity using the fields required by this native protocol.
     *
     * @return owned login-start buffer transferred to the channel
     */
    private ByteBuf hello() {
        ByteBuf buffer = Unpooled.buffer().writeByte(0);
        byte[] name = "EncryptionProbe".getBytes(StandardCharsets.US_ASCII);
        RaknetStatusProbe.writeVarint(buffer, name.length);
        buffer.writeBytes(name);
        if (protocol >= 759 && protocol <= 760) buffer.writeBoolean(false);
        if (protocol >= 760 && protocol < 764) buffer.writeBoolean(true);
        if (protocol >= 760) buffer.writeLong(0).writeLong(1);
        return buffer;
    }

    /**
     * Responds to the native challenge or validates the post-encryption fixture reply.
     * Unsupported extension frames are released without interpretation.
     *
     * @param context connection that owns the ciphers and writes
     * @param message decoded game bytes or an extension frame
     * @throws Exception if cryptographic setup or native packet decoding fails
     */
    @Override
    protected void channelRead0(ChannelHandlerContext context, Object message) throws Exception {
        if (message instanceof ByteBuf buffer) answerKey(context, buffer);
        if (message instanceof FrameData frame && frame.getPacketId() == 0xF8) checkReply(frame);
    }

    /**
     * Writes the native unencrypted key response before enabling frame encryption on the same event loop.
     * Waiting for its RakNet acknowledgement first would race the server's encrypted reply.
     *
     * @param context connection receiving Minecraft's encryption request
     * @param request complete native encryption request without its outer framing
     * @throws Exception if the packet or RSA key is invalid
     * @throws IllegalArgumentException if the server sends an unexpected or malformed login packet
     */
    private void answerKey(ChannelHandlerContext context, ByteBuf request) throws Exception {
        if (RaknetStatusProbe.readVarint(request) != 1) throw new IllegalArgumentException("Expected encryption request");
        readBytes(request);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(readBytes(request)));
        byte[] nonce = readBytes(request);
        if (request.isReadable()) request.readBoolean();
        if (request.isReadable()) throw new IllegalArgumentException("Trailing encryption request data");
        ByteBuf reply = Unpooled.buffer().writeByte(1);
        writeBytes(reply, encrypt(key, KEY.getEncoded()));
        if (protocol >= 759 && protocol <= 760) reply.writeBoolean(true);
        writeBytes(reply, encrypt(key, nonce));
        context.writeAndFlush(reply);
        context.pipeline().addBefore("game", "encryption", new MultiChannellingEncryption(KEY));
        ByteBuf challenge = context.alloc().buffer(8).writeLong(CHALLENGE);
        try {
            context.writeAndFlush(FrameData.create(context.alloc(), 0xF7, challenge));
        } finally {
            challenge.release();
        }
    }

    /**
     * Encrypts one native login field using the server's public RSA key.
     *
     * @param key public key from Minecraft's encryption request
     * @param bytes session key or verification nonce
     * @return PKCS1-padded RSA ciphertext
     * @throws Exception if the supplied key cannot encrypt the field
     */
    private static byte[] encrypt(PublicKey key, byte[] bytes) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(bytes);
    }

    /**
     * Reads one bounded variable-length native login field.
     *
     * @param buffer packet containing the length and bytes
     * @return copied bytes, independent of the inbound packet's lifetime
     * @throws IllegalArgumentException if the field length is invalid
     */
    private static byte[] readBytes(ByteBuf buffer) {
        int length = RaknetStatusProbe.readVarint(buffer);
        if (length < 0 || length > 4096 || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid login field length");
        }
        byte[] result = new byte[length];
        buffer.readBytes(result);
        return result;
    }

    /**
     * Writes a length-prefixed native login field.
     *
     * @param buffer destination packet owned by the caller
     * @param bytes complete field contents
     */
    private static void writeBytes(ByteBuf buffer, byte[] bytes) {
        RaknetStatusProbe.writeVarint(buffer, bytes.length);
        buffer.writeBytes(bytes);
    }

    /**
     * Completes the probe only when server encryption and client decryption preserve the expected reply.
     *
     * @param frame decrypted fixture response whose payload remains owned by the inbound handler
     * @throws IllegalArgumentException if the plaintext reply differs from the expected challenge response
     */
    private void checkReply(FrameData frame) {
        ByteBuf bytes = frame.createData();
        try {
            bytes.skipBytes(1);
            if (bytes.readableBytes() != 8 || bytes.readLong() != ~CHALLENGE) {
                throw new IllegalArgumentException("Encrypted reply mismatch");
            }
            result.complete(null);
        } finally {
            bytes.release();
        }
    }

    /**
     * Fails promptly on decoder or cipher errors and closes the disposable connection.
     *
     * @param context failing connection context
     * @param cause transport, packet, or cryptographic failure
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        result.completeExceptionally(cause);
        context.close();
    }
}
