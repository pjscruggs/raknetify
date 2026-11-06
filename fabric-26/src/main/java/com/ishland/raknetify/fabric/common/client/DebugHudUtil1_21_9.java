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

package com.ishland.raknetify.fabric.common.client;

import com.ishland.raknetify.fabric.common.util.MinecraftVersions;
import com.ishland.raknetify.fabric.mixin.access.IDebugHudEntries;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers a transport diagnostic entry with Minecraft's configurable debug HUD. */
public class DebugHudUtil1_21_9 {

    /** Atomic one-time guard preventing duplicate debug-entry registration. */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /** Registry identifier for the Raknetify debug-screen entry and pre-snapshot line group. */
    public static final Identifier ID =
            Identifier.fromNamespaceAndPath("raknetify", "raknetify_debug_info");

    /**
     * Registers the Raknetify debug entry once when this Minecraft version supports its HUD API.
     */
    public static void init() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        IDebugHudEntries.invokeRegister(ID, createEntry());
    }

    /**
     * Creates a HUD entry that chooses the line-grouping API appropriate to the runtime.
     *
     * @return transport diagnostic entry suitable for Minecraft's debug registry
     */
    public static DebugScreenEntry createEntry() {
        return new DebugScreenEntry() {
            /**
             * Appends transport diagnostics without requiring a loaded world or chunk.
             *
             * @param lines debug-screen line collector
             * @param world current client world, unused by transport diagnostics and possibly null
             * @param clientChunk client-side chunk, unused by transport diagnostics and possibly
             *     null
             * @param chunk server-side chunk, unused by transport diagnostics and possibly null
             */
            @Override
            public void display(
                    final DebugScreenDisplayer lines,
                    @Nullable final Level world,
                    @Nullable final LevelChunk clientChunk,
                    @Nullable final LevelChunk chunk) {
                if (MinecraftVersions.IS_26_4_SNAPSHOT) {
                    DebugHudUtil.getDebugString(s -> DebugHudLines26_4.addLine(lines, s));
                } else {
                    DebugHudUtil.getDebugString(s -> lines.addToGroup(ID, s));
                }
            }

            /**
             * Allows network diagnostics even when Minecraft suppresses detailed world debug
             * information.
             *
             * @param reducedDebugInfo whether the server requests reduced debug information
             * @return true because these lines expose only connection diagnostics
             */
            @Override
            public boolean isAllowed(final boolean reducedDebugInfo) {
                return true;
            }
        };
    }
}
