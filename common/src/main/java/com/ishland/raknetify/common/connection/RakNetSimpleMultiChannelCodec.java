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

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.util.MathUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Wraps Minecraft payloads in RakNet frames and selects their ordering or reliability policy.
 * The channel event loop owns negotiation and pending writes. Override registration is synchronized
 * because protocol handlers can be installed from another thread.
 */
public class RakNetSimpleMultiChannelCodec extends ChannelDuplexHandler {

    /**
     * Pipeline name used to locate the Minecraft-to-RakNet framing handler.
     */
    public static final String NAME = "raknetify-simple-multi-channel-data-codec";

    /**
     * Local outbound marker requesting multichannel negotiation rather than a wire payload.
     */
    public static final Object SIGNAL_START_MULTICHANNEL = new Object();

    /**
     * Frame identifier used to wrap and recognize Minecraft payloads.
     */
    private final int packetId;

    /**
     * Creates a framing codec before any protocol-specific channel rules are installed.
     *
     * @param packetId RakNet frame identifier reserved for Minecraft payloads
     */
    public RakNetSimpleMultiChannelCodec(int packetId) {
        this.packetId = packetId;
    }

    /**
     * Channel overrides in precedence order, guarded by this list as a monitor.
     */
    private final ObjectArrayList<OverrideHandler> handlers = new ObjectArrayList<>();

    /**
     * Appends an override after the existing rules. Earlier nonzero results take precedence.
     *
     * @param handler packet classifier to consult during encoding
     * @return this codec for chained registration
     */
    public RakNetSimpleMultiChannelCodec addHandler(OverrideHandler handler) {
        synchronized (handlers) {
            handlers.add(handler);
        }
        return this;
    }

    /**
     * Removes a classifier when its protocol phase or integration is no longer active.
     *
     * @param handler previously registered classifier to remove
     */
    public void removeHandler(OverrideHandler handler) {
        synchronized (handlers) {
            handlers.remove(handler);
        }
    }

    /**
     * Finds the first registered classifier assignable to the requested type.
     *
     * @param <T> classifier type expected by the caller
     * @param clazz runtime type used to identify the classifier
     * @return the first matching classifier, or null when none is installed
     */
    public <T> T getHandler(Class<T> clazz) {
        synchronized (handlers) {
            for (OverrideHandler handler : handlers) {
                if (clazz.isInstance(handler)) return clazz.cast(handler);
            }
        }
        return null;
    }

    /**
     * Whether the negotiation marker has completed and channel overrides may affect delivery.
     */
    private boolean isMultichannelEnabled;

    /**
     * Whether Minecraft writes must wait for the multichannel negotiation marker.
     */
    private boolean queuePendingWrites = false;
    /**
     * Owned encoded frames and their completion promises awaiting negotiation completion.
     */
    private final Queue<PendingWrite> pendingWrites = new LinkedList<>();

    /**
     * Fails outstanding negotiation-delayed writes and releases their retained frames.
     *
     * @param ctx context being removed from the pipeline
     * @throws Exception if superclass removal processing fails
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        failPendingWrites(new IllegalStateException("Channel closed"));
    }

    /**
     * Handles local negotiation markers and transfers Minecraft buffers into routed frames.
     * Consumed payload buffers are released after encoding. Unrelated pipeline messages pass through.
     *
     * @param ctx outbound context used for allocation and frame delivery
     * @param msg Minecraft payload, local control marker, or unrelated pipeline message
     * @param promise completion notification for the outgoing write
     * @throws Exception if forwarding through the superclass fails
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (this.queuePendingWrites && msg instanceof ByteBuf buf) {
            queueWrite(ctx, buf, promise);
            return;
        }

        if (msg == SIGNAL_START_MULTICHANNEL) {
            startMultichannel(ctx, promise);
            return;
        }
        if (msg == SynchronizationLayer.SYNC_REQUEST_OBJECT) {
            if (this.isMultichannelEnabled) {
                if (Constants.DEBUG) System.out.println("Raknetify: [MultiChannellingDataCodec] Stopped multichannel");
                this.isMultichannelEnabled = false;
                super.write(ctx, msg, promise);
            } else {
                promise.setSuccess();
            }
            return; // discard sync request when multichannel is not active
        }

        if (msg instanceof ByteBuf buf && buf.isReadable()) {
            try {
                final FrameData frameData = encode0(ctx, buf);
                if (frameData != null) {
                    ctx.write(frameData, promise);
                } else {
                    promise.setSuccess();
                }
            } finally {
                buf.release();
            }
            return;
        }

        super.write(ctx, msg, promise);
    }


    /**
     * Starts channel negotiation and holds writes until the ordered marker has been delivered.
     *
     * @param ctx outbound context used to send the marker on channel seven
     * @param promise notification for accepting the local start request, not the peer acknowledgement
     */
    private void startMultichannel(ChannelHandlerContext ctx, ChannelPromise promise) {
        promise.trySuccess();
        if (this.isMultichannelEnabled) return;
        if (!this.isMultichannelAvailable()) {
            System.out.println("Raknetify: [MultiChannellingDataCodec] Failed to start multichannel: not available");
            return;
        }
        final ByteBuf buf = ctx.alloc().buffer(1).writeByte(0);
        try {
            final FrameData frameData = FrameData.create(ctx.alloc(), Constants.RAKNET_PING_PACKET_ID, buf);
            frameData.setOrderChannel(7);
            this.queuePendingWrites = true;
            ctx.write(frameData).addListener(future -> {
                if (!future.isSuccess()) {
                    failPendingWrites(future.cause());
                    ctx.close();
                    return;
                }
                isMultichannelEnabled = true;
                if (Constants.DEBUG) System.out.println("Raknetify: [MultiChannellingDataCodec] Started multichannel");
                flushPendingWrites(ctx);
            });
        } finally {
            buf.release();
        }
    }

