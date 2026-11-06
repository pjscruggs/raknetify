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

package com.ishland.raknetify.test;

import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import network.ycc.raknet.RakNet;

import java.util.concurrent.TimeUnit;

/** Joins a disposable local server through the real client login, configuration and gameplay pipeline. */
public final class NativeConnectionTest {

    /** Whether the preceding packet-contract tests completed and requested a network join. */
    private static boolean armed;

    /** Whether the normal connection screen has already started the single fixture connection. */
    private static boolean connecting;

    /** Monotonic deadline covering resource loading, login and receipt of the first playable world. */
    private static long deadline;

    /** Prevents constructing this client-thread-only fixture state. */
    private NativeConnectionTest() {
    }

    /** Allows the tick hook to start one real connection after ordinary client resource loading completes. */
    static void arm() {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        armed = true;
    }

    /**
     * Advances the isolated client connection without replacing native packet handlers.
     * @param minecraft active client supplied by the transformed tick method
     */
    public static void tick(Minecraft minecraft) {
        if (!armed) return;
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Native RakNet join timed out");
            if (!connecting) {
                if (minecraft.gui.overlay() == null) connect(minecraft);
                return;
            }
            if (minecraft.player != null && minecraft.level != null) verifyJoined(minecraft);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Uses the same prefix parsing and connection screen as an ordinary direct-connect action.
     * @param minecraft initialized client with no loading overlay
     */
    private static void connect(Minecraft minecraft) {
        connecting = true;
        String destination = "raknet;127.0.0.1:28990";
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(destination),
                new ServerData("Transport fixture", destination, ServerData.Type.OTHER), false, null);
    }

    /**
     * Requires a playable world and negotiated UDP streaming compression before reporting success.
     * @param minecraft client that has received its real player and level from the server
     * @throws IllegalStateException if gameplay arrived without the expected RakNet transport and compression
     */
    private static void verifyJoined(Minecraft minecraft) {
        var listener = minecraft.getConnection();
        if (listener == null) throw new IllegalStateException("Player has no server connection");
        Channel channel = ((IClientConnection) listener.getConnection()).getChannel();
        if (!(channel.config() instanceof RakNet.Config)) throw new IllegalStateException("Client joined over TCP");
        var compression = channel.pipeline().get(MultiChannelingStreamingCompression.class);
        if (compression == null || !compression.isActive()) throw new IllegalStateException("Streaming compression was not negotiated");
        listener.sendChat("Raknetify transport fixture joined");
        System.out.println("PASS native RakNet login, configuration and playable world");
        armed = false;
        minecraft.stop();
    }
}
