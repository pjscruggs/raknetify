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
import com.ishland.raknetify.api.PacketOrdering;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.timeout.ReadTimeoutHandler;
import network.ycc.raknet.RakNet;
import network.ycc.raknet.client.channel.RakNetClientThreadedChannel;
import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.pipeline.ReliabilityHandler;
import network.ycc.raknet.server.channel.RakNetApplicationChannel;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;

import static com.ishland.raknetify.common.util.ReflectionUtil.accessible;

/**
 * Installs common transport tuning, metrics, synchronization, and framing support on RakNet connections.
 */
public class RakNetConnectionUtil {

    /**
     * IPv4 type-of-service flag requesting low delay when honored by the network.
     */
    public static final int IP_TOS_LOWDELAY = 0b00010000;
    /**
     * IPv4 type-of-service flag requesting throughput when honored by the network.
     */
    public static final int IP_TOS_THROUGHPUT = 0b00001000;
    /**
     * IPv4 type-of-service flag requesting reliability when honored by the network.
     */
    public static final int IP_TOS_RELIABILITY = 0b00000100;

    /**
     * Default socket traffic-class request combining low-delay and throughput flags.
     */
    public static final int DEFAULT_IP_TOS = IP_TOS_LOWDELAY | IP_TOS_THROUGHPUT;

    /**
     * Prevents construction of the shared channel-initialization utility.
     */
    private RakNetConnectionUtil() {
    }

    /**
     * Send priority: unreliable before reliable, unordered before ordered, then channel and order index.
     */
    private static final Comparator<Frame> cmp =
            Comparator
                    .comparingInt((Frame frame) -> frame.getReliability().isReliable ? 1 : 0) // unreliable then reliable
                    .thenComparingInt(frame -> frame.getReliability().isOrdered ? 1 : 0) // unordered then ordered
                    .thenComparingInt(Frame::getOrderChannel) // lower channel first
                    .thenComparingInt(Frame::getOrderIndex); // lower index first

    /**
     * Applies queue and retry limits and installs the common application-side transport handlers.
     * TCP channels are left unchanged. Platform integrations install their Minecraft codec separately.
     *
     * @param channel newly created application channel to configure
     */
    public static void initChannel(Channel channel) {
        if (channel.config() instanceof RakNet.Config config) {
            config.setMaxQueuedBytes(Constants.MAX_QUEUED_SIZE);
            config.setMaxPendingFrameSets(Constants.MAX_PENDING_FRAME_SETS);
            config.setRetryDelayNanos(TimeUnit.NANOSECONDS.convert(50, TimeUnit.MILLISECONDS));
            config.setDefaultPendingFrameSets(Constants.DEFAULT_PENDING_FRAME_SETS);
            config.setNACKEnabled(false);
            config.setNoDelayEnabled(false);
//            config.setIgnoreResendGauge(true);

            initRaknetChannel(channel);

//            channel.pipeline().addLast("raknetify-flush-enforcer", new FlushEnforcer());
//            channel.pipeline().addLast("raknetify-flush-consolidation", new FlushConsolidationHandler(Integer.MAX_VALUE, true));
            channel.pipeline().addLast("raknetify-graceful-close", new GracefulCloseHandler());
            channel.pipeline().addLast("raknetify-no-flush", new NoFlush());
            channel.pipeline().addLast(MultiChannelingStreamingCompression.NAME, new MultiChannelingStreamingCompression(Constants.RAKNET_GAME_PACKET_ID, Constants.RAKNET_STREAMING_COMPRESSION_PACKET_ID));
            channel.pipeline().addLast(PayloadOrderingNegotiation.NAME, new PayloadOrderingNegotiation(PacketOrdering.snapshot()));
//            channel.pipeline().addLast(MultiChannellingDataCodec.NAME, new MultiChannellingDataCodec(Constants.RAKNET_GAME_PACKET_ID));
            channel.pipeline().addLast("raknetify-frame-data-blocker", new FrameDataBlocker());
        }
    }

    /**
     * Removes Polymer's TCP HTTP detector after other mods have initialized the application pipeline.
     * RakNet delivers reference-counted frames here, which the detector cannot forward or release.
     * HTTP resource-pack downloads still use the separate TCP listener and retain its detector.
     *
     * @param channel initialized application channel, left unchanged for TCP connections
     */
    public static void removeTcpOnlyHandlers(Channel channel) {
        if (!(channel.config() instanceof RakNet.Config)) {
            return;
        }
        final String name = "polymer:autohost/protocol_switcher";
        if (channel.pipeline().get(name) != null) {
            channel.pipeline().remove(name);
        }
    }

