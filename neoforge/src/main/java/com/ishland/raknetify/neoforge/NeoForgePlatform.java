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

package com.ishland.raknetify.neoforge;

import com.ishland.raknetify.minecraft.ModPlatform;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** Reads NeoForge's early discovery state without forcing Minecraft class initialization. */
public final class NeoForgePlatform implements ModPlatform {

    /** Constructs the loader service before game mixins are selected. */
    public NeoForgePlatform() {}

    /**
     * Reads the built-in Minecraft mod entry without loading game code.
     *
     * @return Minecraft's version from NeoForge's built-in mod metadata
     */
    @Override
    public String minecraftVersion() {
        return FMLLoader.getCurrent()
                .getLoadingModList()
                .getModFileById("minecraft")
                .getMods()
                .stream()
                .filter(mod -> "minecraft".equals(mod.getModId()))
                .findFirst()
                .orElseThrow()
                .getVersion()
                .toString();
    }

    /**
     * Selects the physical distribution used for client-only initialization.
     *
     * @return whether NeoForge is running its physical client distribution
     */
    @Override
    public boolean isClient() {
        return FMLLoader.getCurrent().getDist().isClient();
    }

    /**
     * Determines whether development-only mixin auditing is appropriate.
     *
     * @return whether NeoForge is running a development launch
     */
    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.getCurrent().isProduction();
    }

    /**
     * Queries the early mod inventory used while mixin configurations are loaded.
     *
     * @param id NeoForge mod identifier
     * @return whether the mod was discovered for this launch
     */
    @Override
    public boolean isModLoaded(final String id) {
        return FMLLoader.getCurrent().getLoadingModList().getModFileById(id) != null;
    }

    /**
     * Uses the instance configuration directory selected by the loader.
     *
     * @return NeoForge's instance configuration directory
     */
    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Leaves registration to mods calling the optional PacketOrdering API during their setup. */
    @Override
    public void initializePacketOrdering() {}
}
