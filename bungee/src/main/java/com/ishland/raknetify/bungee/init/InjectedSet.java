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

package com.ishland.raknetify.bungee.init;

import com.google.common.collect.ForwardingSet;

import io.netty.channel.Channel;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/** Observes BungeeCord listener-set changes to create and close companion RakNet listeners. */
public class InjectedSet extends ForwardingSet<Channel> {

    /** Concurrent listener membership owned by the proxy after lifecycle hooks are installed. */
    private final Set<Channel> delegate;

    /**
     * Validates existing listener entries before publishing a concurrent set with lifecycle hooks.
     *
     * @param delegate existing listener membership, including layouts declared as a generic
     *     collection
     */
    public InjectedSet(final java.util.Collection<?> delegate) {
        final Set<Channel> checked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (final Object channel : delegate) {
            checked.add(Channel.class.cast(channel));
        }
        this.delegate = checked;
    }

    /**
     * Adds a proxy listener and schedules UDP setup after the proxy finishes initializing it.
     *
     * @param element newly bound proxy listener channel
     * @return whether the backing set gained a new element
     */
    @Override
    public boolean add(final @Nullable Channel element) {
        final Channel channel = Objects.requireNonNull(element, "listener channel");
        if (!super.add(channel)) {
            return false;
        }
        BungeeRaknetifyServer.listenerAdded(channel);

        return true;
    }

    /** Closes companion UDP listeners before clearing the proxy's listener set. */
    @Override
    public void clear() {
        BungeeRaknetifyServer.stopAll();

        super.clear();
    }

    /**
     * Returns the checked concurrent set used for operations without lifecycle hooks.
     *
     * @return proxy listener membership owned by this instance
     */
    @Override
    protected Set<Channel> delegate() {
        return this.delegate;
    }
}
