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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ishland.raknetify.api.PacketOrdering;
import com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;
import network.ycc.raknet.pipeline.UserDataCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Exercises the wire contract used to carry mod ordering requirements to direct servers and
 * proxies.
 */
class PayloadOrderingNegotiationTest {

    /**
     * Older transport pipelines discard the new extension without exposing it to Minecraft or
     * leaking it.
     */
    @Test
    void olderPeersDiscardTheOptionalExtension() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDataBlocker());
        FrameData frame = declaration(Map.of("example:optional", 2));
        try {
            assertFalse(channel.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertTrue(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A client-installed mod can supply a rule to a proxy without changing another player's rules.
     */
    @Test
    void remoteDeclarationsAreConnectionLocal() {
        EmbeddedChannel sender = endpoint(Map.of("remote:entity_path", 2));
        EmbeddedChannel receiver = endpoint(Map.of());
        EmbeddedChannel unrelated = endpoint(Map.of());
        ByteBuf packet = payload("remote:entity_path");
        FrameData announcement = sender.readOutbound();
        try {
            assertFalse(receiver.writeInbound(announcement));
            assertEquals(0, announcement.refCnt());
            assertEquals(
                    2,
                    new CustomPayloadChannel.PayloadClassifier(id -> id == 24, receiver)
                            .getChannelOverride(packet, false));
            assertEquals(
                    0,
                    new CustomPayloadChannel.PayloadClassifier(id -> id == 24, unrelated)
                            .getChannelOverride(packet, false));
            assertEquals(
                    0,
                    new CustomPayloadChannel.PayloadClassifier(id -> id == 25, receiver)
                            .getChannelOverride(packet, false));
            assertEquals(0, packet.readerIndex());
        } finally {
            packet.release();
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
            unrelated.finishAndReleaseAll();
        }
    }

    /** Local mod requirements cannot be overridden by a peer choosing a different traffic group. */
    @Test
    void localRegistrationTakesPrecedence() {
        PacketOrdering.register("local:world_data", "chunk");
        EmbeddedChannel sender = endpoint(Map.of("local:world_data", 2));
        EmbeddedChannel receiver = endpoint(Map.of());
        ByteBuf packet = payload("local:world_data");
        try {
            FrameData declaration = sender.readOutbound();
            receiver.writeInbound(declaration);
            assertEquals(
                    7,
                    new CustomPayloadChannel.PayloadClassifier(id -> id == 24, receiver)
                            .getChannelOverride(packet, false));
        } finally {
            packet.release();
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    /** Both setup and initial game frames use the same reliable ordering channel, in that order. */
    @Test
    void declarationPrecedesMinecraftTraffic() {
        EmbeddedChannel channel =
                new EmbeddedChannel(
                        new PayloadOrderingNegotiation(Map.of("example:step", 2)),
                        new UserDataCodec(0xFD));
        try {
            channel.writeOutbound(Unpooled.buffer().writeByte(0));
            FrameData declaration = channel.readOutbound();
            FrameData game = channel.readOutbound();
            try {
                assertEquals(PayloadOrderingNegotiation.PACKET_ID, declaration.getPacketId());
                assertEquals(0xFD, game.getPacketId());
                assertEquals(0, declaration.getOrderChannel());
                assertEquals(0, game.getOrderChannel());
                assertEquals(
                        FramedPacket.Reliability.RELIABLE_ORDERED, declaration.getReliability());
                assertEquals(FramedPacket.Reliability.RELIABLE_ORDERED, game.getReliability());
            } finally {
                declaration.release();
                game.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Rejects late declarations rather than changing the ordering of packets already delivered. */
    @Test
    void lateDeclarationClosesConnectionAndReleasesFrame() {
        EmbeddedChannel channel = endpoint(Map.of());
        channel.writeInbound(Unpooled.buffer().writeByte(0));
        ByteBuf game = channel.readInbound();
        game.release();
        FrameData frame = declaration(Map.of("example:late", 2));
        try {
            assertThrows(IllegalArgumentException.class, () -> channel.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertFalse(channel.isOpen());
            assertEquals(0, PayloadOrderingNegotiation.remoteChannel(channel, "example:late"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Rejects a second declaration and preserves the complete first table without leaking either
     * frame.
     */
    @Test
    void repeatedDeclarationCannotChangePolicy() {
        EmbeddedChannel channel = endpoint(Map.of());
        FrameData first = declaration(Map.of("example:fixed", 2));
        FrameData second = declaration(Map.of("example:fixed", 7));
        try {
            channel.writeInbound(first);
            assertThrows(IllegalArgumentException.class, () -> channel.writeInbound(second));
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertEquals(2, PayloadOrderingNegotiation.remoteChannel(channel, "example:fixed"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Bounds decoding and rejects malformed identifiers, groups, and incomplete messages
     * atomically.
     *
     * @param kind malformed declaration variant
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "version",
                "count",
                "identifier",
                "channel",
                "duplicate",
                "trailing",
                "truncated"
            })
    void malformedDeclarationsPublishNoRules(final String kind) {
        EmbeddedChannel channel = endpoint(Map.of());
        ByteBuf payload = malformed(kind);
        FrameData frame =
                FrameData.create(channel.alloc(), PayloadOrderingNegotiation.PACKET_ID, payload);
        payload.release();
        try {
            assertThrows(RuntimeException.class, () -> channel.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertEquals(0, PayloadOrderingNegotiation.remoteChannel(channel, "example:valid"));
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Rejects conflicting registrations and reserved names without changing earlier snapshots. */
    @Test
    void registrationValidatesAndSnapshotsRules() {
        PacketOrdering.register("example:snapshot", "entity");
        Map<String, Integer> snapshot = PacketOrdering.snapshot();
        PacketOrdering.register("example:later", "chunk");
        assertFalse(snapshot.containsKey("example:later"));
        assertEquals(2, snapshot.get("example:snapshot"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PacketOrdering.register("example:snapshot", "chunk"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PacketOrdering.register("minecraft:brand", "entity"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PacketOrdering.register("example:unsafe", "unreliable"));
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.put("example:mutation", 2));
        assertTrue(PacketOrdering.snapshot().containsKey("porting_lib:extra_data_entity_spawn"));
    }

    /**
     * Creates an active in-memory endpoint whose initial advertisement remains queued for
     * inspection.
     *
     * @param rules local startup declarations
     * @return owned channel that must be drained and closed by its caller
     */
    private static EmbeddedChannel endpoint(final Map<String, Integer> rules) {
        return new EmbeddedChannel(new PayloadOrderingNegotiation(rules));
    }

    /**
     * Encodes a declaration in the same reference-counted frame used by the real transport.
     *
     * @param rules valid remote declarations
     * @return owned frame transferred to an inbound pipeline by the caller
     */
    private static FrameData declaration(final Map<String, Integer> rules) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            PayloadOrderingNegotiation.encode(buffer, rules);
            return FrameData.create(buffer.alloc(), PayloadOrderingNegotiation.PACKET_ID, buffer);
        } finally {
            buffer.release();
        }
    }

    /**
     * Encodes a short custom payload for the proxy classifier without depending on Minecraft
     * classes.
     *
     * @param identifier ASCII identifier whose length fits one VarInt byte
     * @return owned packet buffer positioned at its packet ID
     */
    private static ByteBuf payload(final String identifier) {
        byte[] bytes = identifier.getBytes(StandardCharsets.US_ASCII);
        return Unpooled.buffer().writeByte(24).writeByte(bytes.length).writeBytes(bytes);
    }

    /**
     * Builds one invalid setup message, including variants with a valid prefix that must never be
     * published.
     *
     * @param kind named validation failure
     * @return owned declaration payload without its frame identifier
     */
    private static ByteBuf malformed(final String kind) {
        ByteBuf buffer = Unpooled.buffer();
        PayloadOrderingNegotiation.encode(buffer, Map.of("example:valid", 2));
        switch (kind) {
            case "version" -> buffer.setByte(0, 2);
            case "count" -> buffer.setShort(1, PacketOrdering.MAX_ENTRIES + 1);
            case "identifier" -> buffer.setByte(5, '!');
            case "channel" -> buffer.setByte(buffer.writerIndex() - 1, 255);
            case "duplicate" -> {
                buffer.setShort(1, 2);
                buffer.writeBytes(buffer, 3, buffer.writerIndex() - 3);
            }
            case "trailing" -> buffer.writeByte(1);
            case "truncated" -> buffer.writerIndex(buffer.writerIndex() - 1);
            default -> buffer.clear();
        }
        return buffer;
    }
}
