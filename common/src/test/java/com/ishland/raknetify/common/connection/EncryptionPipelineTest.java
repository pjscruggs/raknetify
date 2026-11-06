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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ishland.raknetify.common.Constants;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.GeneralSecurityException;

import javax.crypto.KeyGenerator;

/**
 * Exercises cipher replacement when prior pipeline setup installed only one encryption direction.
 */
class EncryptionPipelineTest {
    /**
     * Transport metrics are consumed before the cipher while unencrypted game traffic still fails.
     *
     * @throws GeneralSecurityException if the test runtime cannot create an AES key
     */
    @Test
    void metricsDoNotBypassGameAuthentication() throws GeneralSecurityException {
        var metrics = new MetricsSynchronizationHandler();
        var encryption =
                new MultiChannellingEncryption(
                        KeyGenerator.getInstance("AES").generateKey(), false);
        var channel = new EmbeddedChannel(metrics, encryption);
        try {
            FrameData control = zeroFrame(Constants.RAKNET_METRICS_SYNC_PACKET_ID);
            assertFalse(channel.writeInbound(control));
            assertEquals(0, control.refCnt());
            assertTrue(metrics.isRemoteSupported());
            assertTrue(channel.isOpen());
            FrameData game = zeroFrame(Constants.RAKNET_GAME_PACKET_ID);
            assertThrows(GeneralSecurityException.class, () -> channel.writeInbound(game));
            assertEquals(0, game.refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Creates a zero-valued metrics-sized payload under the selected outer identifier.
     *
     * @param packetId transport or application identifier whose pipeline handling is tested
     * @return reference-counted frame transferred to the test channel by its caller
     */
    private static FrameData zeroFrame(final int packetId) {
        ByteBuf payload = Unpooled.buffer(33).writeZero(33);
        try {
            return FrameData.create(UnpooledByteBufAllocator.DEFAULT, packetId, payload);
        } finally {
            payload.release();
        }
    }

    /**
     * An absent decrypt handler must not prevent removing encrypt or an earlier frame cipher.
     *
     * @param previous handler left by an earlier handshake stage
     */
    @ParameterizedTest
    @ValueSource(strings = {"decrypt", "encrypt", MultiChannellingEncryption.NAME})
    void removesEachCipherIndependently(final String previous) {
        var channel = new EmbeddedChannel();
        var unrelated = new ChannelInboundHandlerAdapter();
        channel.pipeline().addLast("game", unrelated);
        channel.pipeline().addLast(previous, new ChannelInboundHandlerAdapter());
        try {
            MultiChannellingEncryption.removePreviousHandlers(channel.pipeline());
            assertNull(channel.pipeline().get(previous));
            assertSame(unrelated, channel.pipeline().get("game"));
            MultiChannellingEncryption.removePreviousHandlers(channel.pipeline());
            channel.checkException();
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
