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

package com.ishland.raknetify.test.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Selects one isolated login witness so encryption and transfer fixtures cannot intercept each
 * other's phase.
 */
public final class FixtureMixinPlugin implements IMixinConfigPlugin {

    /**
     * Requires no startup work beyond the runner's explicit system property.
     *
     * @param mixinPackage configured fixture mixin package, unused
     */
    @Override
    public void onLoad(final String mixinPackage) {}

    /**
     * Retains the configuration's generated reference map for native names.
     *
     * @return null to keep the configured reference map
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * Enables only the requested fixture and leaves transfer hooks absent from encryption runs on
     * older versions.
     *
     * @param targetClassName native target, unused because the fixture name determines its role
     * @param mixinClassName exact candidate fixture name
     * @return whether this run selected the candidate's login phase
     */
    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return mixinClassName.endsWith(".TransferLoginMixin")
                == Boolean.getBoolean("raknetify.test.transfers");
    }

    /**
     * Leaves target ownership to the two configured fixtures.
     *
     * @param myTargets fixture targets, unchanged
     * @param otherTargets other configurations' targets, unchanged
     */
    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {}

    /**
     * Adds no dynamic fixture classes.
     *
     * @return null to use the JSON mixin list
     */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Leaves bytecode changes to the selected fixture injections.
     *
     * @param targetClassName native class name
     * @param targetClass native class node, unchanged
     * @param mixinClassName selected fixture name
     * @param mixinInfo mixin metadata, unchanged
     */
    @Override
    public void preApply(
            final String targetClassName,
            final ClassNode targetClass,
            final String mixinClassName,
            final IMixinInfo mixinInfo) {}

    /**
     * Performs no additional mutation after the fixture is applied.
     *
     * @param targetClassName native class name
     * @param targetClass transformed class node, unchanged
     * @param mixinClassName selected fixture name
     * @param mixinInfo mixin metadata, unchanged
     */
    @Override
    public void postApply(
            final String targetClassName,
            final ClassNode targetClass,
            final String mixinClassName,
            final IMixinInfo mixinInfo) {}
}
