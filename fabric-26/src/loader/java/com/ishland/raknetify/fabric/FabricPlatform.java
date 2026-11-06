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

package com.ishland.raknetify.fabric;

import com.ishland.raknetify.fabric.common.util.PacketChannelEntrypoints;
import com.ishland.raknetify.minecraft.ModPlatform;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/** Connects the shared modern runtime to Fabric's discovery and entrypoint APIs. */
public final class FabricPlatform implements ModPlatform {

    /** Creates the service provider without accessing Minecraft classes. */
    public FabricPlatform() {}

    /**
     * Reads the built-in Minecraft mod entry without loading game code.
     *
     * @return Minecraft's normalized version from Fabric's built-in mod metadata
     */
    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow()
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }

    /**
     * Selects the physical distribution used for client-only initialization.
     *
     * @return whether Fabric is running the physical client distribution
     */
    @Override
    public boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    /**
     * Determines whether development-only mixin auditing is appropriate.
     *
     * @return whether Fabric is running in a development environment
     */
    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    /**
     * Checks Fabric's already-discovered mods without initializing their classes.
     *
     * @param id Fabric mod identifier
     * @return whether Fabric discovered this mod
     */
    @Override
    public boolean isModLoaded(final String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    /**
     * Uses the instance configuration directory selected by the loader.
     *
     * @return Fabric's configured instance configuration directory
     */
    @Override
    public Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** Loads the existing JDK-only optional packet-ordering entrypoints. */
    @Override
    public void initializePacketOrdering() {
        PacketChannelEntrypoints.initialize();
    }
}
