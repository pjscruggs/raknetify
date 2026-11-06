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

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;

import java.lang.reflect.Method;

// Load this adapter only on the snapshot, where DebugGroup replaces the identifier-based grouping
// API.
/**
 * Bridges the snapshot's DebugGroup-based HUD API without linking it on earlier modern releases.
 */
final class DebugHudLines26_4 {
    /** Snapshot HUD group built once for all Raknetify2 diagnostic lines. */
    private static final Object GROUP;

    /** Cached snapshot addToGroup method used on each HUD update. */
    private static final Method ADD_LINE;

    static {
        try {
            // Cache the reflected API once because debug lines are emitted on every HUD update.
            Class<?> builderClass =
                    Class.forName("net.minecraft.client.gui.components.debug.DebugGroup$Builder");
            Object builder =
                    builderClass.getMethod("titled", String.class).invoke(null, "Raknetify2");
            GROUP = builderClass.getMethod("build").invoke(builder);
            ADD_LINE =
                    DebugScreenDisplayer.class.getMethod(
                            "addToGroup", GROUP.getClass(), String.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /** Prevents construction of the snapshot-only HUD adapter. */
    private DebugHudLines26_4() {}

    /**
     * Adds one line to the cached snapshot HUD group through its runtime-resolved API.
     *
     * @param lines snapshot debug-screen destination
     * @param text complete diagnostic line to append
     * @throws IllegalStateException if the resolved HUD method cannot be invoked
     */
    static void addLine(final DebugScreenDisplayer lines, final String text) {
        try {
            ADD_LINE.invoke(lines, GROUP, text);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Cannot display Raknetify2 debug information", exception);
        }
    }
}
