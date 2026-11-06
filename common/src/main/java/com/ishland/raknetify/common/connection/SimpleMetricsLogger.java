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

import com.ishland.raknetify.common.util.MathUtil;
import network.ycc.raknet.RakNet;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

/**
 * Collects transport counters and publishes sampled rates for diagnostics.
 * Mutations must come from one transport thread. Volatile fields allow other threads
 * to read recent values but do not make the counter increments atomic.
 */
@SuppressWarnings("NonAtomicOperationOnVolatileField")
public class SimpleMetricsLogger implements RakNet.MetricsLogger {

    // ========== Loggers ==========

    /**
     * Received RakNet packet count, accumulated by the connection's single transport writer.
     */
    private volatile long packetsIn;
    /**
     * Received transport byte count, accumulated by the connection's single transport writer.
     */
    private volatile long bytesIn;
    /**
     * Sent RakNet packet count, accumulated by the connection's single transport writer.
     */
    private volatile long packetsOut;
    /**
     * Sent transport byte count, accumulated by the connection's single transport writer.
     */
    private volatile long bytesOut;
    /**
     * Retransmitted transport byte count, accumulated by the connection's single transport writer.
     */
    private volatile long bytesRecalled;

    /**
     * Latest transport round-trip-time estimate, in nanoseconds.
     */
    private volatile long measureRTTns;
    /**
     * Latest standard deviation of the transport RTT estimate, in nanoseconds.
     */
    private volatile long measureRTTnsStdDev;
    /**
     * Latest additional burst allowance above the configured baseline pending-frame-set count.
     */
    private volatile long measureBurstTokens;
    /**
     * Latest transport queue size in bytes, published for diagnostic readers.
     */
    private volatile int currentQueuedBytes;

    /**
     * Accumulates the received raknet packet count reported by the transport.
     *
     * @param delta additional count reported by this transport callback
     */
    @Override
    public void packetsIn(int delta) {
        packetsIn += delta;
    }

    /**
     * Accumulates the received transport byte count reported by the transport.
     *
     * @param delta additional count reported by this transport callback
     */
    @Override
    public void bytesIn(int delta) {
        bytesIn += delta;
    }

    /**
     * Accumulates the sent raknet packet count reported by the transport.
     *
     * @param delta additional count reported by this transport callback
     */
    @Override
    public void packetsOut(int delta) {
        packetsOut += delta;
    }

    /**
     * Accumulates the sent transport byte count reported by the transport.
     * Also refreshes derived rates when the sampling interval has elapsed.
     *
     * @param delta additional count reported by this transport callback
     */
    @Override
    public void bytesOut(int delta) {
        bytesOut += delta;
        tick();
    }

    /**
     * Accumulates the retransmitted transport byte count reported by the transport.
     *
     * @param delta additional count reported by this transport callback
     */
    @Override
    public void bytesRecalled(int delta) {
        bytesRecalled += delta;
    }

    /**
     * Publishes the transport's latest round-trip-time estimate.
     *
     * @param n estimated round-trip time in nanoseconds
     */
    @Override
    public void measureRTTns(long n) {
        measureRTTns = n;
    }

    /**
     * Publishes RTT variation and refreshes derived rates when sampling is due.
     *
     * @param n standard deviation of the RTT estimate in nanoseconds
     */
    @Override
    public void measureRTTnsStdDev(long n) {
        measureRTTnsStdDev = n;
        tick();
    }

    /**
     * Publishes the transport's additional send allowance above its baseline.
     *
     * @param n current burst-token count
     */
    @Override
    public void measureBurstTokens(int n) {
        measureBurstTokens = n;
    }

    /**
     * Publishes the current transport queue size without accumulating it.
     *
     * @param bytes bytes currently waiting in the transport queue
     */
    @Override
    public void currentQueuedBytes(int bytes) {
        currentQueuedBytes = bytes;
    }

    // ========== Calculations ==========

    /**
     * Wall-clock time of the last derived-rate update, in milliseconds.
     */
    private long lastMeasureMillis = System.currentTimeMillis();

