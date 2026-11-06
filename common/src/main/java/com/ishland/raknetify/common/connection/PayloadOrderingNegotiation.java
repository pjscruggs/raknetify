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

import com.ishland.raknetify.api.PacketOrdering;
import com.ishland.raknetify.common.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exchanges bounded custom-payload ordering declarations before channel-zero Minecraft traffic. */
public final class PayloadOrderingNegotiation extends ChannelDuplexHandler {

    /** Pipeline name for the transport-only declaration exchange. */
    public static final String NAME = "raknetify-payload-ordering";

    /**
     * Application-frame identifier unused by Minecraft payloads or earlier transport extensions.
     */
    public static final int PACKET_ID = 0xF9;

    /** Per-connection remote declarations, never shared between players or servers. */
    private static final AttributeKey<Map<String, Integer>> REMOTE =
            AttributeKey.valueOf(NAME + ":remote");

    /** Immutable startup declarations advertised by this endpoint. */
    private final Map<String, Integer> local;

    /**
     * Prevents duplicate advertisement when a handler is installed on an already active channel.
     */
    private boolean sent;

    /** Prevents mid-session policy changes from reordering packets already in flight. */
    private boolean received;

    /**
     * Locks out declarations once any Minecraft packet has crossed the inbound ordering boundary.
     */
    private boolean applicationTrafficReceived;

    /**
     * Captures the local rules before the endpoint starts sending Minecraft traffic.
     *
     * @param local registered payload identifiers and reliable ordering channels
     */
    public PayloadOrderingNegotiation(final Map<String, Integer> local) {
        this.local = Map.copyOf(local);
    }

    /**
     * Advertises immediately if initialization occurs after channel activation.
     *
     * @param context newly installed declaration handler
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext context) {
        if (context.channel().isActive()) {
            advertise(context);
        }
    }

    /**
     * Sends declarations before propagating activation to Minecraft's login handlers.
     *
     * @param context activated transport application channel
     */
    @Override
    public void channelActive(final ChannelHandlerContext context) {
        advertise(context);
        context.fireChannelActive();
    }

    /**
     * Encodes one ordered setup frame. Older peers safely discard this unknown extension.
     *
     * @param context outbound context bypassing Minecraft packet serialization
     */
    private void advertise(final ChannelHandlerContext context) {
        if (sent) {
            return;
        }
        sent = true;
        ByteBuf payload = context.alloc().buffer();
        try {
            encode(payload, local);
            FrameData frame = FrameData.create(context.alloc(), PACKET_ID, payload);
            frame.setOrderChannel(0);
            frame.setReliability(FramedPacket.Reliability.RELIABLE_ORDERED);
            context.writeAndFlush(frame);
        } finally {
            payload.release();
        }
    }

    /**
     * Applies one complete declaration atomically and releases its transport frame on every
     * outcome.
     *
     * @param context inbound application context receiving ordered transport frames
     * @param message declaration frame, Minecraft payload, or another transport extension
     * @throws Exception if malformed declarations or downstream handlers reject the message
     */
    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message)
            throws Exception {
        if (!(message instanceof FrameData frame) || frame.getPacketId() != PACKET_ID) {
            if (isGameMessage(message)) {
                applicationTrafficReceived = true;
            }
            context.fireChannelRead(message);
            return;
        }
        try {
            accept(context.channel(), frame);
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        } finally {
            frame.release();
        }
    }

    /**
     * Requires the initial reliable channel-zero exchange before replacing the connection's remote
     * rules.
     *
     * @param channel receiving connection whose classifier will consult the immutable result
     * @param frame complete setup frame whose data remains owned by the caller
     * @throws IllegalArgumentException if a peer tries to change rules or bypass setup ordering
     */
    private void accept(final Channel channel, final FrameData frame) {
        if (received
                || applicationTrafficReceived
                || frame.getOrderChannel() != 0
                || frame.getReliability() != FramedPacket.Reliability.RELIABLE_ORDERED
                || frame.isFragment()) {
            throw new IllegalArgumentException("Invalid packet ordering exchange");
        }
        ByteBuf data = frame.createData();
        try {
            data.skipBytes(1);
            Map<String, Integer> rules = decode(data);
            channel.attr(REMOTE).set(rules);
            received = true;
        } finally {
            data.release();
        }
    }

    /**
     * Recognizes Minecraft data whether the platform places its frame decoder before or after this
     * handler.
     *
     * @param message decoded bytes or a transport frame
     * @return whether applying a new declaration after this message could reorder game traffic
     */
    private static boolean isGameMessage(final Object message) {
        return message instanceof ByteBuf
                || message instanceof FrameData frame
                        && frame.getPacketId() == Constants.RAKNET_GAME_PACKET_ID;
    }

    /**
     * Looks up a peer-requested custom-payload rule without creating shared mutable state.
     *
     * @param channel connection that received the declaration, or null for local-only
     *     classification
     * @param identifier custom payload whose ordering dependency is being classified
     * @return declared channel, or zero to retain ordinary classification
     */
    public static int remoteChannel(final Channel channel, final String identifier) {
        if (channel == null) {
            return 0;
        }
        Map<String, Integer> rules = channel.attr(REMOTE).get();
        return rules == null ? 0 : rules.getOrDefault(identifier, 0);
    }

    /**
     * Writes a versioned, bounded declaration with deterministic identifier ordering.
     *
     * @param output destination buffer without its transport frame identifier
     * @param rules immutable or otherwise stable declarations to advertise
     * @throws IllegalArgumentException if the entry count or any declaration is invalid
     */
    static void encode(final ByteBuf output, final Map<String, Integer> rules) {
        if (rules.size() > PacketOrdering.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many ordering rules");
        }
        output.writeByte(1).writeShort(rules.size());
        rules.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    PacketOrdering.validate(entry.getKey(), entry.getValue());
                    byte[] identifier = entry.getKey().getBytes(StandardCharsets.US_ASCII);
                    output.writeShort(identifier.length)
                            .writeBytes(identifier)
                            .writeByte(entry.getValue());
                });
    }

    /**
     * Validates the complete declaration before publishing any of its entries to a classifier.
     *
     * @param input declaration payload positioned after its transport identifier
     * @return immutable remote rules
     * @throws IllegalArgumentException if the version, count, identifiers, channels, or trailing
     *     data are invalid
     */
    static Map<String, Integer> decode(final ByteBuf input) {
        if (input.readUnsignedByte() != 1) {
            throw new IllegalArgumentException("Unknown ordering protocol version");
        }
        int count = input.readUnsignedShort();
        if (count > PacketOrdering.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many ordering rules");
        }
        Map<String, Integer> rules = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            readEntry(input, rules);
        }
        if (input.isReadable()) {
            throw new IllegalArgumentException("Trailing ordering data");
        }
        return Map.copyOf(rules);
    }

    /**
     * Reads one short ASCII identifier and rejects duplicates before modifying the candidate map.
     *
     * @param input unread declaration entries
     * @param rules candidate map that remains private until the entire message validates
     * @throws IllegalArgumentException if the identifier length, contents, channel, or duplicate is
     *     invalid
     */
    private static void readEntry(final ByteBuf input, final Map<String, Integer> rules) {
        int length = input.readUnsignedShort();
        if (length > PacketOrdering.MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("Oversized payload identifier");
        }
        String identifier = input.readCharSequence(length, StandardCharsets.US_ASCII).toString();
        int channel = input.readUnsignedByte();
        PacketOrdering.validate(identifier, channel);
        if (rules.putIfAbsent(identifier, channel) != null) {
            throw new IllegalArgumentException("Duplicate ordering rule");
        }
    }
}
