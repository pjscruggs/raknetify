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

package com.ishland.raknetify.fabric.mixin;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.ishland.raknetify.fabric.common.util.MinecraftVersions;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Selects Mixin hooks whose target APIs exist in the running Minecraft version.
 */
public class RaknetifyFabricMixinPlugin implements IMixinConfigPlugin {

    /**
     * Initializes MixinExtras and relaxes the transport's packet-loss disconnect threshold.
     *
     * @param mixinPackage configured mixin package, unused because initialization is shared
     */
    @Override
    public void onLoad(String mixinPackage) {
        System.setProperty("raknetserver.maxPacketLoss", String.valueOf(Integer.MAX_VALUE));
        MixinExtrasBootstrap.init();
    }

    /**
     * Uses the reference mapper declared in the Mixin configuration without overriding it.
     *
     * @return null to retain the configured reference mapper
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * Checks runtime API boundaries before Mixin resolves version-specific target classes.
     *
     * @param targetClassName target binary name, unused because selection is keyed by mixin name
     * @param mixinClassName fully qualified candidate mixin class
     * @return whether the candidate hook is supported by this runtime
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return switch (mixinClassName.substring("com.ishland.raknetify.fabric.mixin.".length())) {
            case "compat.packetfixer.MixinSplitterHandler" -> FabricLoader.getInstance().isModLoaded("packetfixer");
            case "access.IDebugHudEntries", "compat.krypton.MixinServerLoginNetworkHandler" -> !MinecraftVersions.IS_26_4_SNAPSHOT;
            case "client.hud.MixinDebugHudEntries26_4", "common.encryption.MixinClientConnection26_4",
                    "compat.krypton.MixinServerLoginNetworkHandler26_4" -> MinecraftVersions.IS_26_4_SNAPSHOT;
            default -> true;
        };
    }

    /**
     * Leaves this configuration's targets and other configurations' targets unchanged.
     *
     * @param myTargets targets selected by this configuration
     * @param otherTargets targets selected by other loaded configurations
     */
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    /**
     * Uses the mixins listed in the configuration without adding dynamic entries.
     *
     * @return null because no additional mixin class names are supplied
     */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Leaves target bytecode unchanged before Mixin applies the declared injections.
     *
     * @param targetClassName target binary name
     * @param targetClass target bytecode about to be transformed
     * @param mixinClassName mixin selected for application
     * @param mixinInfo metadata describing the selected mixin
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }

    /**
     * Leaves the transformed target unchanged after Mixin applies the declared injections.
     *
     * @param targetClassName target binary name
     * @param targetClass bytecode after the selected mixin was applied
     * @param mixinClassName mixin that has just been applied
     * @param mixinInfo metadata describing the applied mixin
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
