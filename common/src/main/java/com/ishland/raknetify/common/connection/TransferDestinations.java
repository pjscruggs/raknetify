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

import com.google.common.net.InetAddresses;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.util.PrefixUtil;

import java.io.IOException;
import java.net.IDN;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Exact server-owned transfer rules. Matching never performs DNS or trusts a client-supplied
 * destination.
 */
public final class TransferDestinations {

    /** Disabled policy used when the optional configuration file is absent. */
    public static final TransferDestinations EMPTY = new TransferDestinations(Map.of());

    /** Bounds configuration reads before parsing operator-controlled JSON. */
    private static final int MAX_BYTES = 65536;

    /** Limits accidental oversized destination lists while allowing a multi-server network. */
    private static final int MAX_DESTINATIONS = 128;

    /** Canonical host and TCP port map to the explicitly configured UDP port. */
    private final Map<Endpoint, Integer> destinations;

    /**
     * Freezes validated destinations so a login and its later transfer use a stable policy.
     *
     * @param destinations canonical endpoints and their UDP ports
     */
    private TransferDestinations(final Map<Endpoint, Integer> destinations) {
        this.destinations = Map.copyOf(destinations);
    }

    /**
     * Loads optional JSON once at startup. Invalid files stop initialization rather than partially
     * applying rules.
     *
     * @param path operator configuration file, normally config/raknetify2-transfers.json
     * @return validated policy, or the disabled policy when the file does not exist
     * @throws IOException if the file cannot be read or exceeds the size limit
     * @throws IllegalArgumentException if its contents do not match the destination schema
     */
    public static TransferDestinations load(final Path path) throws IOException {
        if (!Files.exists(path)) {
            return EMPTY;
        }
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("Transfer configuration exceeds 64 KiB: " + path);
            }
            return parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * Parses destinations with host, tcpPort and udpPort fields. Duplicate endpoints and unknown
     * fields are errors.
     *
     * @param json complete configuration document
     * @return immutable exact-match rules
     * @throws IllegalArgumentException if a destination, port or document shape is invalid
     */
    public static TransferDestinations parse(final String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            requireKeys(root, Set.of("destinations"));
            var entries = root.getAsJsonArray("destinations");
            if (entries.size() > MAX_DESTINATIONS) {
                throw new IllegalArgumentException("Too many transfer destinations");
            }
            Map<Endpoint, Integer> rules = new LinkedHashMap<>();
            for (JsonElement entry : entries) {
                add(rules, entry.getAsJsonObject());
            }
            return new TransferDestinations(rules);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid Raknetify2 transfer configuration", invalid);
        }
    }

    /**
     * Rejects misspelled options and missing fields instead of silently selecting a different
     * transport.
     *
     * @param object JSON object at the current schema level
     * @param expected complete set of permitted and required keys
     * @throws IllegalArgumentException if keys differ from the schema
     */
    private static void requireKeys(final JsonObject object, final Set<String> expected) {
        if (!object.keySet().equals(expected)) {
            throw new IllegalArgumentException("Expected fields " + expected);
        }
    }

    /**
     * Validates one destination and rejects aliases that collide after host normalization.
     *
     * @param rules destinations parsed so far
     * @param entry host and the two explicitly selected ports
     * @throws IllegalArgumentException if the entry is malformed or duplicates an endpoint
     */
    private static void add(final Map<Endpoint, Integer> rules, final JsonObject entry) {
        requireKeys(entry, Set.of("host", "tcpPort", "udpPort"));
        if (!entry.get("host").getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Host must be a string");
        }
        Endpoint key =
                new Endpoint(
                        canonicalHost(entry.get("host").getAsString()), port(entry.get("tcpPort")));
        if (rules.putIfAbsent(key, port(entry.get("udpPort"))) != null) {
            throw new IllegalArgumentException("Duplicate transfer destination " + key);
        }
    }

    /**
     * Accepts only integer JSON ports in the usable socket range.
     *
     * @param value configured TCP or UDP port
     * @return validated port number
     * @throws IllegalArgumentException if the value is not an integer between 1 and 65535
     */
    private static int port(final JsonElement value) {
        if (!value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Port must be a number");
        }
        int result = value.getAsBigDecimal().intValueExact();
        if (result < 1 || result > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return result;
    }

    /**
     * Normalizes DNS names and IPv6 literals without resolving them or allowing wildcard rules.
     *
     * @param host unprefixed hostname or IP literal, optionally bracketed for IPv6
     * @return lowercase ASCII hostname or canonical IPv6 literal
     * @throws IllegalArgumentException if the hostname is empty, invalid or includes a transport
     *     prefix
     */
    private static String canonicalHost(final String host) {
        if (host.contains("%")) {
            throw new IllegalArgumentException("Scoped IP addresses are not transfer destinations");
        }
        String unbracketed =
                host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1)
                        : host;
        if (unbracketed.contains(":")) {
            return InetAddresses.toAddrString(InetAddresses.forString(unbracketed));
        }
        String ascii = IDN.toASCII(unbracketed, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        if (ascii.endsWith(".")) {
            ascii = ascii.substring(0, ascii.length() - 1);
        }
        if (ascii.isEmpty() || ascii.length() > 253) {
            throw new IllegalArgumentException("Invalid transfer hostname");
        }
        return ascii;
    }

    /**
     * Indicates whether asking clients about transfer support can affect any outgoing transfer.
     *
     * @return true when at least one destination was explicitly configured
     */
    public boolean enabled() {
        return !this.destinations.isEmpty();
    }

    /**
     * Selects UDP only for a capable client and an exact configured TCP destination. Existing
     * explicit RakNet transfers and unmatched addresses retain their original packet.
     *
     * @param host destination from the server's transfer packet
     * @param tcpPort destination port from that packet
     * @param capable whether this connection answered the versioned transfer query
     * @return replacement endpoint, or null when no automatic rewrite is appropriate
     */
    public Endpoint select(final String host, final int tcpPort, final boolean capable) {
        if (!capable || PrefixUtil.getInfo(host).useRakNet()) {
            return null;
        }
        final String canonical;
        try {
            canonical = canonicalHost(host);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        Integer udpPort = this.destinations.get(new Endpoint(canonical, tcpPort));
        return udpPort == null ? null : new Endpoint(Constants.RAKNET_PREFIX + host, udpPort);
    }

    /**
     * Identifies a transfer destination without resolving it or establishing a connection.
     *
     * @param host host text, including a RakNet prefix only for a selected replacement
     * @param port TCP lookup port or the selected UDP replacement port
     */
    public record Endpoint(String host, int port) {}
}
