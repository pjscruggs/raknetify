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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.frame.FrameData;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures routing and reliable-ID bookkeeping without including socket or fixture allocation
 * costs.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TransportBenchmark {
    /** Two handlers exercise both the fallback and successful classification paths. */
    private RakNetSimpleMultiChannelCodec codec;

    /** Mutable input prevents the classifier result from becoming a constant. */
    private ByteBuf buffer;

    /** Received-ID history persists across warmup, measurements and wire-counter wraps. */
    private ReliableFrameDeduplicator dedup;

    /** Owns the decoder's real lifecycle without including socket operations in measurements. */
    private EmbeddedChannel transport;

    /** Supplies a valid context for the same failure handling used by live connections. */
    private ChannelHandlerContext context;

    /** Reused frame isolates history allocation from ordinary packet allocation. */
    private Frame frame;

    /** Decoder output storage is reused and every accepted frame reference is released. */
    private final List<Object> output = new ArrayList<>(1);

    /** Monotonic input counter produces sequential, duplicate and reordered identities. */
    private int index;

    /** Transport callbacks use the same metrics implementation as live connections. */
    private final SimpleMetricsLogger metrics = new SimpleMetricsLogger();

    /** Builds independent connection state outside the measured operations. */
    @Setup
    public void setup() {
        codec = new RakNetSimpleMultiChannelCodec(0xfe);
        codec.addHandler((buf, suppress) -> 0);
        codec.addHandler((buf, suppress) -> (buf.getByte(buf.readerIndex()) & 7) + 1);
        buffer = Unpooled.buffer(128).writeZero(128);
        FrameData data = FrameData.create(UnpooledByteBufAllocator.DEFAULT, 0xfe, buffer);
        data.setReliability(FrameData.Reliability.RELIABLE);
        frame = Frame.create(data);
        data.release();
        dedup = new ReliableFrameDeduplicator();
        transport = new EmbeddedChannel(dedup);
        context = transport.pipeline().context(dedup);
    }

    /** Releases fixture ownership after JMH finishes the trial. */
    @TearDown
    public void close() {
        frame.release();
        buffer.release();
        transport.finishAndReleaseAll();
    }

    /**
     * Classifies changing packet bytes through the installed handlers.
     *
     * @return selected channel consumed by JMH to preserve observable work
     */
    @Benchmark
    public int classify() {
        buffer.setByte(0, index++);
        return codec.getChannelOverride(buffer, true);
    }

    /**
     * Exercises uninterrupted reliable traffic through repeated 24-bit counter wraps.
     *
     * @return accepted frame count
     */
    @Benchmark
    public int reliableSequential() {
        return accept(index++);
    }

    /**
     * Swaps neighboring identities to exercise gap creation and merging on every pair.
     *
     * @return accepted frame count
     */
    @Benchmark
    public int reliableReordered() {
        return accept(index++ ^ 1);
    }

    /**
     * Retries every reliable identity once before advancing to the next packet.
     *
     * @return one on first delivery and zero on the matching retry
     */
    @Benchmark
    public int reliableDuplicates() {
        return accept(index++ >>> 1);
    }

    /**
     * Measures callbacks for counters unused by published diagnostics.
     *
     * @return metrics owner retained as an observable result
     */
    @Benchmark
    public Object unusedMetricCallbacks() {
        metrics.framesIn(1);
        metrics.frameError(1);
        metrics.framesOut(1);
        metrics.bytesACKd(128);
        metrics.bytesNACKd(128);
        metrics.acksSent(1);
        metrics.nacksSent(1);
        return metrics;
    }

    /**
     * Delivers one identity and consumes any retained output reference before the next operation.
     *
     * @param identity unwrapped input identity truncated to the wire counter
     * @return number of frames accepted by the actual decoder
     */
    private int accept(final int identity) {
        frame.setReliableIndex(identity & 0xffffff);
        dedup.decode(context, frame, output);
        int count = output.size();
        if (count != 0) {
            ((Frame) output.remove(0)).release();
        }
        return count;
    }
}
