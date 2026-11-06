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

package com.ishland.raknetify.fabric.common.util;

import com.ishland.raknetify.minecraft.ModPlatform;

/**
 * Resolves modern Minecraft API boundaries once so incompatible class references remain guarded.
 */
public final class MinecraftVersions {

    /**
     * Minecraft version reported by the active loader without initializing game classes.
     */
    private static final String CURRENT = ModPlatform.get().minecraftVersion();

    /**
     * Whether packet references introduced in Minecraft 26.2 may be resolved.
     */
    public static final boolean AT_LEAST_26_2 = atLeast(26, 2);
    /**
     * Whether packet references introduced in Minecraft 26.3 may be resolved.
     */
    public static final boolean AT_LEAST_26_3 = atLeast(26, 3);
    // Keep this exact snapshot predicate aligned with the Minecraft dependency in fabric.mod.json.
    /**
     * Whether the runtime matches the specifically supported 26.4 snapshot API.
     */
    public static final boolean IS_26_4_SNAPSHOT = CURRENT.equals("26.4-alpha.7")
            || CURRENT.equals("26.4-snapshot-7");

    /**
     * Prevents construction of the immutable runtime-version flags.
     */
    private MinecraftVersions() {
    }

    /**
     * Compares the numeric release family shared by the supported modern loader versions.
     *
     * @param major earliest supported release year
     * @param minor earliest supported release within that year
     * @return whether the current runtime reaches this API boundary
     * @throws NumberFormatException if the loader supplies a nonnumeric release family
     */
    private static boolean atLeast(int major, int minor) {
        String[] components = CURRENT.split("[.\\-+]");
        int currentMajor = Integer.parseInt(components[0]);
        int currentMinor = Integer.parseInt(components[1]);
        return currentMajor > major || currentMajor == major && currentMinor >= minor;
    }
}
