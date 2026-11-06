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

import de.maxhenkel.voicechat.api.RawUdpPacket;
import de.maxhenkel.voicechat.api.VoicechatSocket;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;

/** Adapts an existing RakNet listener to Simple Voice Chat's supported server socket interface. */
final class SharedVoiceSocket implements VoicechatSocket {
    /** Listener selected once by the voice configuration's port and bind address. */
    private volatile VoiceHub hub;
    /** Attachment survives only until voice or the primary game listener closes. */
    private volatile VoiceHub.Inbox inbox;
    /** Prevents close during startup from being undone by a late listener bind. */
    private volatile boolean closed;
    /** A voice socket instance may be opened only once, including after a failed open. */
    private boolean opened;

    /**
     * Joins the matching RakNet listener instead of binding a competing UDP socket.
     * @param port voice configuration port, resolved from -1 by Simple Voice Chat itself
     * @param bindAddress voice configuration interface, empty for a wildcard
     * @throws Exception if the bind request is invalid, no listener starts or the socket was already used
     * @throws SocketException if voice closes while waiting for the game listener
     */
    @Override
    public void open(int port, String bindAddress) throws Exception {
        beginOpen(port);
        InetAddress address = InetAddress.getByName(bindAddress.isBlank() ? "0.0.0.0" : bindAddress);
        VoiceHub selected = VoiceTransport.await(port, address, () -> closed);
        synchronized (this) {
            if (closed) throw new SocketException("Voice socket closed during startup");
            inbox = selected.attach();
            hub = selected;
        }
    }

    /**
     * Claims this instance's one permitted open attempt before waiting for another thread's listener.
     * @param port configured voice UDP port
     * @throws SocketException if this socket was used already or the port cannot identify a bound listener
     */
    private synchronized void beginOpen(int port) throws SocketException {
        if (opened || closed) throw new SocketException("Voice socket already opened or closed");
        if (port < 1 || port > 65535) throw new SocketException("Shared voice port must match an explicit RakNet port");
        opened = true;
    }

    /**
     * Returns copied encrypted bytes for Simple Voice Chat to authenticate and decode normally.
     * @return received datagram with its original arrival time and physical reply address
     * @throws Exception if the attachment closes or its reader is interrupted
     */
    @Override
    public RawUdpPacket read() throws Exception {
        VoiceHub.Received received = requireInbox().read();
        return new Packet(received);
    }

    /**
     * Sends through the existing physical UDP port without adding RakNet reliability or ordering.
     * @param data complete encrypted voice packet
     * @param address peer address supplied by the voice server
     * @throws Exception if the socket is closed, the address is unsupported or the write fails
     * @throws SocketException if the destination is not a resolved UDP endpoint
     */
    @Override
    public void send(byte[] data, SocketAddress address) throws Exception {
        VoiceHub.Inbox current = requireInbox();
        if (!(address instanceof InetSocketAddress inet) || inet.isUnresolved()) {
            throw new SocketException("Voice destination must be a resolved UDP address");
        }
        hub.send(current, data, inet);
    }

    /**
     * Rejects reads and writes before successful startup instead of dereferencing a partial attachment.
     * @return current open inbox
     * @throws SocketException if no open attachment exists
     */
    private VoiceHub.Inbox requireInbox() throws SocketException {
        VoiceHub.Inbox current = inbox;
        if (current == null || current.closed()) throw new SocketException("Voice socket is not open");
        return current;
    }

    /**
     * Reports the actual shared port used in Simple Voice Chat's client handshake.
     * @return bound port or -1 before a successful open
     */
    @Override
    public int getLocalPort() {
        VoiceHub current = hub;
        SocketAddress local = current == null ? null : current.channel().localAddress();
        return local instanceof InetSocketAddress inet ? inet.getPort() : -1;
    }

    /** Detaches voice and wakes its reader while keeping every Minecraft connection open. */
    @Override
    public synchronized void close() {
        closed = true;
        if (inbox != null) inbox.close();
        VoiceTransport.wakeWaiters();
    }

    /**
     * Lets the voice receive loop exit on either voice shutdown or game listener shutdown.
     * @return whether there is no currently usable attachment
     */
    @Override
    public boolean isClosed() {
        VoiceHub.Inbox current = inbox;
        return current == null || current.closed();
    }

    /**
     * Keeps the optional API dependency confined to classes loaded only by Simple Voice Chat.
     * @param received copied wire data and arrival metadata
     */
    private record Packet(VoiceHub.Received received) implements RawUdpPacket {
        /**
         * Supplies heap-owned packet bytes to the voice decoder.
         * @return complete original UDP payload
         */
        @Override
        public byte[] getData() {
            return received.data();
        }

        /**
         * Preserves arrival time rather than measuring time spent waiting in the inbox.
         * @return epoch millisecond timestamp
         */
        @Override
        public long getTimestamp() {
            return received.timestamp();
        }

        /**
         * Supplies the endpoint used by voice nonces, connection checks and replies.
         * @return physical UDP sender
         */
        @Override
        public SocketAddress getSocketAddress() {
            return received.address();
        }
    }
}
