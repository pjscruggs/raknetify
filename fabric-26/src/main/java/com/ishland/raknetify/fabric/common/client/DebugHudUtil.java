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

package com.ishland.raknetify.fabric.common.client;

import com.ishland.raknetify.common.util.DebugHudFormatter;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import io.netty.channel.Channel;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;

public class DebugHudUtil {

    public static void getDebugString(Consumer<String> consumer) {
        final ClientPacketListener networkHandler = Minecraft.getInstance().getConnection();
        if (networkHandler != null) {
            final Connection connection = networkHandler.getConnection();
            final Channel channel = ((IClientConnection) connection).getChannel();
            if (DebugHudFormatter.append(consumer, channel)) return;
        }
        consumer.accept("[Raknetify2] A: false");
    }

}
