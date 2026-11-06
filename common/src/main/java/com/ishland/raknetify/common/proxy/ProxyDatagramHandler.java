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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

/** Validates proxy metadata before any datagram reaches RakNet's handshake or reliability decoders. */
final class ProxyDatagramHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    /** Immutable policy inherited by each connected UDP socket. */
    private final ProxyProtocolConfig config;
    /** Listener-scoped bindings survive the transition to connected child sockets. */
    private final ProxySessions sessions;

    /**
     * Creates an independently owned handler sharing only synchronized identity state.
     * @param config explicit physical sender trust policy
     * @param sessions listener's bounded identity table
     */
    ProxyDatagramHandler(ProxyProtocolConfig config, ProxySessions sessions) {
        this.config = config;
        this.sessions = sessions;
    }

    /**
     * Drops untrusted or malformed packets and forwards a retained payload with its physical routing intact.
     * The enclosing inbound handler releases every original datagram, including rejected inputs.
     * @param context physical socket pipeline
     * @param packet received UDP envelope
     */
    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
        if (!config.trusts(packet.sender().getAddress())) return;
        ProxyProtocolHeader header;
        try {
            header = ProxyProtocolHeader.present(packet.content())
                    ? ProxyProtocolHeader.decode(packet.content(), packet.sender(), packet.recipient()) : null;
        } catch (IllegalArgumentException malformed) {
            return;
        }
        int offset = header == null ? 0 : header.length();
        if (packet.content().readableBytes() <= offset) return;
        if (sessions.resolve(packet.sender(), header) == null) return;
        context.fireChannelRead(new DatagramPacket(packet.content().retainedSlice(
                packet.content().readerIndex() + offset, packet.content().readableBytes() - offset),
                packet.recipient(), packet.sender()));
    }
}
