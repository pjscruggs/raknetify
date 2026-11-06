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

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Checks registration visibility and stable classifier precedence during reentrant updates. */
class ClassifierSnapshotTest {
    /** A callback may change future routing without changing the snapshot of the packet being classified. */
    @Test
    void reentrantRemovalAffectsOnlyTheNextPacket() {
        var codec = new RakNetSimpleMultiChannelCodec(0xfe);
        RakNetSimpleMultiChannelCodec.OverrideHandler second = (buffer, quiet) -> 2;
        codec.addHandler((buffer, quiet) -> { codec.removeHandler(second); return 0; });
        codec.addHandler(second);
        var packet = Unpooled.buffer().writeByte(1);
        try {
            assertEquals(2, codec.getChannelOverride(packet, true));
            assertEquals(0, codec.getChannelOverride(packet, true));
            assertEquals(0, packet.readerIndex());
        } finally {
            packet.release();
        }
    }

    /**
     * Concurrent registration must not lose rules, and each removal must expose the next complete snapshot.
     * @throws Exception if a bounded registration task fails
     */
    @Test
    void concurrentWritersPublishEveryRegistration() throws Exception {
        var codec = new RakNetSimpleMultiChannelCodec(0xfe);
        RakNetSimpleMultiChannelCodec.OverrideHandler[] rules = new RakNetSimpleMultiChannelCodec.OverrideHandler[128];
        for (int i = 0; i < rules.length; i++) {
            final int id = i + 1;
            rules[i] = (buffer, quiet) -> id;
        }
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> add(codec, rules, 0));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> add(codec, rules, 1));
        CompletableFuture.allOf(first, second).get(5, TimeUnit.SECONDS);
        var packet = Unpooled.buffer().writeByte(0);
        try {
            for (int remaining = rules.length; remaining > 0; remaining--) {
                int id = codec.getChannelOverride(packet, true);
                assertTrue(id > 0);
                codec.removeHandler(rules[id - 1]);
            }
            assertFalse(codec.isMultichannelAvailable());
        } finally {
            packet.release();
        }
    }

    /**
     * Registers one interleaved half of the rules from a separate writer.
     * @param codec shared registration target
     * @param rules unique identities that must all survive publication
     * @param offset parity assigned to this writer
     */
    private static void add(RakNetSimpleMultiChannelCodec codec, RakNetSimpleMultiChannelCodec.OverrideHandler[] rules, int offset) {
        for (int i = offset; i < rules.length; i += 2) codec.addHandler(rules[i]);
    }
}
