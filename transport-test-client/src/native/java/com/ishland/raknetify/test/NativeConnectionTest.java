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
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;
import com.ishland.raknetify.fabric.mixin.access.IClientConnection;

import io.netty.channel.Channel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import network.ycc.raknet.RakNet;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Joins a disposable local server through the real client login, configuration and gameplay
 * pipeline.
 */
public final class NativeConnectionTest {

    /** Whether the preceding packet-contract tests completed and requested a network join. */
    private static boolean armed;

    /** Whether the normal connection screen has already started the single fixture connection. */
    private static boolean connecting;

    /**
     * Prevents repeated final chat writes while the transport drains and closes before client
     * shutdown.
     */
    private static boolean finishing;

    /**
     * Whether the final chat was sent and the fixture is waiting for the server's gameplay
     * acknowledgement.
     */
    private static boolean chatSent;

    /** Application channel retained while the fixture verifies complete transport shutdown. */
    private static @Nullable Channel closingChannel;

    /**
     * Monotonic deadline covering resource loading, login and receipt of the first playable world.
     */
    private static long deadline;

    /** Last observed native dimension during the optional cross-world ordering regression. */
    private static @Nullable String lastDimension;

    /** Completed dimension changes after initial arrival in the overworld. */
    private static int worldChanges;

    /**
     * Client world tick at the final arrival, used to observe a full second of subsequent gameplay
     * packets.
     */
    private static long finalWorldTick = -1;

    /** Prevents constructing this client-thread-only fixture state. */
    private NativeConnectionTest() {}

    /**
     * Allows the tick hook to start one real connection after ordinary client resource loading
     * completes.
     */
    static void arm() {
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        armed = true;
    }

