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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import network.ycc.raknet.packet.FrameSet;

/** Simulates loss of the first fragmented application datagram without acknowledging or altering later traffic. */
final class DropFirstFragment extends ChannelInboundHandlerAdapter {

    /** A single loss is enough to expose a disconnect overtaking an outstanding reason on another channel. */
    private boolean dropped;

    /**
     * Discards one complete datagram before its sequence number reaches RakNet's acknowledgement handler.
     * @param context parent transport pipeline context
     * @param message decoded datagram or unrelated connection/control traffic
     */
    @Override
    public void channelRead(ChannelHandlerContext context, Object message) {
        if (!this.dropped && message instanceof FrameSet set && containsFirstFragment(set)) {
            this.dropped = true;
            System.out.println("DROPPED_FIRST_REASON_DATAGRAM");
            set.release();
            return;
        }
        context.fireChannelRead(message);
    }

    /**
     * Inspects retained frame views and releases every reference, including unrelated frames in the same datagram.
     * @param set incoming datagram whose contents remain owned by the caller
     * @return whether a channel-zero application's first fragment is present
     */
    private static boolean containsFirstFragment(FrameSet set) {
        boolean[] found = {false};
        set.createFrames(frame -> {
            try {
                if (frame.getOrderChannel() == 0 && frame.hasSplit() && frame.getSplitIndex() == 0) found[0] = true;
            } finally {
                frame.release();
            }
        });
        return found[0];
    }
}
