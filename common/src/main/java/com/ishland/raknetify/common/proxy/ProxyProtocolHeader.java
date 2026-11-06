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

import io.netty.buffer.ByteBuf;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.zip.CRC32C;

/**
 * Decoded PROXY v2 UDP identity and the exact number of bytes preceding the RakNet payload.
 *
 * @param source original client endpoint, or physical sender for a LOCAL command
 * @param destination original public endpoint, or local listener for a LOCAL command
 * @param length complete header size including optional TLVs
 */
record ProxyProtocolHeader(InetSocketAddress source, InetSocketAddress destination, int length) {
    /** Fixed v2 signature, deliberately distinct from all valid RakNet packet identifiers. */
    private static final byte[] SIGNATURE = {13, 10, 13, 10, 0, 13, 10, 81, 85, 73, 84, 10};

    /**
     * Recognizes a full signature without moving the buffer reader index.
     *
     * @param input complete received UDP datagram
     * @return whether the packet starts with the v2 signature
     */
    static boolean present(final ByteBuf input) {
        if (input.readableBytes() < SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (input.getByte(input.readerIndex() + i) != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validates one complete datagram header before exposing any claimed address.
     *
     * @param input received bytes, whose reader index remains unchanged
     * @param physical actual UDP peer used for LOCAL health checks
     * @param local actual listener endpoint used for LOCAL health checks
     * @return decoded identity and payload offset
     * @throws IllegalArgumentException if the header is truncated, malformed or uses a non-UDP
     *     protocol
     */
    static ProxyProtocolHeader decode(
            final ByteBuf input, final InetSocketAddress physical, final InetSocketAddress local) {
        int start = input.readerIndex();
        if (!present(input) || input.readableBytes() < 16) {
            throw new IllegalArgumentException("Truncated PROXY header");
        }
        int command = input.getUnsignedByte(start + 12);
        int length = 16 + input.getUnsignedShort(start + 14);
        if (length > input.readableBytes()) {
            throw new IllegalArgumentException("Truncated PROXY address block");
        }
        if (command == 0x20) {
            return new ProxyProtocolHeader(physical, local, length);
        }
        if (command != 0x21) {
            throw new IllegalArgumentException("Invalid PROXY version or command");
        }
        int width = addressWidth(input.getUnsignedByte(start + 13));
        int addressEnd = 16 + width * 2 + 4;
        if (length < addressEnd) {
            throw new IllegalArgumentException("Truncated UDP addresses");
        }
        InetSocketAddress source = address(input, start + 16, width, start + 16 + width * 2);
        InetSocketAddress destination =
                address(input, start + 16 + width, width, start + 18 + width * 2);
        validateTlvs(input, start, addressEnd, length);
        return new ProxyProtocolHeader(source, destination, length);
    }

    /**
     * Restricts forwarded identities to IPv4 or IPv6 datagrams rather than silently accepting TCP
     * headers.
     *
     * @param family combined address family and transport protocol byte
     * @return address width in bytes
     * @throws IllegalArgumentException if the header does not describe supported UDP endpoints
     */
    private static int addressWidth(final int family) {
        if (family == 0x12) {
            return 4;
        }
        if (family == 0x22) {
            return 16;
        }
        throw new IllegalArgumentException("Expected PROXY UDP4 or UDP6");
    }

    /**
     * Constructs a resolved numeric endpoint without performing DNS requests.
     *
     * @param input validated address block
     * @param offset first address byte
     * @param width address width selected by the family byte
     * @param portOffset unsigned network-order port position
     * @return numeric socket address
     * @throws IllegalArgumentException if an unusable address or zero port is asserted
     */
    private static InetSocketAddress address(
            final ByteBuf input, final int offset, final int width, final int portOffset) {
        byte[] bytes = new byte[width];
        input.getBytes(offset, bytes);
        try {
            InetAddress address = InetAddress.getByAddress(bytes);
            int port = input.getUnsignedShort(portOffset);
            if (address.isAnyLocalAddress() || address.isMulticastAddress() || port == 0) {
                throw new IllegalArgumentException("Unusable forwarded UDP endpoint");
            }
            return new InetSocketAddress(address, port);
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException("Invalid IP address width", impossible);
        }
    }

    /**
     * Bounds every TLV and validates an optional CRC32C instead of treating corrupt metadata as
     * trusted.
     *
     * @param input complete packet
     * @param start header's buffer offset
     * @param offset first TLV relative to the header
     * @param length total header size
     * @throws IllegalArgumentException if a TLV crosses the header boundary or a checksum is
     *     invalid
     */
    private static void validateTlvs(
            final ByteBuf input, final int start, final int offset, final int length) {
        int cursor = offset;
        boolean checksumSeen = false;
        while (cursor < length) {
            if (length - cursor < 3) {
                throw new IllegalArgumentException("Truncated PROXY TLV");
            }
            int type = input.getUnsignedByte(start + cursor);
            int size = input.getUnsignedShort(start + cursor + 1);
            cursor += 3;
            if (size > length - cursor) {
                throw new IllegalArgumentException("Truncated PROXY TLV value");
            }
            if (type == 3) {
                if (checksumSeen || size != 4) {
                    throw new IllegalArgumentException("Invalid PROXY checksum TLV");
                }
                validateChecksum(input, start, cursor, length);
                checksumSeen = true;
            }
            cursor += size;
        }
    }

    /**
     * Computes CRC32C over only the header while treating its checksum field as four zero bytes.
     *
     * @param input immutable received packet
     * @param start header's buffer offset
     * @param checksumOffset checksum value relative to the header
     * @param length total header size excluding application bytes
     * @throws IllegalArgumentException if the asserted checksum differs
     */
    private static void validateChecksum(
            final ByteBuf input, final int start, final int checksumOffset, final int length) {
        CRC32C crc = new CRC32C();
        for (int i = 0; i < length; i++) {
            crc.update(
                    i >= checksumOffset && i < checksumOffset + 4
                            ? 0
                            : input.getUnsignedByte(start + i));
        }
        if (crc.getValue() != input.getUnsignedInt(start + checksumOffset)) {
            throw new IllegalArgumentException("PROXY checksum mismatch");
        }
    }
}
