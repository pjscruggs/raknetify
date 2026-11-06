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

package com.ishland.raknetify.common.proxy;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded identity bindings shared by a listener and its connected per-client UDP sockets. */
final class ProxySessions {
    /** An unfinished handshake cannot retain its proxy address indefinitely. */
    private static final long HANDSHAKE_TIMEOUT = TimeUnit.SECONDS.toNanos(30);

    /**
     * Caps memory used by trusted proxies that send packets without completing RakNet handshakes.
     */
    private static final int MAX_SESSIONS = 4096;

    /** Physical proxy endpoints own immutable forwarded identities until their channel closes. */
    private final Map<InetSocketAddress, Session> sessions = new HashMap<>();

    /** Monotonic time source also permits deterministic expiry tests. */
    private final LongSupplier clock;

    /**
     * Creates a listener-local table whose operations serialize across its child event loops.
     *
     * @param clock monotonic nanosecond supplier
     */
    ProxySessions(final LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Accepts repeated identical headers but never changes the identity of an existing connection.
     *
     * @param physical actual proxy endpoint, including its per-client source port
     * @param header complete validated header, or null for a later headerless datagram
     * @return accepted binding, or null for missing, conflicting, expired or over-capacity sessions
     */
    synchronized Session resolve(
            final InetSocketAddress physical, final ProxyProtocolHeader header) {
        long now = clock.getAsLong();
        Session session = sessions.get(physical);
        if (session != null && session.expired(now)) {
            sessions.remove(physical);
            session = null;
        }
        if (session != null) {
            return session.matches(header) ? session : null;
        }
        if (header == null) {
            return null;
        }
        // Resolve the requested tuple eagerly, but sweep unrelated tuples only when capacity is
        // needed.
        if (sessions.size() >= MAX_SESSIONS) {
            sessions.values().removeIf(value -> value.expired(now));
        }
        if (sessions.size() >= MAX_SESSIONS) {
            return null;
        }
        session = new Session(header.source(), header.destination(), now);
        sessions.put(physical, session);
        return session;
    }

    /**
     * Pins an identity before native login reads the new channel's remote address.
     *
     * @param physical physical peer used by RakNet's routing map
     * @return immutable session selected by the validated handshake
     * @throws IllegalStateException if no validated identity precedes channel creation
     */
    synchronized Session claim(final InetSocketAddress physical) {
        Session session = resolve(physical, null);
        if (session == null) {
            throw new IllegalStateException("Missing validated UDP proxy identity");
        }
        session.active = true;
        return session;
    }

    /**
     * Keeps connectionless voice routing alive while its validated proxy continues sending
     * datagrams. Voice has no RakNet child to pin a binding, so inactive voice routes retain the
     * bounded idle timeout.
     *
     * @param physical trusted proxy endpoint already resolved for this datagram
     */
    synchronized void voiceActivity(final InetSocketAddress physical) {
        Session session = sessions.get(physical);
        if (session != null) {
            session.created = clock.getAsLong();
        }
    }

    /**
     * Releases only the closing channel's binding, never a later connection reusing the same proxy
     * port.
     *
     * @param physical physical endpoint of the closing connection
     * @param session exact identity object claimed by that connection
     */
    synchronized void release(final InetSocketAddress physical, final Session session) {
        sessions.remove(physical, session);
    }

    /** Identity pinned to a physical proxy tuple, with a bounded pre-connection lifetime. */
    static final class Session {
        /** Client endpoint shown to native admission, bans and plugin APIs. */
        final InetSocketAddress source;

        /** Original public destination used to reject inconsistent repeated headers. */
        private final InetSocketAddress destination;

        /**
         * Initial header arrival or last voice datagram, never refreshed by unfinished RakNet
         * handshakes.
         */
        private long created;

        /**
         * Active channels retain their binding until close rather than expiring during idle
         * gameplay.
         */
        private boolean active;

        /**
         * Records one validated proxy identity before a RakNet child exists.
         *
         * @param source original client endpoint
         * @param destination original public destination
         * @param created monotonic arrival time
         */
        private Session(
                final InetSocketAddress source,
                final InetSocketAddress destination,
                final long created) {
            this.source = source;
            this.destination = destination;
            this.created = created;
        }

        /**
         * Allows first-packet-only proxies and identical per-datagram headers without rebinding
         * identity.
         *
         * @param header optional repeated header
         * @return whether the packet preserves this session's endpoints
         */
        private boolean matches(final ProxyProtocolHeader header) {
            return header == null
                    || source.equals(header.source()) && destination.equals(header.destination());
        }

        /**
         * Expires abandoned handshakes while leaving established channel lifetime to RakNet.
         *
         * @param now current monotonic time
         * @return whether an unfinished session exceeded its fixed deadline
         */
        private boolean expired(final long now) {
            return !active && now - created >= HANDSHAKE_TIMEOUT;
        }
    }
}