    /**
     * Holds an encoded packet until multichannel negotiation finishes, completing discarded packets immediately.
     *
     * @param ctx allocation context for the encoded frame
     * @param buf Minecraft packet payload released after encoding
     * @param promise completion notification retained with an accepted frame
     */
    private void queueWrite(ChannelHandlerContext ctx, ByteBuf buf, ChannelPromise promise) {
        try {
            final FrameData data = encode0(ctx, buf);
            if (data != null) {
                pendingWrites.add(new PendingWrite(data, promise));
            } else {
                promise.setSuccess();
            }
        } finally {
            buf.release();
        }
    }

    /**
     * Removes and releases each held frame before notifying its caller, including already cancelled writes.
     * Emptying the queue also prevents a late marker acknowledgement from forwarding released frames.
     *
     * @param failure negotiation or channel-removal cause preserved on every unfinished write
     */
    private void failPendingWrites(Throwable failure) {
        PendingWrite pendingWrite;
        while ((pendingWrite = this.pendingWrites.poll()) != null) {
            pendingWrite.frameData.release();
            pendingWrite.promise.tryFailure(failure);
        }
    }

    /**
     * Creates a frame using the first applicable channel rule without releasing the input buffer.
     * Packets sent before negotiation begins stay ordered on channel zero. Writes held behind the
     * negotiation marker already need their final routes so later writes cannot overtake them.
     *
     * @param ctx allocator for the new frame
     * @param buf readable Minecraft payload with its packet ID at the reader index
     * @return an owned frame, or null for empty payloads or an explicit discard rule
     */
    private FrameData encode0(ChannelHandlerContext ctx, ByteBuf buf) {
        if (buf.isReadable()) {
            final int packetChannelOverride = getChannelOverride(buf, !isMultichannelEnabled);
            if (packetChannelOverride == Integer.MIN_VALUE) {
                return null; // the void
            }
            final FrameData frameData = FrameData.create(ctx.alloc(), packetId, buf);
            if (isMultichannelEnabled || queuePendingWrites) {
                if (packetChannelOverride >= 0)
                    frameData.setOrderChannel(packetChannelOverride);
                else if (packetChannelOverride == -1)
                    frameData.setReliability(FramedPacket.Reliability.RELIABLE);
                else if (packetChannelOverride == -2)
                    frameData.setReliability(FramedPacket.Reliability.UNRELIABLE);
            }
            return frameData;
        }
        return null;
    }

    /**
     * Resumes ordinary writes and drains retained frames in their original submission order.
     * Forwarding failures are reported to the pipeline.
     *
     * @param ctx outbound context that receives the queued frames
     */
    private void flushPendingWrites(ChannelHandlerContext ctx) {
        this.queuePendingWrites = false;
        PendingWrite pendingWrite;
        while ((pendingWrite = this.pendingWrites.poll()) != null) {
            try {
                super.write(ctx, pendingWrite.frameData, pendingWrite.promise);
            } catch (Throwable t) {
                ctx.fireExceptionCaught(t);
            }
        }
    }

    /**
     * Checks whether any protocol classifier has been installed for negotiation.
     *
     * @return true when at least one override handler is registered
     */
    protected boolean isMultichannelAvailable() {
        synchronized (handlers) {
            return !handlers.isEmpty();
        }
    }

    /**
     * Consults classifiers in registration order and uses the first nonzero decision.
     *
     * @param buf Minecraft payload inspected without consuming its reader index
     * @param suppressWarning whether classifiers should suppress unknown-packet diagnostics
     * @return ordering channel, -1 for reliable unordered, -2 for unreliable, or Integer.MIN_VALUE to discard
     */
    protected int getChannelOverride(ByteBuf buf, boolean suppressWarning) {
        synchronized (handlers) {
            for (OverrideHandler handler : handlers) {
                final int override = handler.getChannelOverride(buf, suppressWarning);
                if (override != 0) return override;
            }
        }
        return 0;
    }

