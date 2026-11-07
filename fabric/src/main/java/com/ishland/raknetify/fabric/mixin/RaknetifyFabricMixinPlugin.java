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

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Selects Mixin hooks whose target APIs exist in the running Minecraft version. */
public class RaknetifyFabricMixinPlugin implements IMixinConfigPlugin {

    /**
     * Whether Minecraft is newer than 1.20.1, selecting common packet listeners and the revised
     * player-join callback.
     */
    public static final boolean AFTER_1_20_1;

    /** Whether Minecraft is newer than 1.20.2, selecting post-1.20.2 compatibility paths. */
    public static final boolean AFTER_1_20_2;

    /**
     * Whether Minecraft is newer than 1.20.4, selecting codec-based packet registration and the
     * revised server-list pinger.
     */
    public static final boolean AFTER_1_20_4;

    /** Whether Minecraft is newer than 1.20.5, selecting later codec-transition hooks. */
    public static final boolean AFTER_1_20_5;

    /**
     * Whether Minecraft is newer than 1.21.4, selecting context-aware protocol factory bindings.
     */
    public static final boolean AFTER_1_21_4;

    /**
     * Whether Minecraft is newer than 1.21.5, selecting hunger-state copying without the older NBT
     * serialization methods.
     */
    public static final boolean AFTER_1_21_5;

    /**
     * Whether Minecraft is newer than 1.21.8, selecting the configurable debug HUD and newer entity
     * world accessor.
     */
    public static final boolean AFTER_1_21_8;

    /**
     * Whether Minecraft is newer than 1.21.10, selecting the event-loop backend argument used by
     * connection factories.
     */
    public static final boolean AFTER_1_21_10;

    static {
        try {
            AFTER_1_20_1 =
                    VersionPredicate.parse(">1.20.1")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_20_2 =
                    VersionPredicate.parse(">1.20.2")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_20_4 =
                    VersionPredicate.parse(">1.20.4")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_20_5 =
                    VersionPredicate.parse(">1.20.5")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_21_4 =
                    VersionPredicate.parse(">1.21.4")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_21_5 =
                    VersionPredicate.parse(">1.21.5")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_21_8 =
                    VersionPredicate.parse(">1.21.8")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
            AFTER_1_21_10 =
                    VersionPredicate.parse(">1.21.10")
                            .test(
                                    FabricLoader.getInstance()
                                            .getModContainer("minecraft")
                                            .get()
                                            .getMetadata()
                                            .getVersion());
        } catch (VersionParsingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Initializes MixinExtras and relaxes the transport's packet-loss disconnect threshold.
     *
     * @param mixinPackage configured mixin package, unused because initialization is shared
     */
    @Override
    public void onLoad(final String mixinPackage) {
        System.setProperty("raknetserver.maxPacketLoss", String.valueOf(Integer.MAX_VALUE));
        MixinExtrasBootstrap.init();
    }

    /**
     * Uses the reference mapper declared in the Mixin configuration without overriding it.
     *
     * @return null to retain the configured reference mapper
     */
    @Override
    public @Nullable String getRefMapperConfig() {
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
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.contains(".MixinTransfer") && !AFTER_1_20_4) {
            return false;
        }
        if (mixinClassName.startsWith("com.ishland.raknetify.fabric.mixin.client.")) {
            return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                    && isClientMixinSupported(mixinClassName);
        }
        return isSharedMixinSupported(mixinClassName);
    }

    /**
     * Groups server and connection hooks by the native API transition they require.
     *
     * @param mixinClassName fully qualified candidate mixin name
     * @return whether the active Minecraft version contains that hook's target
     */
    private static boolean isSharedMixinSupported(final String mixinClassName) {
        return switch (mixinClassName.substring("com.ishland.raknetify.fabric.mixin.".length())) {
            case "compat.packetfixer.MixinSplitterHandler" ->
                    AFTER_1_20_1 && FabricLoader.getInstance().isModLoaded("packetfixer");
            case "server.MixinServerPlayNetworkHandler1_20_1", "server.MixinPlayerManager1_20_1" ->
                    !AFTER_1_20_1;
            case "server.MixinServerCommonNetworkHandler",
                    "server.MixinPlayerManager1_20_2",
                    "common.MixinClientConnection1_20_2" ->
                    AFTER_1_20_1;
            case "access.INetworkState1_20_4", "access.INetworkStateInternalPacketHandler" ->
                    !AFTER_1_20_4;
            default -> true;
        };
    }

    /**
     * Selects the pinger and debug HUD hooks whose target methods exist in the running client.
     *
     * @param mixinClassName fully qualified client mixin name from the configuration
     * @return whether this Minecraft version has the target API required by the mixin
     */
    private static boolean isClientMixinSupported(final String mixinClassName) {
        if ("com.ishland.raknetify.fabric.mixin.client.MixinMultiplayerServerListPinger1_20_2"
                .equals(mixinClassName)) {
            return AFTER_1_20_1 && !AFTER_1_20_4;
        }
        if ("com.ishland.raknetify.fabric.mixin.client.MixinMultiplayerServerListPinger1_20_5"
                .equals(mixinClassName)) {
            return AFTER_1_20_4 && !AFTER_1_21_10;
        }
        if ("com.ishland.raknetify.fabric.mixin.client.MixinMultiplayerServerListPinger1_21_11"
                .equals(mixinClassName)) {
            return AFTER_1_21_10;
        }
        if ("com.ishland.raknetify.fabric.mixin.client.hud.MixinDebugHud1_21_8"
                .equals(mixinClassName)) {
            return !AFTER_1_21_8;
        }
        return true;
    }

    /**
     * Leaves this configuration's targets and other configurations' targets unchanged.
     *
     * @param myTargets targets selected by this configuration
     * @param otherTargets targets selected by other loaded configurations
     */
    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {}

    /**
     * Uses the mixins listed in the configuration without adding dynamic entries.
     *
     * @return null because no additional mixin class names are supplied
     */
    @Override
    public @Nullable List<String> getMixins() {
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
    public void preApply(
            final String targetClassName,
            final ClassNode targetClass,
            final String mixinClassName,
            final IMixinInfo mixinInfo) {}

    /**
     * Leaves the transformed target unchanged after Mixin applies the declared injections.
     *
     * @param targetClassName target binary name
     * @param targetClass bytecode after the selected mixin was applied
     * @param mixinClassName mixin that has just been applied
     * @param mixinInfo metadata describing the applied mixin
     */
    @Override
    public void postApply(
            final String targetClassName,
            final ClassNode targetClass,
            final String mixinClassName,
            final IMixinInfo mixinInfo) {}
}
