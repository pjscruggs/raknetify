/*
 * This file is a part of the Velocity implementation of the Raknetify
 * project, licensed under GPLv3.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ishland.raknetify.velocity.connection;

import com.google.common.base.Preconditions;
import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.SynchronizationLayer;
import com.ishland.raknetify.common.util.MathUtil;
import com.ishland.raknetify.velocity.RaknetifyVelocityPlugin;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.client.HandshakeSessionHandler;
import com.velocitypowered.proxy.crypto.EncryptionUtils;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.VelocityConnectionEvent;
import com.velocitypowered.proxy.protocol.packet.AvailableCommandsPacket;
import com.velocitypowered.proxy.protocol.packet.EncryptionResponsePacket;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import com.velocitypowered.proxy.protocol.packet.SetCompressionPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.config.StartUpdatePacket;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToMessageEncoder;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Adapts Velocity login, configuration, and world transitions to RakNet frame processing. */
public class RakNetVelocityChannelEventListener extends ChannelDuplexHandler {

    /** Pipeline name for Velocity's client-facing transport lifecycle handler. */
    public static final String NAME = "raknetify-velocity-event-listener";

    /** Captured client login secret used when Velocity reports that encryption has been enabled. */
    private @Nullable SecretKey encryptionKey;

    /**
     * Suppresses redundant vanilla compression and synchronizes transport ordering at phase
     * boundaries. The available-command packet triggers multichannel negotiation before play
     * traffic follows.
     *
     * @param ctx client-facing outbound context
     * @param msg decoded Minecraft packet sent by the proxy
     * @param promise write completion notification, completed locally for a suppressed compression
     *     packet
     * @throws Exception if superclass outbound forwarding fails
     */
    @Override
    public void write(
            final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        if (msg instanceof SetCompressionPacket) {
            final MultiChannelingStreamingCompression compression =
                    ctx.channel().pipeline().get(MultiChannelingStreamingCompression.class);
            if (compression != null && compression.isActive()) {
                RaknetifyVelocityPlugin.LOGGER.info(
                        "Preventing vanilla compression as streaming compression is enabled");
                promise.setSuccess(); // swallow SetCompression packet
                return;
            }
        } else if (msg instanceof RespawnPacket
                || msg instanceof JoinGamePacket
                || msg instanceof StartUpdatePacket
                || msg instanceof FinishedUpdatePacket) {
            ctx.write(SynchronizationLayer.SYNC_REQUEST_OBJECT)
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            super.write(ctx, msg, promise);
            return;
        } else if (msg instanceof AvailableCommandsPacket) {
            ctx.write(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL)
                    .addListener(RakNet.INTERNAL_WRITE_LISTENER);
            super.write(ctx, msg, promise);
            return;
        }
        super.write(ctx, msg, promise);
    }

    /**
     * Captures the encrypted shared secret before Velocity processes the client's login response.
     * Authentication remains the proxy's responsibility. Capture failures are logged and forwarding
     * continues.
     *
     * @param ctx client-facing inbound context
     * @param msg decoded encryption response or other incoming message
     * @throws Exception if superclass inbound forwarding fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof HandshakePacket packet) {
            adaptFastMOTD(ctx, packet);
        }
        if (msg instanceof EncryptionResponsePacket packet) {
            try {
                byte[] secret =
                        EncryptionUtils.decryptRsa(
                                ((VelocityServer) RaknetifyVelocityPlugin.proxy())
                                        .getServerKeyPair(),
                                packet.getSharedSecret());
                this.encryptionKey = new SecretKeySpec(secret, "AES");
            } catch (Throwable t) {
                RaknetifyVelocityPlugin.LOGGER.warn(
                        "Failed to decrypt captured encryption secret, the raknetify connection is"
                                + " broken",
                        t);
            }
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Uses Velocity's normal status encoder when FastMOTD would otherwise send preframed TCP bytes.
     * FastMOTD's ProxyPingEvent listener still supplies its configured MOTD and player sample.
     * Login still uses FastMOTD's admission handler, with framing corrected for its prepared
     * rejection.
     *
     * @param ctx RakNet connection whose status handshake has not yet reached the session handler
     * @param packet intention packet identifying status or login
     * @throws ReflectiveOperationException if the detected FastMOTD hook no longer exposes its
     *     delegate
     * @throws IllegalStateException if the hook contains an unexpected delegate type
     */
    private static void adaptFastMOTD(final ChannelHandlerContext ctx, final HandshakePacket packet)
            throws ReflectiveOperationException {
        MinecraftConnection connection = ctx.pipeline().get(MinecraftConnection.class);
        if (connection == null) {
            return;
        }
        Object handler = connection.getActiveSessionHandler();
        if (handler == null
                || !"net.elytrium.fastmotd.injection.HandshakeSessionHandlerHook"
                        .equals(handler.getClass().getName())) {
            return;
        }
        if (packet.getNextStatus() == StateRegistry.LOGIN_ID) {
            ctx.pipeline()
                    .addBefore(
                            Connections.MINECRAFT_ENCODER,
                            "raknetify-fastmotd-disconnect",
                            new PreparedDisconnectEncoder());
            return;
        }
        if (packet.getNextStatus() != StateRegistry.STATUS_ID) {
            return;
        }
        Field original = handler.getClass().getDeclaredField("original");
        original.setAccessible(true);
        Object delegate = original.get(handler);
        if (!(delegate instanceof HandshakeSessionHandler handshake)) {
            throw new IllegalStateException("FastMOTD status handler has an unexpected delegate");
        }
        connection.setActiveSessionHandler(connection.getState(), handshake);
    }

