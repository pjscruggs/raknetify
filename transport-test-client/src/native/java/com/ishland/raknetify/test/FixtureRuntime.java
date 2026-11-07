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

import com.google.common.base.Splitter;
import com.ishland.raknetify.minecraft.ModPlatform;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads the native loader's game metadata without introducing a Fabric dependency into its tests.
 */
final class FixtureRuntime {

    /** Prevents construction of the native fixture's metadata adapter. */
    private FixtureRuntime() {}

    /**
     * Selects the namespace declared by the installed native loader's game metadata.
     *
     * @return whether packet and member names are unobfuscated
     */
    static boolean modern() {
        return ModPlatform.get().minecraftVersion().startsWith("26.");
    }

    /**
     * Compares numeric release components before any snapshot suffix.
     *
     * @param version first Minecraft release containing the protocol feature
     * @return whether the runtime's numeric release is at least the requested release
     */
    static boolean atLeast(final String version) {
        final String release =
                Splitter.onPattern("[-+]")
                        .splitToList(ModPlatform.get().minecraftVersion())
                        .getFirst();
        final var current = Splitter.on('.').splitToList(release);
        final var required = Splitter.on('.').splitToList(version);
        for (int index = 0; index < Math.max(current.size(), required.size()); index++) {
            int actual = index < current.size() ? Integer.parseInt(current.get(index)) : 0;
            int minimum = index < required.size() ? Integer.parseInt(required.get(index)) : 0;
            if (actual != minimum) {
                return actual > minimum;
            }
        }
        return true;
    }

    /**
     * Derives fixture storage from the loader's isolated configuration location.
     *
     * @return game directory surrounding the configuration directory
     * @throws NullPointerException if the loader supplies a filesystem root as its config directory
     */
    static Path gameDirectory() {
        return Objects.requireNonNull(
                ModPlatform.get().configDirectory().toAbsolutePath().getParent(),
                "Fixture configuration directory must have a parent");
    }
}
