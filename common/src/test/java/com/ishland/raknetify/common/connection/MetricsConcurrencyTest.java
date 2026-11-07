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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Verifies cumulative transport metrics after independently scheduled callback writers finish. */
class MetricsConcurrencyTest {
    /**
     * Concurrent byte callbacks must retain every increment and worker exceptions must fail the
     * test.
     *
     * @throws Exception if a worker fails, is interrupted or exceeds the bounded completion
     *     deadline
     */
    @Test
    void completedCallbacksDoNotLoseBytes() throws Exception {
        final SimpleMetricsLogger metrics = new SimpleMetricsLogger();
        final ExecutorService workers = Executors.newFixedThreadPool(4);
        final CountDownLatch ready = new CountDownLatch(4);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<?>> completions = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                completions.add(
                        workers.submit(() -> {
                            ready.countDown();
                            start.await();
                            for (int sample = 0; sample < 50000; sample++) {
                                metrics.bytesIn(3);
                            }
                            return null;
                        }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (final Future<?> completion : completions) {
                completion.get(5, TimeUnit.SECONDS);
            }
            assertEquals(600000, metrics.getBytesIn());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
