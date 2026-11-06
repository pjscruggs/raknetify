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

package com.ishland.raknetify.common.voice;

import com.ishland.raknetify.common.proxy.ProxyProtocol;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual UDP checks cover unchanged voice bytes, socket ownership, bounded queues and restart races. */
@Timeout(25)
class VoiceSocketTest {
    /** Isolated configuration paths prevent tests from reading or changing an operator's settings. */
    @TempDir
    Path temporary;

    /**
     * Voice shares the actual bound port and closing either side has the intended independent lifetime.
     * @param host IPv4 or IPv6 loopback interface used by both unmodified endpoints
     * @throws Exception if real UDP delivery, wakeup or cleanup fails
     */
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void realDatagramsAndIndependentShutdown(String host) throws Exception {
        NioEventLoopGroup loops = new NioEventLoopGroup(1);
        Channel server = null;
        SharedVoiceSocket voice = new SharedVoiceSocket();
        try (DatagramSocket client = new DatagramSocket(new InetSocketAddress(host, 0))) {
            server = new ServerBootstrap().group(loops)
                    .channelFactory(() -> ProxyProtocol.create(NioDatagramChannel::new))
                    .childHandler(new ChannelInboundHandlerAdapter())
                    .bind(host, 0).sync().channel();
            InetSocketAddress local = (InetSocketAddress) server.localAddress();
            voice.open(local.getPort(), host);
            assertEquals(local.getPort(), voice.getLocalPort());
            exchange(client, local, voice);
            assertThrows(SocketException.class, () -> new SharedVoiceSocket().open(local.getPort(), host));
            voice.close();
            assertTrue(server.isActive(), "Closing voice must not close gameplay");
            SharedVoiceSocket replacement = new SharedVoiceSocket();
            replacement.open(local.getPort(), host);
            try {
                exchange(client, local, replacement);
                assertThrows(SocketException.class, voice::read);
                CompletableFuture<Boolean> stopped = blockedReader(replacement);
                server.close().sync();
                assertTrue(stopped.get(2, TimeUnit.SECONDS), "Listener close must wake voice's blocked read");
                assertTrue(replacement.isClosed());
            } finally {
                replacement.close();
            }
        } finally {
            voice.close();
            if (server != null) server.close().sync();
            loops.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Exchanges exact encrypted-shaped datagrams without relying on a second public UDP socket.
     * @param client independent operating-system socket representing an unmodified voice client
     * @param server shared public endpoint
     * @param voice API adapter attached to the game listener
     * @throws Exception if the payload, sender, timestamp or bounded reply differs
     */
    private static void exchange(DatagramSocket client, InetSocketAddress server, SharedVoiceSocket voice) throws Exception {
        byte[] bytes = {(byte) 0xff, 1, 3, 5, 7};
        long before = System.currentTimeMillis();
        client.send(new DatagramPacket(bytes, bytes.length, server));
        var received = voice.read();
        assertArrayEquals(bytes, received.getData());
        assertEquals(client.getLocalSocketAddress(), received.getSocketAddress());
        assertTrue(received.getTimestamp() >= before);
        voice.send(received.getData(), received.getSocketAddress());
        client.setSoTimeout(2000);
        DatagramPacket reply = new DatagramPacket(new byte[16], 16);
        client.receive(reply);
        assertEquals(server, reply.getSocketAddress());
        assertArrayEquals(bytes, java.util.Arrays.copyOf(reply.getData(), reply.getLength()));
    }

    /**
     * Blocks an independent consumer and reports whether socket closure wakes it with the expected exception.
     * @param voice open voice attachment
     * @return bounded test future completed by the reader
     */
    private static CompletableFuture<Boolean> blockedReader(SharedVoiceSocket voice) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                voice.read();
                return false;
            } catch (SocketException closed) {
                return true;
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
    }

    /**
     * Saturating unauthenticated input never blocks Netty or leaks pooled buffers, and restart cannot read old bytes.
     * @throws Exception if bounded inbox delivery or shutdown fails
     */
    @Test
    void boundedInboxCopiesAndReleasesInputs() throws Exception {
        var inbox = new VoiceHub.Inbox();
        InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 12345);
        for (int i = 0; i < VoiceHub.MAX_QUEUED + 10; i++) {
            var bytes = Unpooled.buffer().writeByte(0xff).writeInt(i);
            var packet = new io.netty.channel.socket.DatagramPacket(bytes, peer, peer);
            inbox.offer(packet);
            bytes.setInt(1, -1);
            packet.release();
            assertEquals(0, bytes.refCnt());
        }
        for (int i = 0; i < VoiceHub.MAX_QUEUED; i++) {
            var received = inbox.read();
            assertEquals(i, java.nio.ByteBuffer.wrap(received.data(), 1, 4).getInt());
        }
        inbox.close();
        assertThrows(SocketException.class, inbox::read);
    }

    /**
     * Explicit configuration accepts only boolean opt-in and never silently enables sharing after malformed input.
     * @throws Exception if temporary configuration cannot be written or read
     */
    @Test
    void configurationAndSocketLifecycleFailClosed() throws Exception {
        Path config = temporary.resolve("voice.json");
        assertFalse(VoicePlugin.enabled(config));
        Files.writeString(config, "{\"enabled\":true}");
        assertTrue(VoicePlugin.enabled(config));
        Files.writeString(config, "{\"enabled\":false}");
        assertFalse(VoicePlugin.enabled(config));
        Files.writeString(config, "{\"enabled\":\"true\"}");
        assertThrows(IllegalArgumentException.class, () -> VoicePlugin.enabled(config));
        Files.writeString(config, "{\"enabled\":true,\"typo\":false}");
        assertThrows(IllegalArgumentException.class, () -> VoicePlugin.enabled(config));
        Files.writeString(config, " ".repeat(1025));
        assertThrows(java.io.IOException.class, () -> VoicePlugin.enabled(config));
        SharedVoiceSocket socket = new SharedVoiceSocket();
        socket.close();
        assertThrows(SocketException.class, () -> socket.open(12345, ""));
        assertThrows(SocketException.class, socket::read);
    }

    /**
     * A stop during deferred startup wakes immediately instead of waiting for the absent listener's deadline.
     * @throws Exception if cancellation fails to complete within the bounded shutdown window
     */
    @Test
    void closeCancelsPendingStartup() throws Exception {
        SharedVoiceSocket socket = new SharedVoiceSocket();
        var opening = CompletableFuture.supplyAsync(() -> {
            try {
                socket.open(12345, "127.0.0.1");
                return false;
            } catch (SocketException expected) {
                return true;
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        socket.close();
        assertTrue(opening.get(2, TimeUnit.SECONDS));
        assertTrue(socket.isClosed());
    }
}
