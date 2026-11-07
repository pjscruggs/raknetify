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

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/** Exercises binary header boundaries, address trust, identity lifetime and reference ownership. */
class ProxyProtocolTest {
    /** Physical proxy fixture, distinct from the claimed client address. */
    static final InetSocketAddress PROXY =
            new InetSocketAddress(io.netty.util.NetUtil.LOCALHOST4, 40000);

    /** Backend destination is deliberately different from the forwarded public destination. */
    static final InetSocketAddress LOCAL =
            new InetSocketAddress(io.netty.util.NetUtil.LOCALHOST4, 25565);

    /**
     * Non-routable documentation address proves replies cannot go directly to the claimed identity.
     */
    static final InetSocketAddress CLIENT = new InetSocketAddress("192.0.2.50", 12345);

    /**
     * Missing files disable proxy support, while malformed or accidentally broad schemas never do.
     *
     * @throws Exception if the numeric address fixture cannot be constructed
     */
    @Test
    void validatesTrustConfiguration() throws Exception {
        assertFalse(ProxyProtocolConfig.load(Path.of("absent-proxy-fixture.json")).enabled());
        var policy =
                ProxyProtocolConfig.parse(
                        "{\"trustedProxies\":[\"127.0.0.0/25\",\"2001:db8::/33\"]}");
        assertTrue(policy.trusts(new InetSocketAddress("127.0.0.127", 1).getAddress()));
        assertFalse(policy.trusts(new InetSocketAddress("127.0.0.128", 1).getAddress()));
        assertTrue(policy.trusts(new InetSocketAddress("2001:db8:7fff::1", 1).getAddress()));
        assertFalse(policy.trusts(new InetSocketAddress("2001:db8:8000::1", 1).getAddress()));
        for (String invalid :
                new String[] {
                    "{}",
                    "{\"trustedProxies\":[]}",
                    "{\"trustedProxies\":[\"localhost\"]}",
                    "{\"trustedProxies\":[\"127.0.0.1/33\"]}",
                    "{\"trustedProxies\":[\"127.0.0.1\"],\"enabled\":false}"
                }) {
            assertThrows(IllegalArgumentException.class, () -> ProxyProtocolConfig.parse(invalid));
        }
    }

    /**
     * Decodes both supported address families without consuming or modifying the caller's buffer.
     */
    @Test
    void decodesIpv4AndIpv6WithNonzeroReaderIndex() {
        for (String address : new String[] {"192.0.2.50", "2001:db8::50"}) {
            var expected = new InetSocketAddress(address, 12345);
            ByteBuf encoded = header(expected);
            ByteBuf input = Unpooled.buffer().writeLong(99).writeBytes(encoded).writeByte(5);
            encoded.release();
            input.readerIndex(8);
            try {
                var decoded = ProxyProtocolHeader.decode(input, PROXY, LOCAL);
                assertEquals(expected, decoded.source());
                assertEquals(8, input.readerIndex());
                assertEquals(5, input.getUnsignedByte(8 + decoded.length()));
            } finally {
                input.release();
            }
        }
    }