    /**
     * Finds the underlying transport channel and installs handlers before cross-thread delivery.
     *
     * @param appChannel application-facing channel or a transport channel without a threaded wrapper
     */
    private static void initRaknetChannel(Channel appChannel) {
        final Channel channel;
        final String threadedReadHandlerName;
        if (appChannel instanceof RakNetApplicationChannel) {
            channel = appChannel.parent();
            threadedReadHandlerName = RakNetApplicationChannel.NAME_SERVER_PARENT_THREADED_READ_HANDLER;
        } else if (appChannel instanceof RakNetClientThreadedChannel) {
            channel = appChannel.parent();
            threadedReadHandlerName = RakNetClientThreadedChannel.NAME_CLIENT_PARENT_THREADED_READ_HANDLER;
        } else {
            channel = appChannel;
            threadedReadHandlerName = null;
        }
        channel.pipeline().addLast(new ChannelInitializer<>() {
            /**
             * Installs connection-local metrics, ordering synchronization, and the transport read timeout.
             *
             * @param ch transport channel whose event loop owns the new handlers
             */
            @Override
            protected void initChannel(Channel ch) {
                final RakNet.Config config = (RakNet.Config) ch.config();
                final SimpleMetricsLogger simpleMetricsLogger = new SimpleMetricsLogger();
                config.setMetrics(simpleMetricsLogger);
                final MetricsSynchronizationHandler metricsSynchronizationHandler = new MetricsSynchronizationHandler();
                simpleMetricsLogger.setMetricsSynchronizationHandler(metricsSynchronizationHandler);
                final SynchronizationLayer synchronizationLayer = new SynchronizationLayer(Constants.SYNC_IGNORE_CHANNELS);
                reInitChannelForOrdering(channel);
                if (threadedReadHandlerName != null) {
                    ch.pipeline().addBefore(threadedReadHandlerName, "raknetify-metrics-sync", metricsSynchronizationHandler);
                    ch.pipeline().addBefore(threadedReadHandlerName, "raknetify-synchronization-layer", synchronizationLayer);
                } else {
                    ch.pipeline().addLast("raknetify-metrics-sync", metricsSynchronizationHandler);
                    ch.pipeline().addLast("raknetify-synchronization-layer", synchronizationLayer);
                }
                ch.pipeline().addFirst("raknetify-timeout", new ReadTimeoutHandler(15));
            }
        });
    }

//    public static void postInitChannel(Channel channel, boolean isClientSide) {
//        if (channel.config() instanceof RakNet.Config) {
//            ViaFabricCompatInjector.inject(channel, isClientSide);
//            channel.pipeline().replace("timeout", "timeout", new ChannelDuplexHandler()); // no-op
//            channel.pipeline().replace("splitter", "splitter", new ChannelDuplexHandler()); // no-op
//            channel.pipeline().replace("prepender", "prepender", new ChannelDuplexHandler()); // no-op
//            final MultiChannellingPacketCapture handler = new MultiChannellingPacketCapture();
//            channel.pipeline().addLast("raknetify-multi-channel-packet-cature", handler);
//            channel.pipeline().get(MultiChannellingDataCodec.class).setCapture(handler);
//        }
//    }

    /**
     * Replaces the transport send queue with the shared channel-priority comparator.
     * Existing frames are retained. Reflection failures are logged without aborting channel initialization.
     *
     * @param channel transport channel containing the reliability handler
     */
    @SuppressWarnings("unchecked")
    private static void reInitChannelForOrdering(Channel channel) {
        if (channel.config() instanceof RakNet.Config config) {
            try {
                final ReliabilityHandler reliabilityHandler = channel.pipeline().get(ReliabilityHandler.class);
                final Field frameQueueField = accessible(ReliabilityHandler.class.getDeclaredField("frameQueue"));
                PriorityQueue<Frame> reliabilityHandlerFrameQueue = (PriorityQueue<Frame>) frameQueueField.get(reliabilityHandler);

                final PriorityQueue<Frame> newSet = new PriorityQueue<>(cmp);
                newSet.addAll(reliabilityHandlerFrameQueue);

                frameQueueField.set(reliabilityHandler, newSet);

            } catch (Throwable t) {
                System.err.println("Raknetify: Error occurred while reinitializing channel ordering");
                t.printStackTrace();
            }
        }
    }

}
