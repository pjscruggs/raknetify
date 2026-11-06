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

import network.ycc.raknet.frame.FrameData;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Measures authenticated frame round trips with small interactions and larger world payloads. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FrameEncryptionBenchmark {
    /**
     * Payload sizes cover a small interaction, an MTU-sized payload, and a compressed world batch.
     */
    @Param({"64", "1024", "32768"})
    public int size;

    /** Per-iteration sender renews keys before a high-throughput trial reaches the usage budget. */
    private AuthenticatedFrameCipher sender;

    /** Matching receiver includes tag verification and replay accounting in measured work. */
    private AuthenticatedFrameCipher receiver;

    /** Stable frame metadata keeps transport object allocation outside the measurement. */
    private FrameData frame;

    /** Reusable plaintext whose first byte varies between invocations. */
    private ByteBuf plaintext;

    /** Reusable output includes the actual authenticated envelope and tag overhead. */
    private ByteBuf ciphertext;

    /** Reusable verified output whose checksum is consumed by JMH. */
    private ByteBuf decoded;

    /** Changing input prevents a constant payload result from replacing cryptographic work. */
    private int counter;

    /**
     * Allocates buffers and fresh sender/receiver keys outside each measured iteration.
     *
     * @throws GeneralSecurityException if the required JCA algorithms are unavailable
     */
    @Setup(Level.Iteration)
    public void setup() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        SecretKey secret = generator.generateKey();
        sender = new AuthenticatedFrameCipher(secret, true, true);
        receiver = new AuthenticatedFrameCipher(secret, false, true);
        plaintext = Unpooled.buffer(size).writeZero(size);
        ciphertext = Unpooled.buffer(size + AuthenticatedFrameCipher.OVERHEAD);
        decoded = Unpooled.buffer(size);
        frame = FrameData.create(UnpooledByteBufAllocator.DEFAULT, 0xfd, plaintext);
        frame.setReliability(FrameData.Reliability.RELIABLE_ORDERED);
    }

    /**
     * Measures encryption, authentication, and duplicate-history maintenance for one packet.
     *
     * @return decoded byte consumed by JMH to keep the complete operation observable
     * @throws GeneralSecurityException if the candidate violates its session limits
     */
    @Benchmark
    public int roundTrip() throws GeneralSecurityException {
        plaintext.setByte(0, counter++);
        ciphertext.clear();
        decoded.clear();
        sender.transform(frame, plaintext, ciphertext);
        receiver.transform(frame, ciphertext, decoded);
        return decoded.getUnsignedByte(0);
    }

    /** Releases iteration-owned buffers after the measurements complete. */
    @TearDown(Level.Iteration)
    public void close() {
        frame.release();
        plaintext.release();
        ciphertext.release();
        decoded.release();
    }
}
