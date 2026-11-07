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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.test.NativeLoginPacket;
import com.ishland.raknetify.test.TransportTestPayload;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.client.RakNetClient;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.pipeline.UserDataCodec;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Performs native RSA negotiation and a bidirectional AES challenge against the isolated server
 * fixture.
 */
public final class RaknetEncryptionProbe extends SimpleChannelInboundHandler<Object> {

    /**
     * Fixed test-only AES key makes the challenge reproducible without involving an account secret.
     */
    private static final SecretKey KEY = new SecretKeySpec(new byte[16], "AES");

    /**
     * Exact supported snapshot protocol with an options acknowledgement before cipher installation.
     */
    private static final int OPTIONS_PROTOCOL = 1073742169;

    /** Known payload checked after the server's real frame decryption. */
    private static final long CHALLENGE = 0x5d37a91b046ce28fL;

    /** Negotiated native protocol determines the historical login packet fields. */
    private final int protocol;

    /** Message size used to exercise either ordinary or fragmented encrypted frames. */
    private final int payloadSize;

    /** Expected native outcome: fixture echo, rejected session, or rejected nonce. */
    private final String mode;

    /** Distinguishes the initial encryption request from the later native rejection packet. */
    private boolean answered;

    /** Completes only after the encrypted server reply is decrypted and checked. */
    private final CompletableFuture<Void> result = new CompletableFuture<>();

    /**
     * Creates state owned by one probe connection.
     *
     * @param protocol native Minecraft protocol number
     * @param payloadSize bounded plaintext challenge length
     * @param mode expected echo or native authentication rejection
     */
    private RaknetEncryptionProbe(final int protocol, final int payloadSize, final String mode) {
        this.protocol = protocol;
        this.payloadSize = payloadSize;
        this.mode = mode;
    }

    /**
     * Connects only to loopback and fails if the encrypted exchange does not complete within thirty
     * seconds.
     *
     * @param args local port, native protocol, optional payload size, and optional echo or
     *     authentication rejection mode
     * @throws Exception if transport, RSA, AES, or challenge validation fails
     * @throws IllegalArgumentException if the requested verification mode is unknown
     * @throws IllegalStateException if the owned probe channel cannot close within five seconds
     */
    public static void main(final String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String mode = args.length > 3 ? args[3] : "echo";
        if (!java.util.Set.of("echo", "rejected-session", "rejected-nonce", "rejected-throttle")
                .contains(mode)) {
            throw new IllegalArgumentException("Unknown encryption verification mode");
        }
        RaknetEncryptionProbe probe =
                new RaknetEncryptionProbe(
                        Integer.parseInt(args[1]),
                        args.length > 2 ? Integer.parseInt(args[2]) : 8,
                        mode);
        try (ProbeLoop loop = new ProbeLoop(new NioEventLoopGroup(1))) {
            final Channel connected =
                    new Bootstrap()
                            .group(loop.group())
                            .channel(RakNetClient.THREADED_CHANNEL)
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                            .handler(probe.initializer())
                            .connect("127.0.0.1", port)
                            .sync()
                            .channel();
            try (ProbeChannel owned = new ProbeChannel(connected)) {
                final Channel channel = owned.channel();
                channel.writeAndFlush(RaknetStatusProbe.handshake(port, probe.protocol, 2)).sync();
                if (!"rejected-throttle".equals(mode)) {
                    channel.writeAndFlush(probe.hello()).sync();
                }
                probe.result.get(30, TimeUnit.SECONDS);
                System.out.println(
                        "echo".equals(mode)
                                ? "PASS native RSA and bidirectional RakNet encryption"
                                : "PASS native authentication " + mode);
            }
        }
    }

