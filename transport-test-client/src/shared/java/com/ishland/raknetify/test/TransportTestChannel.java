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

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.config.DefaultConfig;

import org.jspecify.annotations.Nullable;

/**
 * Runs native ordering witnesses with the same receive-budget configuration as real RakNet
 * connections.
 */
final class TransportTestChannel extends EmbeddedChannel {
    /**
     * Created on the first configuration request, including requests made by the superclass
     * constructor.
     */
    private @Nullable DefaultConfig transportConfig;

    /**
     * Installs the production handlers under test on a deterministic event loop without opening a
     * socket.
     *
     * @param handlers pipeline stages that consume and release the fixture's injected frames
     */
    TransportTestChannel(final ChannelHandler... handlers) {
        super(handlers);
    }

    /**
     * Supplies persistent transport limits so native fixtures exercise the real receive-budget
     * checks.
     *
     * @return configuration owned by this embedded connection
     */
    @Override
    public DefaultConfig config() {
        if (transportConfig == null) {
            transportConfig = new DefaultConfig(this);
        }
        return transportConfig;
    }
}
