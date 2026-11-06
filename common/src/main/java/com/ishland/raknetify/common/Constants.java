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

package com.ishland.raknetify.common;

/**
 * Defines the shared wire identifiers and transport limits used by clients, servers, and proxies.
 */
public class Constants {

    /** Enables transport diagnostics when the raknetify.debug system property is true. */
    public static final boolean DEBUG = Boolean.getBoolean("raknetify.debug");

    /** Server-address prefix that selects RakNet with the normal MTU. */
    public static final String RAKNET_PREFIX = "raknet;";

    /** Server-address prefix that selects RakNet with the larger LAN-oriented MTU. */
    public static final String RAKNET_LARGE_MTU_PREFIX = "raknetl;";

    /** Frame identifier for the ordered marker used to negotiate multichannel delivery. */
    public static final int RAKNET_PING_PACKET_ID = 0xFA;

    /** Frame identifier for an uncompressed Minecraft packet payload. */
    public static final int RAKNET_GAME_PACKET_ID = 0xFD;

    /** Frame identifier for a Minecraft payload compressed with a channel-specific dictionary. */
    public static final int RAKNET_STREAMING_COMPRESSION_PACKET_ID = 0xED;

    /** Frame identifier for compression negotiation and dictionary-reset markers. */
    public static final int RAKNET_STREAMING_COMPRESSION_HANDSHAKE_PACKET_ID = 0xEC;

    /** Frame identifier for the ordering and reliability cursors used during resynchronization. */
    public static final int RAKNET_SYNC_PACKET_ID = 0xFC;

    /** Frame identifier for the peer transport-statistics snapshot. */
    public static final int RAKNET_METRICS_SYNC_PACKET_ID = 0xFB;

    /** Maximum queued transport payload, in bytes, before the RakNet queue limit is reached. */
    public static final int MAX_QUEUED_SIZE = 256 * 1024 * 1024;

    /** Number of ordering queues supported by the transport and streaming dictionaries. */
    public static final int ORDER_CHANNEL_COUNT = 8;

    /** Normal RakNet MTU in bytes, leaving room below common Ethernet MTUs. */
    public static final int DEFAULT_MTU = 1400;

    /** Opt-in RakNet MTU in bytes for networks that can carry larger datagrams. */
    public static final int LARGE_MTU = 8192;

    /** Upper bound on frame sets awaiting acknowledgement. */
    public static final int MAX_PENDING_FRAME_SETS = 512;

    /** Baseline number of pending frame sets before the transport adds burst capacity. */
    public static final int DEFAULT_PENDING_FRAME_SETS = 4;

    /** Ordering channels whose queued traffic survives world or protocol resynchronization. */
    public static final int[] SYNC_IGNORE_CHANNELS = {1};

    /** Prevents construction of the shared constants holder. */
    private Constants() {}
}