    /**
     * Advances the isolated client connection without replacing native packet handlers.
     *
     * @param minecraft active client supplied by the transformed tick method
     */
    public static void tick(final Minecraft minecraft) {
        if (!armed) {
            return;
        }
        try {
            if (System.nanoTime() > deadline) {
                throw timeoutFailure();
            }
            if (!connecting) {
                if (minecraft.gui.overlay() == null) {
                    connect(minecraft);
                }
                return;
            }
            if (!finishing && minecraft.player != null && minecraft.level != null) {
                verifyJoined(minecraft);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Uses the same prefix parsing and connection screen as an ordinary direct-connect action.
     *
     * @param minecraft initialized client with no loading overlay
     */
    private static void connect(final Minecraft minecraft) {
        connecting = true;
        String destination = "raknet;127.0.0.1:" + Integer.getInteger("raknetify.test.port", 28990);
        ConnectScreen.startConnecting(
                new TitleScreen(),
                minecraft,
                ServerAddress.parseString(destination),
                new ServerData("Transport fixture", destination, ServerData.Type.OTHER),
                false,
                null);
    }

    /**
     * Requires a playable world and negotiated UDP streaming compression before reporting success.
     *
     * @param minecraft client that has received its real player and level from the server
     * @throws IllegalStateException if gameplay arrived without the expected RakNet transport and
     *     compression
     */
    private static void verifyJoined(final Minecraft minecraft) {
        final var listener = minecraft.getConnection();
        final var level = minecraft.level;
        final var player = minecraft.player;
        if (listener == null || level == null || player == null) {
            throw new IllegalStateException("Gameplay witness has no complete client world");
        }
        Channel channel = ((IClientConnection) listener.getConnection()).getChannel();
        if (!(channel.config() instanceof RakNet.Config)) {
            throw new IllegalStateException("Client joined over TCP");
        }
        var compression = channel.pipeline().get(MultiChannelingStreamingCompression.class);
        if (compression == null || !compression.isActive()) {
            throw new IllegalStateException("Streaming compression was not negotiated");
        }
        if (Boolean.getBoolean("raknetify.test.encrypted")
                && channel.pipeline().get(MultiChannellingEncryption.class) == null) {
            throw new IllegalStateException("Native login did not negotiate frame encryption");
        }
        if (!gameplayComplete(level, player, listener)) {
            return;
        }
        finishing = true;
        closingChannel = channel;
        channel.closeFuture()
                .addListener(future -> {
                    System.out.println("PASS native transport close completed");
                    minecraft.execute(() -> {
                        System.out.println(
                                "PASS native RakNet login, configuration and playable world");
                        armed = false;
                        minecraft.stop();
                    });
                });
        listener.getConnection().disconnect(Component.literal("Transport fixture complete"));
    }

    /**
     * Waits for every selected gameplay and voice witness before closing their shared transport.
     *
     * @param level current world whose chunks and tick counter establish readiness
     * @param player local player whose position and experience prove gameplay delivery
     * @param listener connection used for the final chat acknowledgement
     * @return whether dimension changes, chat and optional voice authentication have completed
     */
    private static boolean gameplayComplete(
            final ClientLevel level,
            final LocalPlayer player,
            final ClientPacketListener listener) {
        return verifyWorldChanges(level, player)
                && verifyChatAcknowledgement(player, listener)
                && (!Boolean.getBoolean("raknetify.test.voicechat")
                        || VoiceClientWitness.connected());
    }

    /**
     * Distinguishes incomplete gameplay from a stranded close and reports both transport layers.
     *
     * @return failure containing the actual channel state at the bounded deadline
     */
    private static IllegalStateException timeoutFailure() {
        StringBuilder state =
                new StringBuilder("Native RakNet test timed out, finishing=").append(finishing);
        for (Channel channel = closingChannel; channel != null; channel = channel.parent()) {
            state.append('\n')
                    .append(channel.getClass().getName())
                    .append(" open=")
                    .append(channel.isOpen())
                    .append(" active=")
                    .append(channel.isActive())
                    .append(" registered=")
                    .append(channel.isRegistered())
                    .append(" closed=")
                    .append(channel.closeFuture().isDone())
                    .append(" pipeline=")
                    .append(channel.pipeline());
        }
        return new IllegalStateException(state.toString());
    }

    /**
     * Requires the server-driven overworld, Nether, and overworld sequence when the integration
     * test requests it.
     *
     * @param level world installed by the latest native respawn packet
     * @param player local player whose current chunk must have arrived
     * @return true after the requested sequence, or immediately when world changes are not under
     *     test
     * @throws IllegalStateException if dimensions arrive out of order
     */
    private static boolean verifyWorldChanges(final ClientLevel level, final LocalPlayer player) {
        if (!Boolean.getBoolean("raknetify.test.worldChanges")) {
            return true;
        }
        if (!level.hasChunkAt(player.blockPosition())) {
            return false;
        }
        String dimension = level.dimension().identifier().toString();
        if (dimension.equals(lastDimension)) {
            return worldSettled(level);
        }
        if (lastDimension != null) {
            worldChanges++;
        }
        String expected = worldChanges == 1 ? "minecraft:the_nether" : "minecraft:overworld";
        if (worldChanges > 2 || !dimension.equals(expected)) {
            throw new IllegalStateException("Unexpected native world transition: " + dimension);
        }
        lastDimension = dimension;
        System.out.println("PASS native world boundary " + worldChanges + " " + dimension);
        return worldSettled(level);
    }

    /**
     * Keeps the final world active for twenty ticks so late entity and command packets are also
     * decoded.
     *
     * @param level current world supplying the monotonically advancing fixture tick
     * @return true after the final world has remained playable for twenty ticks
     */
    private static boolean worldSettled(final ClientLevel level) {
        if (worldChanges != 2) {
            return false;
        }
        if (finalWorldTick < 0) {
            finalWorldTick = level.getGameTime();
        }
        return level.getGameTime() - finalWorldTick >= 20;
    }

    /**
     * Sends the final chat once and waits for the server runner to acknowledge receipt with an
     * experience update.
     *
     * @param player local player whose experience level carries the server acknowledgement
     * @param listener active connection that sends the final chat once
     * @return true after the requested acknowledgement, or immediately outside the world-change
     *     regression
     */
    private static boolean verifyChatAcknowledgement(
            final LocalPlayer player, final ClientPacketListener listener) {
        if (!chatSent) {
            listener.sendChat("Raknetify transport fixture joined");
            chatSent = true;
            System.out.println("PASS native final chat sent");
        }
        return !Boolean.getBoolean("raknetify.test.worldChanges") || player.experienceLevel == 42;
    }
}
