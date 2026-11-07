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

import com.ishland.raknetify.fabric.mixin.access.IDebugHudEntries;

import net.minecraft.client.gui.hud.debug.DebugHudEntry;
import net.minecraft.client.gui.hud.debug.DebugHudLines;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers a transport diagnostic entry with Minecraft's configurable debug HUD. */
public class DebugHudUtil1_21_9 {

    /** Atomic one-time guard preventing duplicate debug-entry registration. */
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * Registers the Raknetify debug entry once when this Minecraft version supports its HUD API.
     */
    public static void init() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }

        Identifier identifier = Identifier.of("raknetify", "raknetify_debug_info");
        IDebugHudEntries.invokeRegister(identifier, new TransportEntry(identifier));
    }

    /** Displays transport diagnostics independently of world and chunk availability. */
    private static final class TransportEntry implements DebugHudEntry {
        /** Registry section receiving this entry's transport lines. */
        private final Identifier identifier;

        /**
         * Associates transport diagnostics with their registered HUD section.
         *
         * @param identifier registry identifier used for grouped lines
         */
        private TransportEntry(final Identifier identifier) {
            this.identifier = identifier;
        }

        /**
         * Appends transport diagnostics without requiring a loaded world or chunk.
         *
         * @param lines debug-screen line collector
         * @param world current client world, unused by transport diagnostics and possibly null
         * @param clientChunk client-side chunk, unused by transport diagnostics and possibly null
         * @param chunk server-side chunk, unused by transport diagnostics and possibly null
         */
        @Override
        public void render(
                final DebugHudLines lines,
                @Nullable final World world,
                @Nullable final WorldChunk clientChunk,
                @Nullable final WorldChunk chunk) {
            DebugHudUtil.getDebugString(s -> lines.addLineToSection(identifier, s));
        }

        /**
         * Allows network diagnostics even when Minecraft suppresses detailed world debug
         * information.
         *
         * @param reducedDebugInfo whether the server requests reduced debug information
         * @return true because these lines expose only connection diagnostics
         */
        @Override
        public boolean canShow(final boolean reducedDebugInfo) {
            return true;
        }
    }
}
