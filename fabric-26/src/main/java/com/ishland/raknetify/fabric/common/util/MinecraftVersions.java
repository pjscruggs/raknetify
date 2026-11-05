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

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/**
 * Resolves modern Minecraft API boundaries once so incompatible class references remain guarded.
 */
public final class MinecraftVersions {

    /**
     * Minecraft version reported by Fabric Loader for the running instance.
     */
    private static final Version CURRENT = FabricLoader.getInstance().getModContainer("minecraft")
            .orElseThrow().getMetadata().getVersion();

    /**
     * Whether packet references introduced in Minecraft 26.2 may be resolved.
     */
    public static final boolean AT_LEAST_26_2 = matches(">=26.2");
    /**
     * Whether packet references introduced in Minecraft 26.3 may be resolved.
     */
    public static final boolean AT_LEAST_26_3 = matches(">=26.3");
    // Keep this exact snapshot predicate aligned with the Minecraft dependency in fabric.mod.json.
    /**
     * Whether the runtime matches the specifically supported 26.4 snapshot API.
     */
    public static final boolean IS_26_4_SNAPSHOT = matches("26.4-alpha.7");

    /**
     * Prevents construction of the immutable runtime-version flags.
     */
    private MinecraftVersions() {
    }

    /**
     * Evaluates a Fabric version predicate against the running Minecraft version.
     *
     * @param predicate Fabric version expression defining an API boundary
     * @return whether the running version satisfies the expression
     * @throws ExceptionInInitializerError if an internal predicate is malformed
     */
    private static boolean matches(String predicate) {
        try {
            return VersionPredicate.parse(predicate).test(CURRENT);
        } catch (VersionParsingException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
