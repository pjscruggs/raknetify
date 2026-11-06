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

import com.ishland.raknetify.fabric.common.util.MinecraftVersions;

import net.minecraft.network.protocol.common.ClientboundTransferPacket;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Preserves the transfer properties introduced in 26.4 while retaining the earlier packet
 * constructor.
 */
public final class TransferPacketUtil {

    /** Prevents construction of the version-specific packet adapter. */
    private TransferPacketUtil() {}

    /**
     * Changes only the transport address, carrying every native transfer property through
     * unchanged.
     *
     * @param original native transfer whose optional properties belong to Minecraft and other mods
     * @param host selected RakNet-prefixed destination
     * @param port selected UDP port
     * @return equivalent native transfer with the new transport address
     * @throws IllegalStateException if the supported snapshot's transfer constructor changes
     */
    public static ClientboundTransferPacket replace(
            final ClientboundTransferPacket original, final String host, final int port) {
        if (!MinecraftVersions.IS_26_4_SNAPSHOT) {
            return new ClientboundTransferPacket(host, port);
        }
        try {
            return Snapshot.CONSTRUCTOR.newInstance(
                    host, port, Snapshot.PROPERTIES.invoke(original));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot preserve native transfer properties", failure);
        }
    }

    /** Resolves the newer record contract only when running a version that supplies it. */
    private static final class Snapshot {

        /** Constructor including the complete map of transfer properties. */
        private static final Constructor<ClientboundTransferPacket> CONSTRUCTOR;

        /** Record accessor returning the original immutable transfer metadata. */
        private static final Method PROPERTIES;

        static {
            try {
                CONSTRUCTOR =
                        ClientboundTransferPacket.class.getConstructor(
                                String.class, int.class, Map.class);
                PROPERTIES = ClientboundTransferPacket.class.getMethod("properties");
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        /** Prevents creating instances of the lazily resolved snapshot contract. */
        private Snapshot() {}
    }
}
