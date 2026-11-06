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

import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayDeque;

/** Owns bounded copied voice datagrams while the existing RakNet socket retains all network resources. */
final class VoiceHub {
    /** Matches Simple Voice Chat's receive buffer, which rejects lengths of 4096 or greater. */
    static final int MAX_PACKET = 4095;
    /** Caps unauthenticated queued bytes below one MiB without blocking gameplay's event loop. */
    static final int MAX_QUEUED = 256;
    /** The primary socket remains the reply path even after RakNet creates connected child sockets. */
    private DatagramChannel channel;
    /** At most one voice server may consume this listener's packets. */
    private Inbox subscriber;

    /**
     * Records the already bound physical socket without acquiring another port or thread.
     * @param socket active primary datagram channel
     */
    synchronized void start(DatagramChannel socket) {
        channel = socket;
    }

    /**
     * Returns the physical listener for registry matching and bounded writes.
     * @return primary datagram channel
     */
    synchronized DatagramChannel channel() {
        return channel;
    }

    /**
     * Claims an exclusive subscription while allowing a stopped voice server to restart.
     * @return fresh inbox belonging to this attachment
     * @throws SocketException if the listener closed or another voice server still owns the inbox
     */
    synchronized Inbox attach() throws SocketException {
        if (channel == null || !channel.isActive()) throw new SocketException("RakNet listener closed");
        if (subscriber != null && !subscriber.closed()) throw new SocketException("RakNet listener already has a voice socket");
        subscriber = new Inbox();
        return subscriber;
    }

    /**
     * Drops oversized or overflowing UDP input instead of retaining pooled buffers or blocking Netty.
     * @param packet validated transport envelope whose ownership remains with its caller
     */
    synchronized void receive(DatagramPacket packet) {
        if (subscriber == null || packet.content().readableBytes() > MAX_PACKET) return;
        subscriber.offer(packet);
    }

    /** Wakes blocked readers and releases queued heap bytes when the game listener closes. */
    synchronized void shutdown() {
        if (subscriber != null) subscriber.close();
    }

    /**
     * Copies outgoing bytes before Netty takes ownership and reports actual socket write failure.
     * @param inbox caller's attachment, preventing a stale socket from writing after restart
     * @param data complete Simple Voice Chat wire packet
     * @param address physical peer endpoint used by the voice authentication protocol
     * @throws Exception if the attachment is stale, the packet is invalid or its bounded write fails
     * @throws SocketException if the attachment or datagram is invalid or the write fails
     * @throws IllegalStateException if invoked from the physical socket's own event loop
     */
    void send(Inbox inbox, byte[] data, InetSocketAddress address) throws Exception {
        DatagramChannel socket;
        synchronized (this) {
            if (subscriber != inbox || inbox.closed()) throw new SocketException("Voice socket closed");
            socket = channel;
        }
        if (data.length == 0 || data.length > MAX_PACKET) {
            throw new SocketException("Invalid voice datagram");
        }
        if (socket.eventLoop().inEventLoop()) throw new IllegalStateException("Voice writes must not block the network event loop");
        var write = socket.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(data.clone()), address));
        if (!write.await(5000)) throw new SocketException("Voice datagram write timed out");
        if (!write.isSuccess()) throw new SocketException("Voice datagram write failed: " + write.cause());
    }

    /**
     * Heap-owned bytes passed to the voice API without leaking a reference-counted Netty buffer.
     * @param data complete wire datagram
     * @param address physical sender used for replies
     * @param timestamp arrival time in epoch milliseconds, as expected by Simple Voice Chat
     */
    record Received(byte[] data, InetSocketAddress address, long timestamp) {
    }

    /** One attachment's bounded inbox and shutdown signal, independent of later voice restarts. */
    static final class Inbox {
        /** Copied packets waiting for the voice server's authentication and decoding thread. */
        private final ArrayDeque<Received> packets = new ArrayDeque<>();
        /** A closed inbox never accepts packets or becomes attached again. */
        private boolean closed;

        /**
         * Rejects excess packets before copying their payload and wakes the single consuming thread.
         * @param packet bounded incoming datagram
         */
        synchronized void offer(DatagramPacket packet) {
            if (closed || packets.size() >= MAX_QUEUED) return;
            byte[] data = new byte[packet.content().readableBytes()];
            packet.content().getBytes(packet.content().readerIndex(), data);
            packets.add(new Received(data, packet.sender(), System.currentTimeMillis()));
            notifyAll();
        }

        /**
         * Sleeps without polling until a packet arrives or shutdown invalidates this attachment.
         * @return oldest received datagram
         * @throws SocketException if shutdown wakes a blocked reader
         * @throws InterruptedException if the voice thread is interrupted during shutdown
         */
        synchronized Received read() throws SocketException, InterruptedException {
            while (!closed && packets.isEmpty()) wait();
            if (closed) throw new SocketException("Voice socket closed");
            return packets.remove();
        }

        /** Clears pending bytes and wakes every waiter without closing the game listener. */
        synchronized void close() {
            closed = true;
            packets.clear();
            notifyAll();
        }

        /**
         * Exposes shutdown to the voice server's receive loop.
         * @return whether this attachment is permanently closed
         */
        synchronized boolean closed() {
            return closed;
        }
    }
}