    /**
     * Updates derived measurements at most about once per second from transport callbacks.
     */
    private synchronized void tick() {
        final long measureMillis = System.currentTimeMillis();
        final long deltaTime = measureMillis - lastMeasureMillis;
        if (deltaTime < 990) return; // throttle
        this.lastMeasureMillis = measureMillis;

        tickErrorRate();
        tickRXTX(deltaTime);
    }

    /**
     * Rolling sixteen-sample window of retransmitted bytes divided by total traffic bytes.
     */
    private final DescriptiveStatistics errorStats = new DescriptiveStatistics(16);
    /**
     * Combined inbound and outbound byte total at the previous retransmission sample.
     */
    private long lastBytesTotal;
    /**
     * Retransmitted-byte total at the previous retransmission sample.
     */
    private long lastBytesRecalled;
    /**
     * Published rolling retransmitted-byte fraction, not a direct packet-loss measurement.
     */
    private volatile double measureErrorRate;

    /**
     * Updates the retransmitted-byte fraction only for intervals containing traffic.
     */
    private void tickErrorRate() {
        final long bytesTotal = this.bytesIn + this.bytesOut;
        final long bytesRecalled = this.bytesRecalled;

        final long bytesTotalDelta = bytesTotal - lastBytesTotal;
        final long bytesRecalledDelta = bytesRecalled - this.lastBytesRecalled;

        if (bytesTotalDelta != 0) {
            this.errorStats.addValue(bytesRecalledDelta / (double) bytesTotalDelta);
            this.measureErrorRate = this.errorStats.getMean();
        }

        this.lastBytesTotal = bytesTotal;
        this.lastBytesRecalled = bytesRecalled;
    }

    /**
     * Rolling eight-sample window of incoming packet rates in packets per second.
     */
    private final DescriptiveStatistics rxStats = new DescriptiveStatistics(8);
    /**
     * Rolling eight-sample window of outgoing packet rates in packets per second.
     */
    private final DescriptiveStatistics txStats = new DescriptiveStatistics(8);
    /**
     * Incoming packet total at the previous rate sample.
     */
    private long lastPacketsIn;
    /**
     * Outgoing packet total at the previous rate sample.
     */
    private long lastPacketsOut;
    /**
     * Incoming byte total at the previous rate sample.
     */
    private long lastBytesIn;
    /**
     * Outgoing byte total at the previous rate sample.
     */
    private long lastBytesOut;
    /**
     * Published rolling mean incoming packet rate, truncated to whole packets per second.
     */
    private volatile int measureRX;
    /**
     * Published rolling mean outgoing packet rate, truncated to whole packets per second.
     */
    private volatile int measureTX;
    /**
     * Published incoming byte rate for the latest sampling interval, in bytes per second.
     */
    private volatile long measureBytesInRate;
    /**
     * Published outgoing byte rate for the latest sampling interval, in bytes per second.
     */
    private volatile long measureBytesOutRate;
    /**
     * Incoming byte rate formatted with binary units and a per-second suffix.
     */
    private volatile String measureTrafficInFormatted = "...";
    /**
     * Outgoing byte rate formatted with binary units and a per-second suffix.
     */
    private volatile String measureTrafficOutFormatted = "...";

    /**
     * Converts counter deltas into packet and byte rates using the actual elapsed interval.
     * Packet rates use rolling means. Byte rates and their display strings describe the latest interval.
     *
     * @param deltaTime elapsed sampling time in milliseconds
     */
    private void tickRXTX(long deltaTime) {

        final long packetsIn = this.packetsIn;
        final long packetsOut = this.packetsOut;
        final long bytesIn = this.bytesIn;
        final long bytesOut = this.bytesOut;

        final double timeDeltaS = deltaTime / 1000.0;

        this.rxStats.addValue((packetsIn - this.lastPacketsIn) / timeDeltaS);
        this.txStats.addValue((packetsOut - this.lastPacketsOut) / timeDeltaS);

        this.measureRX = (int) this.rxStats.getMean();
        this.measureTX = (int) this.txStats.getMean();

        this.measureBytesInRate = (long) ((bytesIn - this.lastBytesIn) / timeDeltaS);
        this.measureBytesOutRate = (long) ((bytesOut - this.lastBytesOut) / timeDeltaS);

        this.measureTrafficInFormatted = MathUtil.humanReadableByteCountBin(this.measureBytesInRate) + "/s";
        this.measureTrafficOutFormatted = MathUtil.humanReadableByteCountBin(this.measureBytesOutRate) + "/s";

        this.lastPacketsIn = packetsIn;
        this.lastPacketsOut = packetsOut;
        this.lastBytesIn = bytesIn;
        this.lastBytesOut = bytesOut;
    }