    /** Removes TCP framing only from FastMOTD's prepared maintenance disconnect packet. */
    private static final class PreparedDisconnectEncoder extends MessageToMessageEncoder<ByteBuf> {

        /**
         * Keeps ordinary login traffic unchanged unless FastMOTD installed its prepared-packet
         * encoder. A retained slice gives RakNet ownership without modifying FastMOTD's cached
         * buffer indices.
         *
         * @param ctx outbound context before Velocity's Minecraft packet encoder
         * @param buffer encoded login bytes, released by MessageToMessageEncoder
         * @param output packet bodies passed to RakNet without an outer TCP length
         * @throws IllegalArgumentException if the prepared payload is not one complete disconnect
         *     packet
         */
        @Override
        protected void encode(
                final ChannelHandlerContext ctx, final ByteBuf buffer, final List<Object> output) {
            Object prepared = ctx.pipeline().get("fastprepare-encoder");
            if (prepared == null
                    || !"net.elytrium.fastmotd.thirdparty.fastprepare.handler.PreparedPacketEncoder"
                            .equals(prepared.getClass().getName())) {
                output.add(buffer.retain());
                return;
            }
            ByteBuf view = buffer.duplicate();
            int length = MathUtil.readVarInt(view);
            if (length != view.readableBytes()
                    || length < 2
                    || view.getUnsignedByte(view.readerIndex()) != 0) {
                throw new IllegalArgumentException(
                        "Unexpected FastMOTD prepared disconnect framing");
            }
            output.add(view.readRetainedSlice(length));
        }
    }

    /**
     * Reconciles Velocity codec changes with RakNet compression, framing, and per-frame encryption.
     *
     * @param ctx client-facing context whose pipeline is being updated
     * @param evt Velocity connection event describing a codec-state transition
     * @throws Exception if event forwarding or frame-encryption initialization fails
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt)
            throws Exception {
        super.userEventTriggered(ctx, evt);
        if (evt == VelocityConnectionEvent.COMPRESSION_ENABLED) {
            final MultiChannelingStreamingCompression compression =
                    ctx.channel().pipeline().get(MultiChannelingStreamingCompression.class);
            if (compression != null && compression.isActive()) {
                RaknetifyVelocityPlugin.LOGGER.info(
                        "Preventing vanilla compression as streaming compression is enabled");
                ctx.channel()
                        .pipeline()
                        .replace(
                                Connections.COMPRESSION_ENCODER,
                                Connections.COMPRESSION_ENCODER,
                                new ChannelDuplexHandler()); // no-op
                ctx.channel()
                        .pipeline()
                        .replace(
                                Connections.COMPRESSION_DECODER,
                                Connections.COMPRESSION_DECODER,
                                new ChannelDuplexHandler()); // no-op

                // sync velocity compression state
                final MinecraftConnection minecraftConnection =
                        ctx.channel().pipeline().get(MinecraftConnection.class);
                if (minecraftConnection != null) {
                    minecraftConnection.setCompressionThreshold(-1);
                } else {
                    RaknetifyVelocityPlugin.LOGGER.warn(
                            "Unable to sync compression state with velocity");
                }
            }
        } else if (evt == VelocityConnectionEvent.COMPRESSION_DISABLED) {
            ctx.channel()
                    .pipeline()
                    .replace(
                            Connections.FRAME_DECODER,
                            Connections.FRAME_DECODER,
                            new ChannelDuplexHandler()); // no-op
            ctx.channel()
                    .pipeline()
                    .replace(
                            Connections.FRAME_ENCODER,
                            Connections.FRAME_ENCODER,
                            new ChannelDuplexHandler()); // no-op
        } else if (evt == VelocityConnectionEvent.ENCRYPTION_ENABLED) {
            Preconditions.checkState(
                    this.encryptionKey != null,
                    "EncryptionResponse not received yet or already consumed");
            ctx.channel()
                    .pipeline()
                    .replace(
                            Connections.CIPHER_ENCODER,
                            Connections.CIPHER_ENCODER,
                            new ChannelDuplexHandler());
            ctx.channel()
                    .pipeline()
                    .replace(
                            Connections.CIPHER_DECODER,
                            Connections.CIPHER_DECODER,
                            new ChannelDuplexHandler());
            ctx.channel()
                    .pipeline()
                    .addBefore(
                            MultiChannelingStreamingCompression.NAME,
                            MultiChannellingEncryption.NAME,
                            new MultiChannellingEncryption(encryptionKey));
        }
    }
}
