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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Measures one new proxy binding while other connections remain pinned to their identities. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProxyBenchmark {
    /** Existing active connections include a nearly full bounded listener. */
    @Param({"32", "1024", "4095"})
    public int connections;

    /** Listener-local identity table shared by the simulated existing connections. */
    private ProxySessions sessions;

    /** Already parsed metadata keeps header parsing outside this bookkeeping measurement. */
    private ProxyProtocolHeader header;

    /** Physical proxy tuple reused only after releasing the previous binding. */
    private InetSocketAddress endpoint;

    /**
     * Populates active bindings before measurement and freezes time to isolate table operations.
     */
    @Setup
    public void setup() {
        header =
                new ProxyProtocolHeader(
                        new InetSocketAddress("192.0.2.1", 1234),
                        new InetSocketAddress("198.51.100.1", 25565),
                        28);
        endpoint = new InetSocketAddress("127.0.0.1", 60000);
        sessions = new ProxySessions(() -> 0L);
        for (int i = 1; i <= connections; i++) {
            var peer = new InetSocketAddress("127.0.0.1", i);
            sessions.resolve(peer, header);
            sessions.claim(peer);
        }
    }

    /**
     * Allocates and releases one valid session without displacing an existing active connection.
     *
     * @return binding consumed by JMH so allocation cannot be eliminated
     * @throws AssertionError if the table incorrectly rejects its final available slot
     */
    @Benchmark
    public Object acceptAndClose() {
        var session = sessions.resolve(endpoint, header);
        if (session == null) {
            throw new AssertionError("Missing available slot");
        }
        sessions.release(endpoint, session);
        return session;
    }
}
