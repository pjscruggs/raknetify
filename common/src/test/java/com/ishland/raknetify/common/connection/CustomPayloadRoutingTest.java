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

import com.ishland.raknetify.common.connection.multichannel.CustomPayloadChannel;
import com.ishland.raknetify.api.PacketOrdering;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers entity-dependent custom payloads through the shared Fabric and proxy routing path. */
class CustomPayloadRoutingTest {

    /** Packet ID supplied by the proxy protocol registry in this deterministic fixture. */
    private static final int CUSTOM_PAYLOAD_ID = 24;

    /**
     * Keeps trajectory frames ordered with entity updates after negotiation and on channel zero before it.
     *
     * @param negotiated whether multichannel negotiation has completed
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trajectoryDeliveryRemainsReliableAndOrdered(boolean negotiated) {
        EmbeddedChannel channel = new EmbeddedChannel(proxyCodec());
        try {
            if (negotiated) {
                channel.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
                FrameData marker = channel.readOutbound();
                marker.release();
            }
            assertTrue(channel.writeOutbound(payload(CUSTOM_PAYLOAD_ID, "example:entity_path")));
            FrameData frame = channel.readOutbound();
            try {
                assertEquals(FramedPacket.Reliability.RELIABLE_ORDERED, frame.getReliability());
                assertEquals(negotiated ? 2 : 0, frame.getOrderChannel());
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Classifies a sliced payload without changing its index, bytes, or retained-reference count. */
    @Test
    void classificationPreservesPacketOwnership() {
        ByteBuf packet = payload(CUSTOM_PAYLOAD_ID, "example:entity_path");
        try {
            int reader = packet.readerIndex();
            int writer = packet.writerIndex();
            int references = packet.refCnt();
            assertEquals(2, proxyCodec().getChannelOverride(packet, false));
            assertEquals(reader, packet.readerIndex());
            assertEquals(writer, packet.writerIndex());
            assertEquals(references, packet.refCnt());
            assertEquals(CUSTOM_PAYLOAD_ID, packet.getUnsignedByte(reader));
        } finally {
            packet.release();
        }
    }

    /**
     * Leaves capability/configuration and unrelated custom payloads on their ordinary proxy channel.
     *
     * @param identifier payload identifier that must not inherit the trajectory override
     */
    @ParameterizedTest
    @ValueSource(strings = {"example:hello", "example:config", "other-mod:trajectory_v1"})
    void unrelatedPayloadsKeepTheirFallback(String identifier) {
        ByteBuf packet = payload(CUSTOM_PAYLOAD_ID, identifier);
        try {
            assertEquals(3, proxyCodec().getChannelOverride(packet, false));
        } finally {
            packet.release();
        }
    }

    /** Requires the proxy's packet-ID predicate before interpreting any bytes as a payload identifier. */
    @Test
    void ordinaryPacketsDoNotUsePayloadOverrides() {
        ByteBuf packet = payload(CUSTOM_PAYLOAD_ID + 1, "example:entity_path");
        try {
            CustomPayloadChannel.OverrideHandler handler = new CustomPayloadChannel.OverrideHandler(
                    id -> id == CUSTOM_PAYLOAD_ID);
            assertEquals(0, handler.getChannelOverride(packet, false));
        } finally {
            packet.release();
        }
    }

    /**
     * Recreates the custom-payload-first classifier order used by Velocity and BungeeCord.
     *
     * @return codec with the generic world-interaction channel as its custom-payload fallback
     */
    private static RakNetSimpleMultiChannelCodec proxyCodec() {
        PacketOrdering.register("example:entity_path", "entity");
        Int2IntOpenHashMap routes = new Int2IntOpenHashMap();
        routes.put(CUSTOM_PAYLOAD_ID, 3);
        return new RakNetSimpleMultiChannelCodec(0xFD)
                .addHandler(new CustomPayloadChannel.OverrideHandler(id -> id == CUSTOM_PAYLOAD_ID))
                .addHandler(new RakNetSimpleMultiChannelCodec.PacketIdBasedOverrideHandler(routes, "test protocol"));
    }

    /**
     * Builds a short identifier-prefixed packet behind one already-consumed byte.
     *
     * @param packetId single-byte Minecraft packet ID for this fixture
     * @param identifier short namespaced identifier whose UTF-8 length fits one byte
     * @return owned buffer positioned at the Minecraft packet ID
     */
    private static ByteBuf payload(int packetId, String identifier) {
        byte[] encoded = identifier.getBytes(StandardCharsets.UTF_8);
        return Unpooled.buffer().writeByte(99).writeByte(packetId).writeByte(encoded.length)
                .writeBytes(encoded).writeByte(42).readerIndex(1);
    }
}
