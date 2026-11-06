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
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;

import network.ycc.raknet.client.channel.RakNetClientChannel;
import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.server.channel.RakNetApplicationChannel;
import network.ycc.raknet.server.channel.RakNetChildChannel;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

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
    private static final List<String> PREVIOUS_HANDLERS =
            List.of("decrypt", "encrypt", NAME, NAME + "-inbound", NAME + "-outbound");

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

    /** Negotiated secret copied from Minecraft before its stream ciphers consume any data. */
    private final SecretKey key;

    /** Whether this handler verifies inbound frame payloads. */
    private final boolean inbound;

    /** Whether this handler authenticates outbound frame payloads. */
    private final boolean outbound;

    /** Explicit endpoint role for adapters, or null to infer it from a RakNet channel. */
    private final Boolean clientSide;

    /** Inbound payload transformer created when the handler joins a channel. */
    private AuthenticatedFrameCipher decryption;

    /** Outbound payload transformer created when the handler joins a channel. */
    private AuthenticatedFrameCipher encryption;

    /**
     * Creates authenticated frame encryption with a role determined from the native RakNet channel.
     *
     * @param key negotiated Minecraft session secret
     */
    public MultiChannellingEncryption(final SecretKey key) {
        this(key, true, true, null);
    }

    /**
     * Creates authenticated frame encryption for an adapter with a known endpoint role.
     *
     * @param key negotiated Minecraft session secret
     * @param clientSide true for a connecting client, false for an accepting server
     */
    public MultiChannellingEncryption(final SecretKey key, final boolean clientSide) {
        this(key, true, true, clientSide);
    }

    /**
     * Extracts Minecraft's shared secret without using or modifying its stream ciphers.
     *
     * @param decryption unused Minecraft inbound cipher
     * @param encryption unused Minecraft outbound cipher
     */
    public MultiChannellingEncryption(final Cipher decryption, final Cipher encryption) {
        this(FrameKeyDerivation.minecraftSecret(decryption, encryption), true, true, null);
    }

    /**
     * Retains the enabled directions until the connection role is available during installation.
     *
     * @param key Minecraft session secret used for direction-separated key derivation
     * @param inbound whether to authenticate incoming frames
     * @param outbound whether to encrypt outgoing frames
     * @param clientSide explicit endpoint role, or null for native channel inference
     */
    private MultiChannellingEncryption(
            final SecretKey key,
            final boolean inbound,
            final boolean outbound,
            final Boolean clientSide) {
        this.key = Objects.requireNonNull(key);
        this.inbound = inbound;
        this.outbound = outbound;
        this.clientSide = clientSide;
    }

    /**
     * Enables decryption only while Minecraft completes its outbound handshake transition.
     *
     * @param cipher unused Minecraft inbound cipher
     * @return handler that authenticates incoming frames only
     */
    public static MultiChannellingEncryption inbound(final Cipher cipher) {
        return new MultiChannellingEncryption(
                FrameKeyDerivation.minecraftSecret(cipher), true, false, null);
    }

    /**
     * Enables encryption only while leaving incoming handshake traffic unchanged.
     *
     * @param cipher unused Minecraft outbound cipher
     * @return handler that encrypts outgoing frames only
     */
    public static MultiChannellingEncryption outbound(final Cipher cipher) {
        return new MultiChannellingEncryption(
                FrameKeyDerivation.minecraftSecret(cipher), false, true, null);
    }

    /**
     * Initializes direction-specific keys only after the native channel identifies the endpoint
     * role.
     *
     * @param ctx newly installed connection context
     * @throws GeneralSecurityException if authenticated encryption cannot be initialized
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws GeneralSecurityException {
        try {
            boolean client = clientSide != null ? clientSide : isClient(ctx.channel());
            if (inbound) {
                decryption = new AuthenticatedFrameCipher(key, false, !client);
            }
            if (outbound) {
                encryption = new AuthenticatedFrameCipher(key, true, client);
            }
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            ctx.close();
            throw failure;
        }
    }

    /**
     * Rejects unknown channel wrappers rather than silently using the wrong direction key.
     *
     * @param channel native RakNet endpoint receiving this handler
     * @return true for a client channel and false for an accepted server channel
     * @throws IllegalArgumentException if the endpoint does not expose a recognized RakNet role
     */
    private static boolean isClient(final Channel channel) {
        if (channel instanceof RakNetClientChannel
                || channel instanceof RakNetClientThreadedChannel) {
            return true;
        }
        if (channel instanceof RakNetChildChannel || channel instanceof RakNetApplicationChannel) {
            return false;
        }
        throw new IllegalArgumentException("Cannot determine RakNet encryption endpoint role");
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
                res = ctx.alloc().buffer(data.getDataSize() + AuthenticatedFrameCipher.OVERHEAD);
                encryption.transform(data, buf, res);
                resFrame = FrameData.create(ctx.alloc(), data.getPacketId(), res);
                resFrame.setOrderChannel(data.getOrderChannel());
                resFrame.setReliability(data.getReliability());
                ctx.write(resFrame, promise);
                resFrame = null;
                return;
            } catch (GeneralSecurityException failure) {
                ctx.close();
                throw failure;
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
                if (!decryption.transform(data, buf, res)) {
                    return;
                }
                resFrame = FrameData.create(ctx.alloc(), data.getPacketId(), res);
                resFrame.setOrderChannel(data.getOrderChannel());
                resFrame.setReliability(data.getReliability());
                ctx.fireChannelRead(resFrame);
                resFrame = null;
                return;
            } catch (GeneralSecurityException failure) {
                ctx.close();
                throw failure;
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
