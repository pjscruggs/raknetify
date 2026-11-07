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

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

import java.util.function.Consumer;

/**
 * Applies a fixture's channel setup after registration without sharing mutable handlers between
 * connections.
 */
public final class ChannelSetup extends ChannelInitializer<Channel> {
    /** Connection-local setup invoked once by Netty's initializer lifecycle. */
    private final Consumer<Channel> setup;

    /**
     * Captures fixture configuration without opening a socket or registering an event loop.
     *
     * @param setup callback installing the handlers needed for this connection
     */
    public ChannelSetup(final Consumer<Channel> setup) {
        this.setup = setup;
    }

    /**
     * Installs handlers while Netty still owns registration failure cleanup.
     *
     * @param channel newly registered channel owned by the fixture
     */
    @Override
    protected void initChannel(final Channel channel) {
        setup.accept(channel);
    }
}
