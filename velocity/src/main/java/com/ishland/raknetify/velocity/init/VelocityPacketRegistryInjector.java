/*
 * This file is a part of the Velocity implementation of the Raknetify
 * project, licensed under GPLv3.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ishland.raknetify.velocity.init;

import static com.ishland.raknetify.common.util.ReflectionUtil.accessible;

import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;

import io.netty.util.collection.IntObjectMap;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Makes Velocity decode respawn packets so world transitions are visible to transport
 * synchronization.
 */
public class VelocityPacketRegistryInjector {

    /**
     * Adds missing respawn decoders to play registries that already know the corresponding encoder
     * ID. Existing decoders and protocol IDs remain unchanged.
     *
     * @throws RuntimeException if Velocity's packet registry internals cannot be inspected or
     *     updated
     */
    public static void inject() {
        try {
            final StateRegistry.PacketRegistry registry =
                    (StateRegistry.PacketRegistry)
                            accessible(StateRegistry.class.getDeclaredField("clientbound"))
                                    .get(StateRegistry.PLAY);
            final var versions =
                    (Map<?, ?>)
                            accessible(
                                            StateRegistry.PacketRegistry.class.getDeclaredField(
                                                    "versions"))
                                    .get(registry);
            for (Object entry : versions.values()) {
                final StateRegistry.PacketRegistry.ProtocolRegistry value =
                        StateRegistry.PacketRegistry.ProtocolRegistry.class.cast(entry);
                final var packetClassToId =
                        (Map<?, ?>)
                                accessible(
                                                StateRegistry.PacketRegistry.ProtocolRegistry.class
                                                        .getDeclaredField("packetClassToId"))
                                        .get(value);
                final var packetIdToSupplier =
                        (IntObjectMap<?>)
                                accessible(
                                                StateRegistry.PacketRegistry.ProtocolRegistry.class
                                                        .getDeclaredField("packetIdToSupplier"))
                                        .get(value);
                // Share only JDK map types across the proxy and plugin dependency loaders.
                final Object registeredId = packetClassToId.get(RespawnPacket.class);
                if (registeredId != null) {
                    if (!(registeredId instanceof Integer packetId)) {
                        throw new IllegalStateException("Respawn packet ID is not an integer");
                    }
                    if (!packetIdToSupplier.containsKey(packetId)) {
                        installRespawnDecoder(packetIdToSupplier, packetId);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException t) {
            throw new RuntimeException("Failed to inject velocity packet registry", t);
        }
    }

    /**
     * Adds the known packet constructor through the erased API of Velocity's private decoder map.
     * The caller obtains the ID from this registry's RespawnPacket encoder entry and checks it is
     * vacant. No cast claims that unrelated map values have a generic type they have not been
     * checked against.
     *
     * @param decoders private map already verified to implement Netty's integer-keyed map interface
     * @param packetId registered RespawnPacket wire identifier for this protocol version
     * @throws ReflectiveOperationException if the private registry cannot accept the validated
     *     constructor
     */
    private static void installRespawnDecoder(final IntObjectMap<?> decoders, final int packetId)
            throws ReflectiveOperationException {
        final Supplier<MinecraftPacket> factory = RespawnPacket::new;
        IntObjectMap.class
                .getMethod("put", int.class, Object.class)
                .invoke(decoders, packetId, factory);
    }
}
