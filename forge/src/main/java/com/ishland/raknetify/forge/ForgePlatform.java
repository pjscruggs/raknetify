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

package com.ishland.raknetify.forge;

import com.ishland.raknetify.minecraft.ModPlatform;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Path;

/** Reads Forge's early discovery state without forcing Minecraft class initialization. */
public final class ForgePlatform implements ModPlatform {

    /** Constructs the loader service before game mixins are selected. */
    public ForgePlatform() {
    }

    /**
     * Reads the built-in Minecraft mod entry without loading game code.
     * @return Minecraft's version from Forge's built-in mod metadata
     */
    @Override
    public String minecraftVersion() {
        return LoadingModList.getModFileById("minecraft")
                .getMods().stream().filter(mod -> mod.getModId().equals("minecraft"))
                .findFirst().orElseThrow().getVersion().toString();
    }

    /**
     * Selects the physical distribution used for client-only initialization.
     * @return whether Forge is running its physical client distribution
     */
    @Override
    public boolean isClient() {
        return FMLLoader.getDist().isClient();
    }

    /**
     * Determines whether development-only mixin auditing is appropriate.
     * @return whether Forge is running a development launch
     */
    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    /**
     * Queries the early mod inventory used while mixin configurations are loaded.
     * @param id Forge mod identifier
     * @return whether the mod was discovered for this launch
     */
    @Override
    public boolean isModLoaded(String id) {
        return LoadingModList.getModFileById(id) != null;
    }

    /**
     * Uses the instance configuration directory selected by the loader.
     * @return Forge's instance configuration directory
     */
    @Override
    public Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** Leaves registration to mods calling the optional PacketOrdering API during their setup. */
    @Override
    public void initializePacketOrdering() {
    }
}