    /**
     * Unwraps complete Minecraft frames, consumes negotiation markers, and forwards other traffic.
     * A forwarded frame retains its own reference before the input reference is released.
     *
     * @param ctx inbound context receiving decoded payloads
     * @param msg incoming frame or unrelated pipeline message
     * @throws Exception if superclass inbound processing fails
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FrameData packet && !packet.isFragment() && packet.getDataSize() > 0) {
            try {
                if (packetId == packet.getPacketId()) {
                    ctx.fireChannelRead(packet.createData().skipBytes(1));
                } else if (packet.getPacketId() == Constants.RAKNET_PING_PACKET_ID) {
                    return;
                } else {
                    ctx.fireChannelRead(packet.retain());
                }
            } finally {
                packet.release();
            }
            return;
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Extracts a Minecraft payload or retains an unrelated frame for a decoder output list.
     * The caller retains responsibility for releasing the input frame. Negotiation markers produce no output.
     *
     * @param ctx decoder context, unused because the frame supplies its own retained payload
     * @param packet complete, nonfragmented frame to inspect
     * @param out output list receiving owned payload buffers or retained frames
     */
    protected void decode(ChannelHandlerContext ctx, FrameData packet, List<Object> out) {
        assert !packet.isFragment();
        if (packet.getDataSize() > 0) {
            if (packetId == packet.getPacketId()) {
                out.add(packet.createData().skipBytes(1));
            } else if (packet.getPacketId() == Constants.RAKNET_PING_PACKET_ID) {
                return;
            } else {
                out.add(packet.retain());
            }
        }
    }

    /**
     * Classifies a Minecraft packet without changing the payload reader index.
     */
    public interface OverrideHandler {
        /**
         * Selects a delivery policy while allowing later classifiers to handle the default channel.
         *
         * @param buf payload beginning with the Minecraft packet ID, which must remain unconsumed
         * @param suppressWarning whether an unknown packet should be inspected without a diagnostic
         * @return zero to defer, a positive ordering channel, -1 for reliable unordered, -2 for unreliable,
         *     or Integer.MIN_VALUE to discard the packet
         */
        int getChannelOverride(ByteBuf buf, boolean suppressWarning);
    }

    /**
     * Uses a protocol-specific packet-ID table and warns once for each unknown packet ID.
     */
    public static class PacketIdBasedOverrideHandler implements OverrideHandler {

        /**
         * Packet IDs already reported as unknown, preventing repeated diagnostics on a connection.
         */
        private final IntOpenHashSet unknownPacketIds = new IntOpenHashSet();
        /**
         * Private copy of the packet-ID routes supplied for this protocol direction.
         */
        private final Int2IntOpenHashMap channelMapping;
        /**
         * Human-readable protocol and direction included in unknown-packet diagnostics.
         */
        private final String descriptiveProtocolStatus;

        /**
         * Snapshots the routing table so later caller changes do not alter an active classifier.
         *
         * @param channelMapping packet IDs mapped to ordering channels or special delivery policies
         * @param descriptiveProtocolStatus protocol and direction to identify in diagnostics
         */
        public PacketIdBasedOverrideHandler(Int2IntMap channelMapping, String descriptiveProtocolStatus) {
            this.channelMapping = new Int2IntOpenHashMap(channelMapping);
            this.descriptiveProtocolStatus = descriptiveProtocolStatus;
        }

        /**
         * Reads the packet ID from a slice and looks up its delivery policy.
         * An explicit unknown sentinel falls back to channel seven, with at most one warning per ID.
         *
         * @param buf Minecraft payload whose reader index remains unchanged
         * @param suppressWarning whether an unknown ID should be handled silently
         * @return table policy, or channel seven for an Integer.MAX_VALUE table entry
         */
        @Override
        public int getChannelOverride(ByteBuf buf, boolean suppressWarning) {
            final ByteBuf slice = buf.slice();
            final int packetId = MathUtil.readVarInt(slice);
            final int override = this.channelMapping.get(packetId);
            if (override == Integer.MAX_VALUE) {
                if (!suppressWarning) {
                    if (this.unknownPacketIds.add(packetId)) {
                        System.err.println("Raknetify: Unknown packet id %d for %s".formatted(packetId, descriptiveProtocolStatus));
                    }
                }
                return 7;
            }
            return override;
        }
    }

    /**
     * Keeps an owned frame paired with the write promise while negotiation delays delivery.
     *
     * @param frameData encoded frame released on removal or transferred when the queue drains
     * @param promise completion notification belonging to the original Minecraft write
     */
    private record PendingWrite(FrameData frameData, ChannelPromise promise) {
    }

}
