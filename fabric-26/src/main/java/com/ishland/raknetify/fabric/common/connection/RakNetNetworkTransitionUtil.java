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

package com.ishland.raknetify.fabric.common.connection;

import net.minecraft.network.UnconfiguredPipelineHandler;

/**
 * Preserves packet-capture placement when Minecraft reconfigures its inbound or outbound codecs.
 */
public class RakNetNetworkTransitionUtil {

    /**
     * Wraps recognized configuration tasks to restore capture placement after the original task
     * runs.
     *
     * @param msg outgoing pipeline task or unrelated message
     * @return wrapped configuration task, or the unchanged message when no wrapper is needed
     */
    static Object handleTransition(final Object msg) {
        if (msg instanceof UnconfiguredPipelineHandler.InboundConfigurationTask transitioner) {
            return (UnconfiguredPipelineHandler.InboundConfigurationTask)
                    context -> {
                        transitioner.run(context);
                        RakNetFabricConnectionUtil.onPipelineReorder(context.pipeline());
                    };
        } else if (msg
                instanceof UnconfiguredPipelineHandler.OutboundConfigurationTask transitioner) {
            return (UnconfiguredPipelineHandler.OutboundConfigurationTask)
                    context -> {
                        transitioner.run(context);
                        RakNetFabricConnectionUtil.onPipelineReorder(context.pipeline());
                    };
        }
        return msg;
    }
}
