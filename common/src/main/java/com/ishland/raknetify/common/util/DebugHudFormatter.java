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

package com.ishland.raknetify.common.util;

import com.ishland.raknetify.common.connection.MetricsSynchronizationHandler;
import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.SimpleMetricsLogger;

import io.netty.channel.Channel;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Formats transport statistics without depending on a particular Minecraft client's classes. */
public final class DebugHudFormatter {

    /** Prevents instances of the shared HUD formatter. */
    private DebugHudFormatter() {}

    /**
     * Emits the active RakNet connection's HUD lines in their display order.
     *
     * @param consumer destination for complete display lines
     * @param channel connection channel, or null while the client is disconnected
     * @return true when RakNet details were emitted, false for a missing or ordinary TCP channel
     */
    public static boolean append(final Consumer<String> consumer, final @Nullable Channel channel) {
        if (channel == null || !(channel.config() instanceof RakNet.Config config)) {
            return false;
        }
        if (config.getMetrics() instanceof SimpleMetricsLogger logger) {
            appendConnectionMetrics(consumer, config, logger);
        } else {
            consumer.accept("[Raknetify2] A: true, MTU: %d".formatted(config.getMTU()));
        }
        final MultiChannelingStreamingCompression compression =
                channel.pipeline().get(MultiChannelingStreamingCompression.class);
        if (compression != null && compression.isActive()) {
            consumer.accept(
                    "[Raknetify2] CRatio: I: %.2f%%, O: %.2f%%"
                            .formatted(
                                    compression.getInCompressionRatio() * 100,
                                    compression.getOutCompressionRatio() * 100));
        }
        return true;
    }

    /**
     * Formats local measurements and includes peer statistics only after metrics negotiation.
     *
     * @param consumer destination for each display line
     * @param config transport configuration supplying the MTU and pending-frame allowance
     * @param logger local traffic samples and the optional remote metrics report
     */
    private static void appendConnectionMetrics(
            final Consumer<String> consumer,
            final RakNet.Config config,
            final SimpleMetricsLogger logger) {
        consumer.accept(
                "[Raknetify2] A: true, MTU: %d, RTT: %.2f/%.2fms"
                        .formatted(
                                config.getMTU(),
                                logger.getMeasureRTTns() / 1_000_000.0,
                                logger.getMeasureRTTnsStdDev() / 1_000_000.0));
        final MetricsSynchronizationHandler serverSync = logger.getMetricsSynchronizationHandler();
        if (serverSync != null && serverSync.isRemoteSupported()) {
            consumer.accept(
                    "[Raknetify2] C: BUF: %.2fMB; S: BUF: %.2fMB"
                            .formatted(
                                    logger.getCurrentQueuedBytes() / 1024.0 / 1024.0,
                                    serverSync.getQueuedBytes() / 1024.0 / 1024.0));
        } else {
            consumer.accept(
                    "[Raknetify2] C: BUF: %.2fMB"
                            .formatted(logger.getCurrentQueuedBytes() / 1024.0 / 1024.0));
        }

        consumer.accept(
                "[Raknetify2] C: I: %s, O: %s"
                        .formatted(
                                logger.getMeasureTrafficInFormatted(),
                                logger.getMeasureTrafficOutFormatted()));

        consumer.accept(
                "[Raknetify2] C: ERR: %.4f%%, %d tx, %d rx, BST: %d"
                        .formatted(
                                logger.getMeasureErrorRate() * 100.0,
                                logger.getMeasureTX(),
                                logger.getMeasureRX(),
                                logger.getMeasureBurstTokens()
                                        + config.getDefaultPendingFrameSets()));
        if (serverSync != null && serverSync.isRemoteSupported()) {
            consumer.accept(
                    "[Raknetify2] S: ERR: %.4f%%, %d tx, %d rx, BST: %d"
                            .formatted(
                                    serverSync.getErrorRate() * 100.0,
                                    serverSync.getTX(),
                                    serverSync.getRX(),
                                    serverSync.getBurst()));
        }
    }
}
