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

import com.google.gson.JsonParser;

import io.netty.util.NetUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Immutable UDP proxy trust policy loaded before a listener can accept traffic. */
public final class ProxyProtocolConfig {
    /** An absent configuration preserves ordinary direct RakNet connections. */
    private static final ProxyProtocolConfig DISABLED = new ProxyProtocolConfig(List.of());

    /** Literal address prefixes that may assert another client's identity. */
    private final List<Subnet> trusted;

    /**
     * Freezes the trust list so connected sockets inherit their listener's exact policy.
     *
     * @param trusted validated literal address ranges
     */
    private ProxyProtocolConfig(final List<Subnet> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    /**
     * Reads the optional server configuration without accepting a partial or oversized policy.
     *
     * @param path configuration file beneath the server's config directory
     * @return disabled policy only when the file is absent
     * @throws IOException if reading fails or the document exceeds 64 KiB
     */
    public static ProxyProtocolConfig load(final Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) {
                throw new IOException("PROXY configuration exceeds 64 KiB");
            }
            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (NoSuchFileException absent) {
            return DISABLED;
        }
    }

    /**
     * Requires an explicit nonempty trust list and rejects unknown keys or DNS hostnames.
     *
     * @param json document containing only the trustedProxies array
     * @return enabled policy with at most 128 address prefixes
     * @throws IllegalArgumentException if any field or address is invalid
     */
    public static ProxyProtocolConfig parse(final String json) {
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.keySet().equals(Set.of("trustedProxies"))) {
                throw new IllegalArgumentException("Expected only trustedProxies");
            }
            var entries = root.getAsJsonArray("trustedProxies");
            if (entries.isEmpty() || entries.size() > 128) {
                throw new IllegalArgumentException("Expected 1 to 128 trusted proxy addresses");
            }
            List<Subnet> subnets = new ArrayList<>();
            for (var entry : entries) {
                if (!entry.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("Expected an IP or CIDR string");
                }
                subnets.add(Subnet.parse(entry.getAsString()));
            }
            return new ProxyProtocolConfig(subnets);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Invalid Raknetify2 PROXY protocol configuration", invalid);
        }
    }

    /**
     * Distinguishes a missing configuration from an enabled, restrictive listener.
     *
     * @return whether all UDP clients must arrive through a trusted proxy
     */
    public boolean enabled() {
        return !trusted.isEmpty();
    }

    /**
     * Matches the actual UDP sender without resolving names or inspecting claimed addresses.
     *
     * @param address physical sender's IP address
     * @return whether the sender belongs to an explicitly trusted subnet
     */
    boolean trusts(final InetAddress address) {
        return trusted.stream().anyMatch(subnet -> subnet.contains(address.getAddress()));
    }

    /** A binary IP prefix with no DNS lookup or implicit trust expansion. */
    private static final class Subnet {
        /** Privately owned prefix bytes which are never returned to callers. */
        private final byte[] address;

        /** Number of leading address bits required to match. */
        private final int bits;

        /**
         * Copies a validated prefix so later changes to a parser buffer cannot alter proxy trust.
         *
         * @param address binary IPv4 or IPv6 prefix
         * @param bits validated number of prefix bits
         */
        private Subnet(final byte[] address, final int bits) {
            this.address = address.clone();
            this.bits = bits;
        }

        /**
         * Accepts a literal IP and optional CIDR width, including exact IPv6 addresses.
         *
         * @param value operator-supplied address or prefix
         * @return parsed address prefix
         * @throws IllegalArgumentException if the literal or prefix width is invalid
         */
        private static Subnet parse(final String value) {
            String[] pieces = value.split("/", -1);
            byte[] address = NetUtil.createByteArrayFromIpAddressString(pieces[0]);
            if (pieces.length > 2 || address == null || value.contains("%")) {
                throw new IllegalArgumentException("Expected a literal IP address or CIDR");
            }
            int bits = pieces.length == 2 ? Integer.parseInt(pieces[1]) : address.length * 8;
            if (bits < 0 || bits > address.length * 8) {
                throw new IllegalArgumentException("Invalid CIDR width");
            }
            return new Subnet(address, bits);
        }

        /**
         * Compares the address family and each selected prefix byte, including a partial last byte.
         *
         * @param candidate physical sender's address bytes
         * @return whether the entire configured prefix matches
         */
        private boolean contains(final byte[] candidate) {
            if (candidate.length != address.length) {
                return false;
            }
            for (int offset = 0; offset < bits; offset += 8) {
                int mask = 0xff << (8 - Math.min(8, bits - offset));
                if ((candidate[offset / 8] & mask) != (address[offset / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }
}
