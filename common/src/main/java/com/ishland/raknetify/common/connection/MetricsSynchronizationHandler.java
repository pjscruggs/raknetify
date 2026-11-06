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

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.ScheduledFuture;

import network.ycc.raknet.RakNet;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket;

import java.util.concurrent.TimeUnit;

/**
 * Exchanges diagnostic snapshots with the peer over unreliable RakNet frames. Snapshots carry the
 * sender's timestamp so reordered older reports do not replace newer data. These values describe
 * the remote endpoint and do not control local transport behavior.
 */
public class MetricsSynchronizationHandler extends ChannelDuplexHandler {

    // Packet format:
    // byte: version (currently 0x00)
    // long: packet sent time (used to filter out old packets)
    // int: buffer size in bytes
    // int: burst size
    // double: error rate
    // int: tx
    // int: rx

    /** Wire-layout version accepted by this metrics exchange implementation. */
    private static final byte VERSION = 0x00;

    /** Periodic event-loop task publishing a local snapshot every 200 milliseconds. */
    private ScheduledFuture<?> future;

    /**
     * Captures the context and schedules peer-statistics updates on its event loop.
     *
     * @param ctx installed handler context for allocation, scheduling, and writes
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) {
        this.future =
                ctx.channel()
                        .eventLoop()
                        .scheduleAtFixedRate(
                                () -> sendSyncPacket(ctx), 200, 200, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops periodic metric publication when the handler leaves the pipeline.
     *
     * @param ctx removed context, unused because the scheduled task is already retained
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext ctx) {
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * Sends the latest local queue, burst, retransmission, and packet-rate measurements. The
     * temporary payload is released after an unreliable frame takes ownership of its data.
     *
     * @param ctx installed context captured by this handler's event-loop task
     */
    private void sendSyncPacket(final ChannelHandlerContext ctx) {
        if (ctx.channel().config() instanceof RakNet.Config config
                && config.getMetrics() instanceof SimpleMetricsLogger logger) {
            ByteBuf buffer = null;
            try {
                buffer = ctx.alloc().buffer(1 + 8 + 4 + 4 + 8 + 4 + 4);
                buffer.writeByte(VERSION);
                buffer.writeLong(System.currentTimeMillis());
                buffer.writeInt(logger.getCurrentQueuedBytes());
                buffer.writeInt(
                        (int)
                                (logger.getMeasureBurstTokens()
                                        + config.getDefaultPendingFrameSets()));
                buffer.writeDouble(logger.getMeasureErrorRate());
                buffer.writeInt(logger.getMeasureTX());
                buffer.writeInt(logger.getMeasureRX());
                final FrameData frameData =
                        FrameData.create(
                                ctx.alloc(), Constants.RAKNET_METRICS_SYNC_PACKET_ID, buffer);
                frameData.setReliability(FramedPacket.Reliability.UNRELIABLE);
                ctx.write(frameData);
            } finally {
                if (buffer != null) {
                    buffer.release();
                }
            }
        }
    }

    /** Initial zero values distinguish an unsupported peer from an accepted all-zero snapshot. */
    private static final RemoteMetrics EMPTY = new RemoteMetrics(0, 0, 0, 0, 0, 0);

    /**
     * Complete peer measurements published together for readers outside the transport event loop.
     */
    private volatile RemoteMetrics remote = EMPTY;

    /**
     * Keeps parsing failures and concurrent readers from observing partially replaced statistics.
     *
     * @param time sender timestamp used to reject older snapshots
     * @param queuedBytes peer's queued transport bytes
     * @param burst peer's pending-frame allowance
     * @param errorRate peer's retransmitted-byte fraction
     * @param tx peer's outgoing packets per second
     * @param rx peer's incoming packets per second
     */
    private record RemoteMetrics(
            long time, int queuedBytes, int burst, double errorRate, int tx, int rx) {}

    /**
     * Consumes supported metric frames and rejects stale snapshots without forwarding them. Every
     * consumed frame and its payload view are released even when parsing fails.
     *
     * @param ctx inbound context receiving messages unrelated to metrics
     * @param msg incoming metric frame or other pipeline message
     * @throws Exception if superclass processing of an unrelated message fails
     */
    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        if (msg instanceof FrameData frameData
                && frameData.getDataSize() > 0
                && frameData.getPacketId() == Constants.RAKNET_METRICS_SYNC_PACKET_ID) {
            ByteBuf byteBuf = null;
            try {
                byteBuf = frameData.createData().skipBytes(1);
                final byte version = byteBuf.readByte();
                if (version != VERSION) {
                    return;
                }

                final long time = byteBuf.readLong();
                if (time < remote.time()) {
                    return;
                }
                remote =
                        new RemoteMetrics(
                                time,
                                byteBuf.readInt(),
                                byteBuf.readInt(),
                                byteBuf.readDouble(),
                                byteBuf.readInt(),
                                byteBuf.readInt());
            } finally {
                if (byteBuf != null) {
                    byteBuf.release();
                }
                frameData.release();
            }
            return;
        }
        super.channelRead(ctx, msg);
    }

    /**
     * Reports whether remote metric values have been initialized by a compatible snapshot.
     *
     * @return true after at least one supported snapshot has been accepted
     */
    public boolean isRemoteSupported() {
        return remote != EMPTY;
    }

    /**
     * Reports the peer's most recently advertised queue size.
     *
     * @return remote queued bytes, or zero before the first snapshot
     */
    public int getQueuedBytes() {
        return remote.queuedBytes();
    }

    /**
     * Reports the peer's most recently advertised send capacity.
     *
     * @return remote burst tokens plus its baseline pending-frame-set allowance
     */
    public int getBurst() {
        return remote.burst();
    }

    /**
     * Reports the peer's rolling retransmission measurement.
     *
     * @return retransmitted-byte fraction, not a percentage or a measured packet-loss probability
     */
    public double getErrorRate() {
        return remote.errorRate();
    }

    /**
     * Reports traffic sent by the peer, which is inbound from this endpoint's perspective.
     *
     * @return remote outgoing packets per second
     */
    public int getTX() {
        return remote.tx();
    }

    /**
     * Reports traffic received by the peer, which is outbound from this endpoint's perspective.
     *
     * @return remote incoming packets per second
     */
    public int getRX() {
        return remote.rx();
    }
}
