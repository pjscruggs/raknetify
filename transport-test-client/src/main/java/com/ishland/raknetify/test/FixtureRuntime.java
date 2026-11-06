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

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.nio.file.Path;

/** Supplies Fabric runtime metadata to the shared native packet tests. */
final class FixtureRuntime {

    /** Prevents construction of the fixture's metadata adapter. */
    private FixtureRuntime() {}

    /**
     * Selects the namespace used by the installed Fabric game.
     *
     * @return whether packet and member names are unobfuscated
     */
    static boolean modern() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow()
                .getMetadata()
                .getVersion()
                .getFriendlyString()
                .startsWith("26.");
    }

    /**
     * Checks when a protocol feature became available using Fabric's version semantics.
     *
     * @param version first Minecraft release containing the feature
     * @return whether the installed runtime supports that protocol feature
     * @throws Exception if the fixed test version cannot be parsed
     */
    static boolean atLeast(final String version) throws Exception {
        return VersionPredicate.parse(">=" + version)
                .test(
                        FabricLoader.getInstance()
                                .getModContainer("minecraft")
                                .orElseThrow()
                                .getMetadata()
                                .getVersion());
    }

    /**
     * Locates writable fixture state without touching an installed player's game directory.
     *
     * @return isolated game directory chosen by the test launcher
     */
    static Path gameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }
}
