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

package com.ishland.raknetify.velocity;

import static com.ishland.raknetify.velocity.RaknetifyVelocityPlugin.LOGGER;

import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;
import com.ishland.raknetify.velocity.connection.RakNetVelocityConnectionUtil;
import com.ishland.raknetify.velocity.init.VelocityPacketRegistryInjector;
import com.ishland.raknetify.velocity.init.VelocityRaknetifyServer;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ListenerBoundEvent;
import com.velocitypowered.api.event.proxy.ListenerCloseEvent;

/**
 * Initializes Velocity integration after the plugin's dependency-isolating class loader is
 * selected.
 */
public class RaknetifyVelocityLaunchWrapper {

    /** Matches Velocity's legacy LAST priority while using the current numeric registration API. */
    private static final short LAST_EVENT_PRIORITY = -32767;

    /**
     * Checks required Velocity internals, loads routing tables, and registers connection lifecycle
     * hooks. An unsupported proxy receives startup and listener-bound diagnostics instead of
     * partial injection.
     */
    public static void launch() {
        if (!isCompatible()) {
            Runnable runnable =
                    () -> {
                        LOGGER.error(
                                "This version of Raknetify is NOT compatible with your version of"
                                        + " Velocity");
                        LOGGER.error(
                                "Please update your Velocity at"
                                        + " https://papermc.io/downloads#Velocity");
                    };
            runnable.run();
            RaknetifyVelocityPlugin.proxy()
                    .getEventManager()
                    .register(
                            RaknetifyVelocityPlugin.instance(),
                            ListenerBoundEvent.class,
                            LAST_EVENT_PRIORITY,
                            ignored -> runnable.run());
            return;
        }

        ProtocolMultiChannelMappings.init();
        VelocityPacketRegistryInjector.inject();

        RaknetifyVelocityPlugin.proxy()
                .getEventManager()
                .register(
                        RaknetifyVelocityPlugin.instance(),
                        LoginEvent.class,
                        LAST_EVENT_PRIORITY,
                        RakNetVelocityConnectionUtil::onPlayerLogin);
        RaknetifyVelocityPlugin.proxy()
                .getEventManager()
                .register(
                        RaknetifyVelocityPlugin.instance(),
                        ListenerBoundEvent.class,
                        LAST_EVENT_PRIORITY,
                        VelocityRaknetifyServer::start);
        RaknetifyVelocityPlugin.proxy()
                .getEventManager()
                .register(
                        RaknetifyVelocityPlugin.instance(),
                        ListenerCloseEvent.class,
                        LAST_EVENT_PRIORITY,
                        VelocityRaknetifyServer::stop);
        RaknetifyVelocityPlugin.proxy()
                .getEventManager()
                .register(
                        RaknetifyVelocityPlugin.instance(),
                        ServerPostConnectEvent.class,
                        LAST_EVENT_PRIORITY,
                        RakNetVelocityConnectionUtil::onServerSwitch);
    }

    /**
     * Checks for the Velocity implementation classes required by this integration.
     *
     * @return true when encryption utilities and modern plugin-message packets are available
     */
    private static boolean isCompatible() {
        try {
            Class.forName("com.velocitypowered.proxy.crypto.EncryptionUtils");
            Class.forName("com.velocitypowered.proxy.protocol.packet.PluginMessagePacket");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
