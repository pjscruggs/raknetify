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

import com.ishland.raknetify.fabric.MinecraftRuntime;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** Initializes the shared transport after NeoForge has established the game registries. */
@Mod("raknetify")
public final class RaknetifyNeoForge {

    /**
     * Registers startup work on the mod lifecycle bus without eagerly loading game codecs.
     *
     * @param bus lifecycle bus supplied by NeoForge for this mod
     */
    public RaknetifyNeoForge(final IEventBus bus) {
        bus.addListener(this::setup);
    }

    /**
     * Runs routing initialization on the main setup thread after parallel mod construction
     * finishes.
     *
     * @param event common setup event with the main-thread work queue
     */
    private void setup(final FMLCommonSetupEvent event) {
        // Observe the failure without replacing the failed future retained by the loader.
        event.enqueueWork(MinecraftRuntime::initialize)
                .exceptionally(failure -> {
                    System.getLogger("Raknetify2")
                            .log(
                                    System.Logger.Level.ERROR,
                                    "Raknetify2 could not initialize Minecraft transport support",
                                    failure);
                    return null;
                });
    }
}