    /**
     * Installs the original single-channel game codec and the bounded login responder.
     *
     * @return initializer used for this connection only
     */
    private ChannelInitializer<Channel> initializer() {
        return new com.ishland.raknetify.test.ChannelSetup(channel -> {
            ((RakNet.Config) channel.config()).setMaxQueuedBytes(Constants.MAX_QUEUED_SIZE);
            // Metrics are transport control traffic handled before application encryption.
            channel.pipeline().addLast("metrics", new MetricsSynchronizationHandler());
            channel.pipeline().addLast("game", new UserDataCodec(0xFD));
            channel.pipeline().addLast("probe", RaknetEncryptionProbe.this);
        });
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
        if (protocol >= 759 && protocol <= 760) {
            buffer.writeBoolean(false);
        }
        if (protocol >= 760 && protocol < 764) {
            buffer.writeBoolean(true);
        }
        if (protocol >= 760) {
            buffer.writeLong(0).writeLong(1);
        }
        return buffer;
    }

    /**
     * Responds to the native challenge or validates the post-encryption fixture reply. Unsupported
     * extension frames are released without interpretation.
     *
     * @param context connection that owns the ciphers and writes
     * @param message decoded game bytes or an extension frame
     * @throws Exception if cryptographic setup or native packet decoding fails
     */
    @Override
    protected void channelRead0(final ChannelHandlerContext context, final Object message)
            throws Exception {
        if (message instanceof ByteBuf buffer) {
            if ("rejected-throttle".equals(mode)) {
                checkThrottle(buffer);
            } else if (answered && !"echo".equals(mode)) {
                checkRejection(buffer);
            } else if (answered && protocol == OPTIONS_PROTOCOL) {
                answerOptions(context, buffer);
            } else {
                answerKey(context, buffer);
            }
        }
        if (message instanceof FrameData frame && frame.getPacketId() == 0xF8) {
            checkReply(frame);
        }
    }

    /**
     * Writes the native unencrypted key response before enabling frame encryption on the same event
     * loop. Waiting for its RakNet acknowledgement first would race the server's encrypted reply.
     *
     * @param context connection receiving Minecraft's encryption request
     * @param request complete native encryption request without its outer framing
     * @throws Exception if the packet or RSA key is invalid
     * @throws IllegalArgumentException if the server sends an unexpected or malformed login packet
     */
    private void answerKey(final ChannelHandlerContext context, final ByteBuf request)
            throws Exception {
        if (RaknetStatusProbe.readVarint(request) != 1) {
            throw new IllegalArgumentException("Expected encryption request");
        }
        readBytes(request);
        PublicKey key =
                KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(readBytes(request)));
        byte[] nonce = readBytes(request);
        if ("rejected-nonce".equals(mode)) {
            nonce[0] ^= 1;
        }
        if (request.isReadable()) {
            request.readBoolean();
        }
        if (request.isReadable()) {
            throw new IllegalArgumentException("Trailing encryption request data");
        }
        ByteBuf reply = Unpooled.buffer().writeByte(1);
        try {
            NativeLoginPacket.write(reply, KEY, key, nonce);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reply.release();
            throw failure;
        }
        context.writeAndFlush(reply)
                .addListener(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        answered = true;
        if ("rejected-nonce".equals(mode)) {
            return;
        }
        if (protocol != OPTIONS_PROTOCOL) {
            enableEncryption(context);
        }
    }

