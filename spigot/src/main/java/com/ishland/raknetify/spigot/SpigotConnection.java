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

package com.ishland.raknetify.spigot;

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.RakNetConnectionUtil;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPipeline;

import java.security.PrivateKey;

/** Installs RakNet framing without replacing the native server's packet listener or login state machine. */
final class SpigotConnection {
    /** Prevents instantiation of the channel setup utility. */
    private SpigotConnection() { }

    /**
     * Installs transport framing before the original Minecraft initializer adds its packet codecs.
     * @param channel accepted RakNet application channel
     */
    static void initialize(Channel channel) {
        RakNetConnectionUtil.initChannel(channel);
        channel.pipeline().addAfter(MultiChannelingStreamingCompression.NAME,
                RakNetSimpleMultiChannelCodec.NAME, new RakNetSimpleMultiChannelCodec(Constants.RAKNET_GAME_PACKET_ID));
    }

    /**
     * Replaces TCP framing and installs connection-local login and protocol-transition observers.
     * @param channel channel initialized by the server's normal child initializer
     * @param key native server login key used to recover the negotiated shared secret
     * @throws IllegalStateException if TCP proxy headers are required or the tested packet map is absent
     */
    static void finish(Channel channel, PrivateKey key) {
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get("haproxy-decoder") != null) {
            throw new IllegalStateException("RakNet does not yet support this server's required PROXY headers");
        }
        var mapping = ProtocolMultiChannelMappings.INSTANCE.mappings.get(777);
        if (mapping == null) throw new IllegalStateException("Missing Minecraft 26.3 packet routing table");
        for (String name : new String[]{"timeout", "splitter", "prepender"}) {
            pipeline.replace(name, name, new ChannelDuplexHandler());
        }
        remove(pipeline, "legacy_query");
        SpigotCipherState cipher = new SpigotCipherState(key);
        SpigotTransportGuard guard = new SpigotTransportGuard(cipher);
        var classifier = new RakNetSimpleMultiChannelCodec.PacketIdBasedOverrideHandler(mapping.s2c,
                "Spigot 26.3 clientbound");
        SpigotPacketHandler packets = new SpigotPacketHandler(cipher, guard, classifier);
        pipeline.addBefore(MultiChannelingStreamingCompression.NAME, "raknetify-spigot-transport", guard);
        pipeline.addBefore("packet_handler", "raknetify-spigot-packets", packets);
        pipeline.addBefore("packet_handler", "raknetify-spigot-disconnect", new SpigotDisconnectHandler());
        pipeline.get(RakNetSimpleMultiChannelCodec.class).addHandler(packets::classify);
    }

    /**
     * Removes a named optional handler while allowing its native cleanup callback to release resources.
     * @param pipeline connection pipeline owning the handler
     * @param name optional handler name
     */
    static void remove(ChannelPipeline pipeline, String name) {
        if (pipeline.get(name) != null) pipeline.remove(name);
    }
}