    // ========== Getters ==========

    /**
     * Reports the transport round-trip-time estimate.
     *
     * @return latest RTT in nanoseconds
     */
    public long getMeasureRTTns() {
        return measureRTTns;
    }

    /**
     * Reports variation in the transport round-trip-time estimate.
     *
     * @return latest RTT standard deviation in nanoseconds
     */
    public long getMeasureRTTnsStdDev() {
        return measureRTTnsStdDev;
    }

    /**
     * Reports the rolling retransmission fraction.
     *
     * @return retransmitted bytes divided by combined incoming and outgoing bytes
     */
    public double getMeasureErrorRate() {
        return measureErrorRate;
    }

    /**
     * Reports the rolling incoming packet rate.
     *
     * @return incoming packets per second, truncated to an integer
     */
    public int getMeasureRX() {
        return measureRX;
    }

    /**
     * Reports the rolling outgoing packet rate.
     *
     * @return outgoing packets per second, truncated to an integer
     */
    public int getMeasureTX() {
        return measureTX;
    }

    /**
     * Reports the transport queue occupancy.
     *
     * @return most recently reported queued bytes
     */
    public int getCurrentQueuedBytes() {
        return currentQueuedBytes;
    }

    /**
     * Reports the transport burst allowance.
     *
     * @return additional pending-frame-set tokens above the configured baseline
     */
    public long getMeasureBurstTokens() {
        return measureBurstTokens;
    }

    /**
     * Reports the latest incoming byte rate.
     *
     * @return incoming bytes per second over the latest sampling interval
     */
    public long getMeasureBytesInRate() {
        return measureBytesInRate;
    }

    /**
     * Reports the latest outgoing byte rate.
     *
     * @return outgoing bytes per second over the latest sampling interval
     */
    public long getMeasureBytesOutRate() {
        return measureBytesOutRate;
    }

    /**
     * Reports the incoming transfer rate for display.
     *
     * @return binary-unit rate with a per-second suffix, or an ellipsis before sampling
     */
    public String getMeasureTrafficInFormatted() {
        return measureTrafficInFormatted;
    }

    /**
     * Reports the outgoing transfer rate for display.
     *
     * @return binary-unit rate with a per-second suffix, or an ellipsis before sampling
     */
    public String getMeasureTrafficOutFormatted() {
        return measureTrafficOutFormatted;
    }

    /**
     * Reports the lifetime received traffic total.
     *
     * @return cumulative incoming transport bytes
     */
    public long getBytesIn() {
        return bytesIn;
    }

    // ========== Misc ==========

    /**
     * Peer-statistics handler paired with this local logger, or null before pipeline initialization.
     */
    private MetricsSynchronizationHandler metricsSynchronizationHandler;

    /**
     * Finds the peer-statistics source associated with this connection.
     *
     * @return paired synchronization handler, or null if one has not been installed
     */
    public MetricsSynchronizationHandler getMetricsSynchronizationHandler() {
        return this.metricsSynchronizationHandler;
    }

    /**
     * Associates the local logger with the handler that receives peer statistics.
     *
     * @param metricsSynchronizationHandler installed peer-statistics handler, or null to clear the association
     */
    public void setMetricsSynchronizationHandler(MetricsSynchronizationHandler metricsSynchronizationHandler) {
        this.metricsSynchronizationHandler = metricsSynchronizationHandler;
    }
}
