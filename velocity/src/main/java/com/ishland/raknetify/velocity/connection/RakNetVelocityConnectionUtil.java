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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.ByteBufCopyDecoder;
import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel;
import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;
import com.ishland.raknetify.common.proxy.ProxyProtocol;
import com.ishland.raknetify.velocity.RaknetifyVelocityPlugin;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.network.Connections;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;

import network.ycc.raknet.RakNet;

import java.util.Arrays;

/** Installs Velocity connection hooks around the shared RakNet transport pipeline. */
public class RakNetVelocityConnectionUtil {

    /** Prevents construction of the proxy connection-initialization utility. */
    private RakNetVelocityConnectionUtil() {}

    /**
     * Installs shared transport handlers and Minecraft framing before the proxy initializes its
     * pipeline.
     *
     * @param channel accepted application channel, left unchanged when it is not a RakNet channel
     */
    public static void initChannel(final Channel channel) {
        if (channel.config() instanceof RakNet.Config) {
            RakNetConnectionUtil.initChannel(channel);
            //            channel.pipeline().addAfter(MultiChannelingStreamingCompression.NAME,
            // MultiChannellingDataCodec.NAME, new
            // MultiChannellingDataCodec(Constants.RAKNET_GAME_PACKET_ID));
            channel.pipeline()
                    .addAfter(
                            MultiChannelingStreamingCompression.NAME,
                            RakNetSimpleMultiChannelCodec.NAME,
                            new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID));
            channel.pipeline()
                    .addAfter(
                            RakNetSimpleMultiChannelCodec.NAME,
                            ByteBufCopyDecoder.NAME,
                            new ByteBufCopyDecoder());
        }
    }

    /**
     * Replaces TCP framing and timeout behavior after proxy initialization and attaches login
     * hooks. A required HAProxy stream decoder is removed only after the UDP layer has validated
     * the identity.
     *
     * @param channel initialized client connection, left unchanged for TCP
     * @param isClientSide reserved direction flag, currently unused by this server-side integration
     */
    public static void postInitChannel(final Channel channel, final boolean isClientSide) {
        if (channel.config() instanceof RakNet.Config) {
            channel.pipeline()
                    .replace(
                            Connections.READ_TIMEOUT,
                            Connections.READ_TIMEOUT,
                            new ChannelDuplexHandler()); // no-op
            channel.pipeline()
                    .replace(
                            Connections.FRAME_DECODER,
                            Connections.FRAME_DECODER,
                            new ChannelDuplexHandler()); // no-op
            channel.pipeline()
                    .replace(
                            Connections.FRAME_ENCODER,
                            Connections.FRAME_ENCODER,
                            new ChannelDuplexHandler()); // no-op
            if (channel.pipeline().get(HAProxyMessageDecoder.class) != null) {
                ProxyProtocol.requireForwarded(channel);
                channel.pipeline().remove(HAProxyMessageDecoder.class);
            }
            channel.pipeline()
                    .addBefore(
                            Connections.HANDLER,
                            RakNetVelocityChannelEventListener.NAME,
                            new RakNetVelocityChannelEventListener());
            //            System.out.println(channel.pipeline().names());
            //            final MultiChannellingPacketCapture handler = new
            // MultiChannellingPacketCapture();
            //            channel.pipeline().addLast("raknetify-multi-channel-packet-cature",
            // handler);
            //
            // channel.pipeline().get(MultiChannellingDataCodec.class).setCapture(handler);
        }
    }

    /**
     * Selects the client's protocol routing table and installs transport-derived ping updates.
     * Unknown protocol numbers retain the default routing behavior and produce a warning.
     *
     * @param evt proxy login event supplying the connected player
     */
    public static void onPlayerLogin(final LoginEvent evt) {
        final ConnectedPlayer player = (ConnectedPlayer) evt.getPlayer();
        final Channel channel = player.getConnection().getChannel();
        if (channel != null && channel.config() instanceof RakNet.Config config) {
            final RakNetSimpleMultiChannelCodec multiChannelCodec =
                    channel.pipeline().get(RakNetSimpleMultiChannelCodec.class);
            if (multiChannelCodec != null) {
                final ProtocolVersion protocolVersion = player.getProtocolVersion();
                final ProtocolMultiChannelMappings.VersionMapping versionMapping =
                        ProtocolMultiChannelMappings.INSTANCE.mappings.get(
                                protocolVersion.getProtocol());
                if (versionMapping != null) {

                    // handle custom payload separately
                    final int pluginMessageId =
                            StateRegistry.PLAY
                                    .getProtocolRegistry(
                                            ProtocolUtils.Direction.CLIENTBOUND, protocolVersion)
                                    .getPacketId(new PluginMessagePacket());
                    if (Constants.DEBUG) {
                        RaknetifyVelocityPlugin.LOGGER.info(
                                "PluginMessage packetId=%d at version=%d"
                                        .formatted(pluginMessageId, protocolVersion.getProtocol()));
                    }
                    multiChannelCodec.addHandler(
                            new CustomPayloadChannel.OverrideHandler(
                                    value -> value == pluginMessageId, channel));

                    // packet id -> channel id
                    multiChannelCodec.addHandler(
                            new RakNetSimpleMultiChannelCodec.PacketIdBasedOverrideHandler(
                                    versionMapping.s2c,
                                    "%s (%d)"
                                            .formatted(
                                                    protocolVersion.getVersionIntroducedIn(),
                                                    protocolVersion.getProtocol())));
                } else {
                    RaknetifyVelocityPlugin.LOGGER.warn(
                            "No multi-channel mappings for protocol version {} ({})",
                            protocolVersion.getProtocol(),
                            Arrays.toString(
                                    protocolVersion
                                            .getVersionsSupportedBy()
                                            .toArray(String[]::new)));
                }
            }

            channel.pipeline()
                    .addBefore(
                            Connections.HANDLER,
                            RakNetVelocityPingUpdater.NAME,
                            new RakNetVelocityPingUpdater(player));

            RaknetifyVelocityPlugin.LOGGER.info(
                    String.format(
                            "Raknetify: %s logged in via RakNet, mtu %d",
                            evt.getPlayer().getGameProfile().getName(), config.getMTU()));
        }
    }

    /**
     * Attaches backend keepalive handling after a RakNet player connects to a server. Missing
     * backend connections are logged and left untouched.
     *
     * @param evt completed backend connection event for the player
     */
    public static void onServerSwitch(final ServerPostConnectEvent evt) {
        final ConnectedPlayer player = (ConnectedPlayer) evt.getPlayer();
        final VelocityServerConnection connectedServer = player.getConnectedServer();
        if (connectedServer == null) {
            RaknetifyVelocityPlugin.LOGGER.warn(
                    "No connected server for player ({}) after server switch?", player);
            return;
        }
        final MinecraftConnection serverConnection = connectedServer.getConnection();
        if (serverConnection == null) {
            RaknetifyVelocityPlugin.LOGGER.warn(
                    "Connected server ({}) have no underlying connection?", connectedServer);
            return;
        }
        final Channel channel = player.getConnection().getChannel();
        if (channel.config() instanceof RakNet.Config) {
            serverConnection
                    .getChannel()
                    .pipeline()
                    .addBefore(
                            Connections.HANDLER,
                            RakNetVelocityServerChannelEventListener.NAME,
                            new RakNetVelocityServerChannelEventListener(channel));
        }
    }
}
