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

package com.ishland.raknetify.minecraft;

import java.nio.file.Path;
import java.util.ServiceLoader;

/** Supplies loader metadata without resolving Minecraft classes during mixin selection. */
public interface ModPlatform {

    /** The distribution supplies exactly one provider in its service descriptor. */
    ModPlatform INSTANCE = load();

    /**
     * Resolves the adapter from this runtime's class loader and rejects ambiguous distributions.
     * @return the only installed platform service
     * @throws IllegalStateException if no adapter or more than one adapter is available
     */
    private static ModPlatform load() {
        var providers = ServiceLoader.load(ModPlatform.class, ModPlatform.class.getClassLoader()).iterator();
        if (!providers.hasNext()) throw new IllegalStateException("Missing Raknetify2 loader adapter");
        ModPlatform selected = providers.next();
        if (providers.hasNext()) throw new IllegalStateException("Multiple Raknetify2 loader adapters");
        return selected;
    }

    /**
     * Returns the adapter supplied by the installed loader distribution.
     * @return loader-specific configuration and discovery operations
     */
    static ModPlatform get() {
        return INSTANCE;
    }

    /**
     * Reads Minecraft's version from loader metadata, before Minecraft classes are initialized.
     * @return Minecraft version identifier declared by the running loader
     */
    String minecraftVersion();

    /**
     * Tests the physical distribution without referencing dedicated-client Minecraft classes.
     * @return true for a client process, including an integrated server
     */
    boolean isClient();

    /**
     * Checks whether loader development facilities are active.
     * @return true when development-only validation is appropriate
     */
    boolean isDevelopmentEnvironment();

    /**
     * Looks up a mod in the loader's discovery inventory, including during early mixin selection.
     * @param id loader mod identifier
     * @return whether the mod was discovered for this instance
     */
    boolean isModLoaded(String id);

    /**
     * Locates the configuration directory chosen by the active loader.
     * @return directory containing user-editable transport settings
     */
    Path configDirectory();

    /** Invokes loader-specific optional packet-ordering registrations during normal startup. */
    void initializePacketOrdering();
}
