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
import com.ishland.raknetify.bungee.RaknetifyBungeePlugin;
import io.netty.channel.Channel;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.api.ProxyServer;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Observes BungeeCord listener-set changes to create and close companion RakNet listeners.
 */
public class InjectedSet extends ForwardingSet<Channel> {

    /**
     * Original proxy listener set whose ordinary set behavior is preserved.
     */
    private final Set<Channel> delegate;

    /**
     * Wraps the existing listener set without copying or replacing its channel entries.
     *
     * @param delegate live BungeeCord listener set
     */
    public InjectedSet(Set<Channel> delegate) {
        this.delegate = delegate;
    }

    /**
     * Adds a proxy listener and schedules UDP setup after the proxy finishes initializing it.
     *
     * @param element newly bound proxy listener channel
     * @return whether the backing set gained a new element
     */
    @Override
    public boolean add(Channel element) {
        ProxyServer.getInstance().getScheduler().schedule(
                RaknetifyBungeePlugin.INSTANCE,
                () -> BungeeRaknetifyServer.injectChannel((BungeeCord) ProxyServer.getInstance(), element, false),
                100, TimeUnit.MILLISECONDS);

        return super.add(element);
    }

    /**
     * Closes companion UDP listeners before clearing the proxy's listener set.
     */
    @Override
    public void clear() {
        BungeeRaknetifyServer.stopAll();

        super.clear();
    }

    /**
     * Returns the original set used for operations without lifecycle hooks.
     *
     * @return live proxy listener set wrapped by this instance
     */
    @Override
    protected Set<Channel> delegate() {
        return this.delegate;
    }
}
