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

package com.ishland.raknetify.test;

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.loader.api.FabricLoader;
import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;
import network.ycc.raknet.pipeline.FrameOrderIn;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Checks native packet routing under deterministic reversal of inventory and interaction traffic. */
final class GameplayOrderingTest {

    /** Stable legacy and modern names for actions that read or change the player's held item and movement state. */
    private static final String[][] GAMEPLAY_PACKETS = {
            {"class_2873", "ServerboundSetCreativeModeSlotPacket"},
            {"class_2868", "ServerboundSetCarriedItemPacket"},
            {"class_2828$class_2829", "ServerboundMovePlayerPacket$Pos"},
            {"class_2885", "ServerboundUseItemOnPacket"},
            {"class_2846", "ServerboundPlayerActionPacket"},
            {"class_2886", "ServerboundUseItemPacket"},
            {"class_2813", "ServerboundContainerClickPacket"},
            {"class_2815", "ServerboundContainerClosePacket"},
            {"class_2848", "ServerboundPlayerCommandPacket"},
            {"class_2851", "ServerboundPlayerInputPacket"},
            {"class_2842", "ServerboundPlayerAbilitiesPacket"},
            {"class_2824", "ServerboundInteractPacket"}
    };

    /** Entity creation, metadata, pickup and removal must share one ordered lifecycle. */
    private static final String[][] ENTITY_PACKETS = {
            {"class_2604", "ClientboundAddEntityPacket"},
            {"class_2739", "ClientboundSetEntityDataPacket"},
            {"class_2775", "ClientboundTakeItemEntityPacket"},
            {"class_2716", "ClientboundRemoveEntitiesPacket"}
    };

    /** Prevents construction of this isolated runtime witness. */
    private GameplayOrderingTest() {
    }

    /**
     * Uses the installed native packet table and real frame reorderer to restore an entirely reversed batch.
     *
     * @throws ReflectiveOperationException if a supported version no longer exposes a required packet class
     */
    static void verify() throws ReflectiveOperationException {
        verifyGroup(GAMEPLAY_PACKETS, "gameplay");
        verifyGroup(ENTITY_PACKETS, "entity lifecycle");
    }

    /**
     * Reverses a complete packet group and checks that transport restores the native send sequence.
     *
     * @param packets namespace pairs for packets that share state dependencies
     * @param label diagnostic group name written to the fixture log
     * @throws ReflectiveOperationException if a native packet or classifier is unavailable
     */
    private static void verifyGroup(String[][] packets, String label) throws ReflectiveOperationException {
        int[] routes = nativeRoutes(packets);
        RakNetSimpleMultiChannelCodec codec = new RakNetSimpleMultiChannelCodec(253);
        codec.addHandler((buffer, silent) -> routes[buffer.getUnsignedByte(buffer.readerIndex())]);
        EmbeddedChannel sender = new EmbeddedChannel(codec);
        EmbeddedChannel receiver = new EmbeddedChannel(new FrameOrderIn());
        List<Frame> frames = new ArrayList<>();
        try {
            sender.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            ((FrameData) sender.readOutbound()).release();
            encode(sender, routes, frames);
            while (!frames.isEmpty()) receiver.writeInbound(frames.remove(frames.size() - 1));
            for (int i = 0; i < packets.length; i++) checkNext(receiver, i);
            require(receiver.readInbound() == null, "Duplicate gameplay packet");
            System.out.println("PASS native " + label + " ordering " + packets.length);
        } finally {
            frames.forEach(Frame::release);
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    /**
     * Resolves the installed packet classes rather than copying the production channel assignments.
     *
     * @param packets pairs of legacy and modern packet names to resolve
     * @return actual channel assignments for this runtime's equivalent gameplay packets
     * @throws ReflectiveOperationException if packet lookup or the routing entry point fails
     */
    private static int[] nativeRoutes(String[][] packets) throws ReflectiveOperationException {
        boolean modern = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString().startsWith("26.");
        Method route = Class.forName("com.ishland.raknetify.fabric.common.connection.RakNetMultiChannel")
                .getMethod("getPacketChannelOverride", Class.class, boolean.class);
        int[] routes = new int[packets.length];
        for (int i = 0; i < packets.length; i++) {
            String name = modern ? "net.minecraft.network.protocol.game." + packets[i][1] : "net.minecraft." + packets[i][0];
            Class<?> packet = Class.forName(name, false, GameplayOrderingTest.class.getClassLoader());
            routes[i] = (int) route.invoke(null, packet, false);
        }
        return routes;
    }

    /**
     * Encodes each action through the real classifier and assigns independent per-channel sequence numbers.
     *
     * @param sender production codec configured with the native class-derived routes
     * @param routes channel assignments used to check the encoded frame
     * @param frames owned frames collected for delivery in reverse order
     */
    private static void encode(EmbeddedChannel sender, int[] routes, List<Frame> frames) {
        int[] sequence = new int[8];
        for (int i = 0; i < routes.length; i++) {
            sender.writeOutbound(Unpooled.buffer(1).writeByte(i));
            FrameData data = sender.readOutbound();
            try {
                require(data.getReliability() == FramedPacket.Reliability.RELIABLE_ORDERED, "Unordered gameplay packet");
                require(data.getOrderChannel() == routes[i], "Classifier lost native channel");
                frames.add(Frame.createOrdered(data, sequence[data.getOrderChannel()]++, 0));
            } finally {
                data.release();
            }
        }
    }

    /**
     * Requires the next delivered action to retain its send position after transport reordering.
     *
     * @param receiver real RakNet reorderer after every reversed frame has arrived
     * @param expected original action index, also encoded in the test packet payload
     */
    private static void checkNext(EmbeddedChannel receiver, int expected) {
        FrameData data = receiver.readInbound();
        require(data != null, "Missing gameplay packet " + expected);
        try {
            ByteBuf bytes = data.createData();
            try {
                bytes.skipBytes(1);
                int actual = bytes.readUnsignedByte();
                require(actual == expected, "Gameplay order changed: expected " + expected + " but received " + actual);
            } finally {
                bytes.release();
            }
        } finally {
            data.release();
        }
    }

    /**
     * Fails the fixture immediately without relying on JVM assertion flags.
     *
     * @param condition invariant established by the native transport witness
     * @param message failure detail retained in the CI log
     * @throws AssertionError if the transport violates the checked invariant
     */
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
