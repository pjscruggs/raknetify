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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

/** Measures receipt-window maintenance independently of cryptographic and socket costs. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ReplayWindowBenchmark {
    /** Packet loss between arrivals, including a complete rotation of the receipt bitmap. */
    @Param({"1", "64", "2048", "4096"})
    public int stride;

    /** Retains receipt bits across operations so each arrival expires real previous history. */
    private final FrameReplayWindow history = new FrameReplayWindow();

    /** Synthetic monotonically increasing identity isolates window advancement from key renewal. */
    private long sequence;

    /** Creates independent receipt history for each benchmark worker. */
    public ReplayWindowBenchmark() {}

    /**
     * Advances by the selected loss gap and consumes the acceptance decision as the measured
     * result.
     *
     * @return whether the newly arrived loss-tolerant frame was accepted
     * @throws GeneralSecurityException if unreliable receipt unexpectedly consumes reliable budget
     */
    @Benchmark
    public boolean unreliableArrival() throws GeneralSecurityException {
        sequence += stride;
        return history.accept(sequence, true);
    }
}