    /** Rejects every truncated prefix, wrong version, unknown command and TCP-family header. */
    @Test
    void rejectsTruncationAndWrongProtocol() {
        ByteBuf input = header(CLIENT);
        try {
            for (int length = 0; length < input.readableBytes(); length++) {
                ByteBuf truncated = input.slice(0, length);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ProxyProtocolHeader.decode(truncated, PROXY, LOCAL));
            }
            for (int command : new int[] {0x11, 0x22, 0x31}) {
                input.setByte(12, command);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> ProxyProtocolHeader.decode(input, PROXY, LOCAL));
            }
            input.setByte(12, 0x21).setByte(13, 0x11);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProxyProtocolHeader.decode(input, PROXY, LOCAL));
        } finally {
            input.release();
        }
    }

    /**
     * LOCAL commands skip their full declared body and retain the actual endpoint regardless of
     * address bytes.
     */
    @Test
    void localCommandNeverClaimsAnotherIdentity() {
        ByteBuf input = header(CLIENT);
        try {
            input.setByte(12, 0x20).setByte(13, 0xff);
            var decoded = ProxyProtocolHeader.decode(input, PROXY, LOCAL);
            assertEquals(PROXY, decoded.source());
            assertEquals(LOCAL, decoded.destination());
            assertEquals(28, decoded.length());
        } finally {
            input.release();
        }
    }

    /**
     * Verifies CRC32C across unknown TLVs and rejects corruption rather than ignoring the checksum.
     */
    @Test
    void checksTlvBoundsAndChecksum() {
        ByteBuf input = header(CLIENT);
        try {
            input.writeByte(0xe1).writeShort(2).writeShort(123);
            input.writeByte(3).writeShort(4);
            int checksum = input.writerIndex();
            input.writeInt(0);
            input.setShort(14, input.readableBytes() - 16);
            CRC32C crc = new CRC32C();
            for (int i = 0; i < input.readableBytes(); i++) {
                crc.update(input.getUnsignedByte(i));
            }
            input.setInt(checksum, (int) crc.getValue());
            assertEquals(CLIENT, ProxyProtocolHeader.decode(input, PROXY, LOCAL).source());
            input.setByte(17, input.getByte(17) ^ 1);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProxyProtocolHeader.decode(input, PROXY, LOCAL));
            input.setShort(29, 65535);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProxyProtocolHeader.decode(input, PROXY, LOCAL));
        } finally {
            input.release();
        }
    }

    /**
     * Trusted raw traffic needs an initial header, and all rejection paths release their input
     * exactly once.
     */
    @Test
    void enforcesTrustAndPreservesPhysicalRouting() {
        var table = new ProxySessions(System::nanoTime);
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyDatagramHandler(trusted(), table));
        try {
            assertDropped(channel, Unpooled.buffer().writeByte(5), PROXY);
            assertDropped(channel, header(CLIENT).writeByte(5), CLIENT);
            ByteBuf bytes = header(CLIENT).writeByte(5);
            assertTrue(channel.writeInbound(new DatagramPacket(bytes, LOCAL, PROXY)));
            DatagramPacket forwarded = channel.readInbound();
            assertEquals(PROXY, forwarded.sender());
            assertEquals(LOCAL, forwarded.recipient());
            assertEquals(5, forwarded.content().readUnsignedByte());
            forwarded.release();
            assertEquals(0, bytes.refCnt());
            assertTrue(
                    channel.writeInbound(
                            new DatagramPacket(Unpooled.buffer().writeByte(6), LOCAL, PROXY)));
            DatagramPacket plain = channel.readInbound();
            assertEquals(6, plain.content().readUnsignedByte());
            plain.release();
            assertDropped(
                    channel,
                    header(new InetSocketAddress("192.0.2.51", 12345)).writeByte(5),
                    PROXY);
            assertEquals(CLIENT, table.claim(PROXY).source);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Abandoned identities expire, live identities cannot rebind, and closing a channel permits
     * safe tuple reuse.
     */
    @Test
    void boundsSessionLifetimeAndCapacity() {
        AtomicLong clock = new AtomicLong();
        var sessions = new ProxySessions(clock::get);
        var header = new ProxyProtocolHeader(CLIENT, LOCAL, 28);
        assertNotNull(sessions.resolve(PROXY, header));
        clock.set(TimeUnit.SECONDS.toNanos(31));
        assertNull(sessions.resolve(PROXY, null));
        assertNotNull(sessions.resolve(PROXY, header));
        var active = sessions.claim(PROXY);
        clock.set(TimeUnit.DAYS.toNanos(1));
        assertSame(active, sessions.resolve(PROXY, null));
        sessions.release(PROXY, active);
        assertNull(sessions.resolve(PROXY, null));
        for (int port = 1; port <= 4096; port++) {
            assertNotNull(
                    sessions.resolve(
                            new InetSocketAddress(io.netty.util.NetUtil.LOCALHOST4, port), header));
        }
        assertNull(sessions.resolve(PROXY, header));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertNotNull(sessions.resolve(PROXY, header));
    }

    /**
     * Voice traffic refreshes its bounded idle lease without changing unfinished RakNet handshake
     * expiry.
     */
    @Test
    void voiceIdleLeaseDoesNotPinAbandonedRoutes() {
        AtomicLong clock = new AtomicLong();
        var sessions = new ProxySessions(clock::get);
        var header = new ProxyProtocolHeader(CLIENT, LOCAL, 28);
        assertNotNull(sessions.resolve(PROXY, header));
        for (int i = 0; i < 4; i++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(20));
            assertNotNull(sessions.resolve(PROXY, null));
            sessions.voiceActivity(PROXY);
        }
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertNull(sessions.resolve(PROXY, null));
    }

    /**
     * Builds a standards-shaped UDP header with a public destination distinct from the backend
     * bind.
     *
     * @param client synthetic client address
     * @return owned writable buffer containing only the complete header
     */
    static ByteBuf header(final InetSocketAddress client) {
        byte[] address = client.getAddress().getAddress();
        byte[] destination =
                new InetSocketAddress(address.length == 4 ? "198.51.100.8" : "2001:db8::8", 25565)
                        .getAddress()
                        .getAddress();
        return Unpooled.buffer()
                .writeBytes(new byte[] {13, 10, 13, 10, 0, 13, 10, 81, 85, 73, 84, 10})
                .writeByte(0x21)
                .writeByte(address.length == 4 ? 0x12 : 0x22)
                .writeShort(address.length * 2 + 4)
                .writeBytes(address)
                .writeBytes(destination)
                .writeShort(client.getPort())
                .writeShort(25565);
    }

    /**
     * Restricts the fixture to one literal loopback proxy rather than all loopback or private
     * networks.
     *
     * @return enabled exact-match policy
     */
    static ProxyProtocolConfig trusted() {
        return ProxyProtocolConfig.parse("{\"trustedProxies\":[\"127.0.0.1\"]}");
    }

    /**
     * Verifies both absence of downstream traffic and complete release of rejected datagrams.
     *
     * @param channel isolated decoder pipeline
     * @param bytes owned input buffer
     * @param sender asserted physical peer
     */
    private static void assertDropped(
            final EmbeddedChannel channel, final ByteBuf bytes, final InetSocketAddress sender) {
        assertFalse(channel.writeInbound(new DatagramPacket(bytes, LOCAL, sender)));
        assertEquals(0, bytes.refCnt());
    }
}
