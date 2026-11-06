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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import network.ycc.raknet.frame.FrameData;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Encrypts or decrypts individual RakNet frame payloads while preserving routing metadata. Each
 * direction may be enabled independently during login. Nonframe messages and disabled directions
 * pass through without transformation.
 */
public class MultiChannellingEncryption extends ChannelDuplexHandler {

    /**
     * Pipeline name used to install frame encryption after Minecraft negotiates its session key.
     */
    public static final String NAME = "raknetify-multichannel-encryption";

    /** Stream and frame handlers replaced independently when encryption is installed again. */
    private static final List<String> PREVIOUS_HANDLERS = List.of("decrypt", "encrypt", NAME);

    /**
     * Removes each previous cipher independently, including when only one stream direction exists.
     *
     * @param pipeline connection pipeline about to receive frame encryption
     */
    public static void removePreviousHandlers(final ChannelPipeline pipeline) {
        for (String name : PREVIOUS_HANDLERS) {
            if (pipeline.context(name) == null) {
                continue;
            }
            try {
                pipeline.remove(name);
            } catch (NoSuchElementException removed) {
                // A concurrent cleanup already removed this optional handler after the lookup.
            }
        }
    }

    /** Inbound payload transformer, or null while decryption is not enabled. */
    private final PacketEncryptionManager decryption;

    /** Outbound payload transformer, or null while encryption is not enabled. */
    private final PacketEncryptionManager encryption;

    /**
     * Creates matching AES/CFB8 payload transformers from Minecraft's shared session key.
     *
     * @param key negotiated session key also used as the initial cipher IV
     * @throws GeneralSecurityException if the cipher is unavailable or rejects the key
     */
    public MultiChannellingEncryption(final SecretKey key) throws GeneralSecurityException {
        Cipher decryption = Cipher.getInstance("AES/CFB8/NoPadding");
        decryption.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
        this.decryption = new PacketEncryptionManager(decryption);
        Cipher encryption = Cipher.getInstance("AES/CFB8/NoPadding");
        encryption.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
        this.encryption = new PacketEncryptionManager(encryption);
    }

    /**
     * Wraps separately initialized ciphers for both directions of the connection.
     *
     * @param decryption initialized inbound cipher owned by this handler
     * @param encryption initialized outbound cipher owned by this handler
     */
    public MultiChannellingEncryption(final Cipher decryption, final Cipher encryption) {
        this.decryption = new PacketEncryptionManager(Objects.requireNonNull(decryption));
        this.encryption = new PacketEncryptionManager(Objects.requireNonNull(encryption));
    }

    // A null manager passes that direction through while the handshake enables the other direction
    // independently.
    /**
     * Creates a handler whose disabled direction passes frames through unchanged.
     *
     * @param decryption inbound transformer, or null to leave incoming frames unchanged
     * @param encryption outbound transformer, or null to leave outgoing frames unchanged
     */
    private MultiChannellingEncryption(
            final PacketEncryptionManager decryption, final PacketEncryptionManager encryption) {
        this.decryption = decryption;
        this.encryption = encryption;
    }

    /**
     * Enables decryption only, leaving outgoing frames unchanged during handshake transitions.
     *
     * @param cipher initialized inbound cipher owned by the new handler
     * @return handler that transforms incoming frame payloads only
     */
    public static MultiChannellingEncryption inbound(final Cipher cipher) {
        return new MultiChannellingEncryption(
                new PacketEncryptionManager(Objects.requireNonNull(cipher)), null);
    }

    /**
     * Enables encryption only, leaving incoming frames unchanged during handshake transitions.
     *
     * @param cipher initialized outbound cipher owned by the new handler
     * @return handler that transforms outgoing frame payloads only
     */
    public static MultiChannellingEncryption outbound(final Cipher cipher) {
        return new MultiChannellingEncryption(
                null, new PacketEncryptionManager(Objects.requireNonNull(cipher)));
    }

    /**
     * Encrypts an outgoing frame payload and transfers its routing policy and promise to the
     * replacement. The input frame and temporary buffers are released on success and failure.
     *
     * @param ctx outbound context allocating and forwarding the replacement frame
     * @param msg outgoing frame or unrelated pipeline message
     * @param promise completion notification for the outgoing write
     * @throws Exception if encryption or superclass forwarding fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        if (encryption != null && msg instanceof FrameData data) {
            data.touch();
            final ByteBuf buf = data.createData().skipBytes(1);
            ByteBuf res = null;
            FrameData resFrame = null;
            try {
                res = ctx.alloc().buffer(data.getDataSize());
                encryption.doWork(buf, res);
                resFrame = FrameData.create(ctx.alloc(), data.getPacketId(), res);
                //                res = null;
                resFrame.setOrderChannel(data.getOrderChannel());
                resFrame.setReliability(data.getReliability());
                ctx.write(resFrame, promise);
                resFrame = null;
                return;
            } finally {
                buf.release();
                data.release();
                if (res != null) {
                    res.release();
                }
                if (resFrame != null) {
                    resFrame.release();
                }
            }
        }
        super.write(ctx, msg, promise);
    }

    /**
     * Decrypts an incoming frame payload without changing its identifier, channel, or reliability.
     * The input frame and temporary buffers are released on success and failure.
     *
     * @param ctx inbound context receiving the decrypted replacement
     * @param msg incoming frame or unrelated pipeline message
     * @throws Exception if decryption or superclass forwarding fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (decryption != null && msg instanceof FrameData data) {
            data.touch();
            final ByteBuf buf = data.createData().skipBytes(1);
            ByteBuf res = null;
            FrameData resFrame = null;
            try {
                res = ctx.alloc().buffer(data.getDataSize());
                decryption.doWork(buf, res);
                resFrame = FrameData.create(ctx.alloc(), data.getPacketId(), res);
                resFrame.setOrderChannel(data.getOrderChannel());
                resFrame.setReliability(data.getReliability());
                ctx.fireChannelRead(resFrame);
                resFrame = null;
                return;
            } finally {
                buf.release();
                data.release();
                if (res != null) {
                    res.release();
                }
                if (resFrame != null) {
                    resFrame.release();
                }
            }
        }
        super.channelRead(ctx, msg);
    }
}
