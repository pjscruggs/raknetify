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

package com.ishland.raknetify.common.util;

import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import it.unimi.dsi.fastutil.objects.ReferenceSets;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polls local interface addresses so wildcard UDP listeners can follow network changes. Callbacks
 * run on a shared polling thread. Consumers must dispatch channel mutations to the appropriate
 * event loop or platform scheduler.
 */
public class NetworkInterfaceListener {

    /** Single daemon polling thread for interface enumeration and notification. */
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread thread = new Thread(r, "Raknetify IFListener");
                thread.setDaemon(true);
                return thread;
            });

    /** Addresses seen on active interfaces at the last poll, owned by the polling thread. */
    private static final ObjectOpenHashSet<InetAddress> knownAddresses = new ObjectOpenHashSet<>();

    /** Identity-based synchronized callback set, allowing platform-specific listener removal. */
    private static final ReferenceSet<Consumer<InterfaceAddressChangeEvent>> listeners =
            ReferenceSets.synchronize(new ReferenceOpenHashSet<>());

    /**
     * Polling failures are logged with context rather than written directly to the process error
     * stream.
     */
    private static final System.Logger LOGGER =
            System.getLogger(NetworkInterfaceListener.class.getName());

    /** Scheduled task retained so initialization can detect an unexpectedly terminated poller. */
    private static final java.util.concurrent.ScheduledFuture<?> POLLING =
            scheduler.scheduleAtFixedRate(
                    NetworkInterfaceListener::pollChanges, 0, 10, TimeUnit.SECONDS);

    /**
     * Verifies that the shared interface poller has not terminated unexpectedly.
     *
     * @throws IllegalStateException if polling terminated or the caller was interrupted while
     *     checking it
     */
    public static void init() {
        if (!POLLING.isDone()) {
            return;
        }
        try {
            POLLING.get();
            throw new IllegalStateException("Network interface polling stopped");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while checking interface polling", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("Network interface polling failed", exception);
        }
    }

    /**
     * Compares active interface addresses with the previous snapshot and publishes additions and
     * removals. Enumeration or callback failures are logged so the scheduled task can run again.
     */
    private static void pollChanges() {
        try {
            final List<NetworkInterface> networkInterfaces =
                    NetworkInterface.networkInterfaces().toList();
            ObjectOpenHashSet<InetAddress> currentAddresses = new ObjectOpenHashSet<>();
            for (NetworkInterface networkInterface : networkInterfaces) {
                if (networkInterface.isUp()) {
                    for (InetAddress address : networkInterface.inetAddresses().toList()) {
                        currentAddresses.add(address);
                        if (knownAddresses.add(address)) {
                            listeners.forEach(consumer ->
                                    consumer.accept(
                                            new InterfaceAddressChangeEvent(true, address)));
                        }
                    }
                }
            }
            final ObjectIterator<InetAddress> iterator = knownAddresses.iterator();
            while (iterator.hasNext()) {
                final InetAddress address = iterator.next();
                if (!currentAddresses.contains(address)) {
                    iterator.remove();
                    listeners.forEach(consumer ->
                            consumer.accept(new InterfaceAddressChangeEvent(false, address)));
                }
            }
        } catch (java.net.SocketException | RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Cannot update local interface addresses",
                    exception);
        }
    }

    /**
     * Registers a callback for subsequent address changes without replaying the current snapshot.
     *
     * @param consumer callback invoked on the shared interface polling thread
     */
    public static void addListener(final Consumer<InterfaceAddressChangeEvent> consumer) {
        init();
        listeners.add(consumer);
    }

    /**
     * Unregisters the exact callback instance previously supplied to addListener.
     *
     * @param consumer callback identity to remove
     */
    public static void removeListener(final Consumer<InterfaceAddressChangeEvent> consumer) {
        listeners.remove(consumer);
    }

    /**
     * Describes an address entering or leaving the set of active local interfaces.
     *
     * @param added true for an address addition, false for a removal
     * @param address local IPv4 or IPv6 address affected by the change
     */
    public record InterfaceAddressChangeEvent(boolean added, InetAddress address) {}
}