    /**
     * Acknowledges the snapshot's plaintext options request before installing encrypted framing.
     *
     * @param context native connection waiting for the options acknowledgement
     * @param request decoded options packet with its original packet identifier
     * @throws IllegalArgumentException if the request does not enable encryption alone
     */
    private void answerOptions(final ChannelHandlerContext context, final ByteBuf request) {
        if (RaknetStatusProbe.readVarint(request) != 6
                || request.readUnsignedByte() != 1
                || request.isReadable()) {
            throw new IllegalArgumentException("Expected encryption options request");
        }
        context.writeAndFlush(Unpooled.buffer(1).writeByte(5))
                .addListener(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        enableEncryption(context);
    }

    /**
     * Installs the real client frame cipher and sends the bounded encrypted witness payload.
     *
     * @param context connection whose plaintext key or options response has already been written
     */
    private void enableEncryption(final ChannelHandlerContext context) {
        context.pipeline().addBefore("game", "encryption", new MultiChannellingEncryption(KEY));
        if ("rejected-session".equals(mode)) {
            return;
        }
        ByteBuf challenge = TransportTestPayload.create(context.alloc(), payloadSize, CHALLENGE);
        try {
            context.writeAndFlush(FrameData.create(context.alloc(), 0xF7, challenge))
                    .addListener(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        } finally {
            challenge.release();
        }
    }

    /**
     * Requires a native login disconnect after the expected plaintext or encrypted key-exchange
     * outcome.
     *
     * @param packet decoded login packet whose frame cipher has already been applied when
     *     appropriate
     * @throws IllegalArgumentException if the server admits the probe or sends a malformed
     *     disconnect
     */
    private void checkRejection(final ByteBuf packet) {
        if (RaknetStatusProbe.readVarint(packet) != 0 || !packet.isReadable()) {
            throw new IllegalArgumentException("Expected a native login rejection");
        }
        result.complete(null);
    }

    /**
     * Requires the native reconnect throttle to reject the forwarded identity before key exchange.
     *
     * @param packet first login response without transport framing
     * @throws IllegalArgumentException if the native reason is absent or another login packet was
     *     returned
     */
    private void checkThrottle(final ByteBuf packet) {
        if (!packet.toString(StandardCharsets.UTF_8).contains("Connection throttled")) {
            throw new IllegalArgumentException("Expected native connection throttle rejection");
        }
        checkRejection(packet);
    }

    /**
     * Reads one bounded variable-length native login field.
     *
     * @param buffer packet containing the length and bytes
     * @return copied bytes, independent of the inbound packet's lifetime
     * @throws IllegalArgumentException if the field length is invalid
     */
    private static byte[] readBytes(final ByteBuf buffer) {
        int length = RaknetStatusProbe.readVarint(buffer);
        if (length < 0 || length > 4096 || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("Invalid login field length");
        }
        byte[] result = new byte[length];
        buffer.readBytes(result);
        return result;
    }

    /**
     * Completes the probe only when server encryption and client decryption preserve the expected
     * reply.
     *
     * @param frame decrypted fixture response whose payload remains owned by the inbound handler
     * @throws IllegalArgumentException if the plaintext reply differs from the expected challenge
     *     response
     */
    private void checkReply(final FrameData frame) {
        ByteBuf bytes = frame.createData();
        try {
            bytes.skipBytes(1);
            if (bytes.readableBytes() != payloadSize) {
                throw new IllegalArgumentException("Encrypted reply mismatch");
            }
            TransportTestPayload.verify(bytes, ~CHALLENGE);
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
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
        result.completeExceptionally(cause);
        context.close()
                .addListener(io.netty.channel.ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
    }

    /**
     * Owns event-loop shutdown so cleanup failures are suppressed onto an earlier probe failure.
     *
     * @param group dedicated probe threads, never shared with a running game
     */
    private record ProbeLoop(NioEventLoopGroup group) implements AutoCloseable {
        /**
         * Waits for termination after all channel cleanup has completed or failed.
         *
         * @throws InterruptedException if the controlling thread is interrupted during shutdown
         */
        @Override
        public void close() throws InterruptedException {
            group.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Bounds socket cleanup without replacing a failure from the encrypted exchange.
     *
     * @param channel exclusively owned connected probe transport
     */
    private record ProbeChannel(Channel channel) implements AutoCloseable {
        /**
         * Requires the application channel to close within the fixture's shutdown budget.
         *
         * @throws InterruptedException if the shutdown wait is interrupted
         * @throws IllegalStateException if the transport does not close within five seconds
         */
        @Override
        public void close() throws InterruptedException {
            if (!channel.close().await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Encryption probe channel did not close within five seconds");
            }
        }
    }
}
