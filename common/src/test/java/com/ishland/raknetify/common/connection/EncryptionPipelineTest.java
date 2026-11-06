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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises cipher replacement when prior pipeline setup installed only one encryption direction.
 */
class EncryptionPipelineTest {
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
