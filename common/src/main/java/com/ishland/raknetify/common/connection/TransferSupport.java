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

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Coordinates a versioned login query and opt-in destinations without changing authentication or
 * authorization.
 */
public final class TransferSupport {

    /**
     * Empty query and handled response mean support for automatic RakNet transfer addresses,
     * version one.
     */
    public static final String QUERY_ID = "raknetify:transfer_v1";

    /**
     * Per-connection support is deliberately independent of account identity and admission
     * decisions.
     */
    private static final AttributeKey<Boolean> CAPABLE =
            AttributeKey.valueOf("raknetify:transfer_v1");

    /** Startup-only operator policy. No destinations are inferred from a client's response. */
    private static volatile TransferDestinations destinations = TransferDestinations.EMPTY;

    /** Prevents instantiating the static platform bridge. */
    private TransferSupport() {}

    /**
     * Loads the optional server policy once the platform's configuration directory is available.
     *
     * @param configDirectory Fabric configuration directory
     * @throws IllegalStateException if an existing configuration cannot be read or validated
     */
    public static void initialize(final Path configDirectory) {
        try {
            destinations =
                    TransferDestinations.load(configDirectory.resolve("raknetify2-transfers.json"));
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Cannot load Raknetify2 transfer destinations", invalid);
        }
    }

    /**
     * Allows login hooks to skip negotiation entirely on servers that have not opted in.
     *
     * @return whether the current server policy has any destinations
     */
    public static boolean enabled() {
        return destinations.enabled();
    }

    /**
     * Applies the server's policy using only this connection's capability result.
     *
     * @param channel connection receiving the outgoing transfer
     * @param host original server-selected host
     * @param port original server-selected TCP port
     * @return replacement RakNet address, or null to preserve the original packet
     */
    public static TransferDestinations.Endpoint select(
            final Channel channel, final String host, final int port) {
        return destinations.select(
                host, port, channel != null && Boolean.TRUE.equals(channel.attr(CAPABLE).get()));
    }

    /**
     * Tracks one login query. A response advertises a transport feature, never permission to enter
     * a server. The login tick retains Minecraft's ordinary timeout while waiting for the reply.
     */
    public static final class Negotiation {

        /**
         * High randomized IDs avoid the small sequential IDs commonly allocated by login networking
         * mods.
         */
        private final int transactionId =
                ThreadLocalRandom.current().nextInt(0x40000000, Integer.MAX_VALUE);

        /** Set before sending the query so an immediate reply can be recognized. */
        private volatile boolean started;

        /** Published from the network thread before the login tick is allowed to continue. */
        private volatile boolean answered;

        /**
         * Claims the single query send for this login listener.
         *
         * @return true exactly once, before the packet should be sent
         */
        public boolean start() {
            if (this.started) {
                return false;
            }
            this.started = true;
            return true;
        }

        /**
         * Supplies the transaction ID that the platform places in its native login query packet.
         *
         * @return this connection's positive query identifier
         */
        public int transactionId() {
            return this.transactionId;
        }

        /**
         * Keeps login in its existing verification state until the owned query receives a response.
         *
         * @return whether verification must wait for the network reply
         */
        public boolean waiting() {
            return this.started && !this.answered;
        }

        /**
         * Consumes only the outstanding owned reply. Unhandled replies still finish negotiation and
         * retain TCP transfers.
         *
         * @param id response transaction identifier
         * @param handled whether the native decoder observed an exact handled-empty response
         * @param channel connection whose future transfer packets may use the advertised feature
         * @return true only for the first matching reply, so unrelated mods retain their own
         *     responses
         */
        public boolean answer(final int id, final boolean handled, final Channel channel) {
            if (!waiting() || id != this.transactionId) {
                return false;
            }
            channel.attr(CAPABLE).set(handled);
            this.answered = true;
            return true;
        }
    }
}
