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
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks actual transformed Minecraft bytecode so literal compatibility selectors cannot silently stop injecting. */
final class LegacyMixinContractTest {

    /** Version predicates select API transitions independently of the mixin plugin's implementation. */
    private static final Version VERSION = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
            .getMetadata().getVersion();

    /** Prevents construction of this isolated bytecode witness. */
    private LegacyMixinContractTest() {
    }

    /**
     * Loads each affected native target and requires executable calls to its transport hooks.
     * Merely merging a handler method does not pass. The negative control removes call sites from
     * an in-memory copy and must be rejected by the same validator.
     * @throws Exception if transformation fails, an expected call is absent or the control is accepted
     */
    static void verify() throws Exception {
        if (VERSION.getFriendlyString().startsWith("26.")) return;
        requireCalls("class_2535", "redirectGroup", "redirectChannel");
        requireCalls("class_644", "modifyRaknetAddress", "redirectConnect");
        requireCalls("class_644$1", "noPingRaknet");
        requireCalls("class_3248", "stopCompressionIfStreamingCompressionExists");
        verifyVersionedTargets();
        verifyQueueWhenPresent();
        System.out.println("PASS native legacy mixin call sites and rejected missing-hook control");
    }

    /**
     * Checks hooks whose owning classes or methods changed during the supported legacy range.
     * @throws Exception if an applicable target, accessor field or transformed hook is missing
     */
    private static void verifyVersionedTargets() throws Exception {
        if (matches("<=1.21.8")) requireCalls("class_340", "getLeftText");
        if (matches("<=1.20.1")) {
            requireCalls("class_3324", "onJoin", "postJoin");
            requireCalls("class_3244", "stopTimeoutPlayersOnKeepAlive", "redirectPingStoring");
        }
        if (matches(">=1.20.5")) requireCalls("class_2535", "raknetify$selectTransferDestination");
        if (matches("<=1.20.4")) verifyPacketAccessor();
    }

    /**
     * Requires the generated packet-map accessor to read the real registry field.
     * @throws Exception if the old registry cannot load or its export cannot be read
     * @throws AssertionError if the accessor is detached from its native field
     */
    private static void verifyPacketAccessor() throws Exception {
        ClassNode target = transformed("class_2539$class_4532");
        for (MethodNode method : target.methods) {
            if (!method.name.equals("getPacketIds")) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && field.name.equals("field_20596")) return;
            }
        }
        throw new AssertionError("Legacy packet registry accessor does not read field_20596");
    }

    /**
     * Checks the subscription cleanup on runtimes that still contain the removed sample-tracker class.
     * @throws Exception if a present tracker cannot transform or its cleanup hook is unreferenced
     */
    private static void verifyQueueWhenPresent() throws Exception {
        try {
            Class.forName("net.minecraft.class_9188", false, LegacyMixinContractTest.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return;
        }
        requireCalls("class_9188", "cleanQueue");
    }

    /**
     * Evaluates a Fabric version predicate without depending on the production mixin-selection helper.
     * @param predicate supported API interval to check
     * @return whether this native runtime belongs to the interval
     * @throws Exception if the fixture contains a malformed version predicate
     */
    private static boolean matches(String predicate) throws Exception {
        return VersionPredicate.parse(predicate).test(VERSION);
    }

    /**
     * Loads a class without initializing game state and reads the bytecode exported after Mixin applies.
     * @param name intermediary class name relative to net.minecraft
     * @return transformed class, including injected method bodies and their actual call sites
     * @throws Exception if the runtime class or its fresh Mixin export is unavailable
     */
    private static ClassNode transformed(String name) throws Exception {
        Class.forName("net.minecraft." + name, false, LegacyMixinContractTest.class.getClassLoader());
        Path path = Path.of(".mixin.out", "class", "net", "minecraft", name + ".class");
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(path)).accept(node, 0);
        return node;
    }

    /**
     * Checks every expected hook and proves that a disconnected handler fails the same assertion.
     * @param target intermediary class whose transformed method bodies must contain the hooks
     * @param hooks stable source handler names retained inside Mixin's generated unique names
     * @throws Exception if loading or reading the transformed target fails
     */
    private static void requireCalls(String target, String... hooks) throws Exception {
        ClassNode node = transformed(target);
        for (String hook : hooks) {
            requireCall(node, hook);
            ClassNode broken = new ClassNode();
            node.accept(broken);
            removeCalls(broken, hook);
            requireRejectedControl(broken, hook);
        }
    }

    /**
     * Finds calls to a merged hook, excluding self-recursion and references to unrelated owners.
     * @param node actual transformed target or a deliberately broken control copy
     * @param hook source handler name identifying the injected method
     * @return executable call sites that invoke a hook merged into this target
     */
    private static List<MethodInsnNode> calls(ClassNode node, String hook) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.contains(hook)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)
                        && call.name.contains(hook)) result.add(call);
            }
        }
        return result;
    }

    /**
     * Rejects a missing call even when the injected handler declaration exists in the target class.
     * @param node transformed target to inspect
     * @param hook required transport handler
     * @throws AssertionError if the transformed target never invokes the required hook
     */
    private static void requireCall(ClassNode node, String hook) {
        if (calls(node, hook).isEmpty()) throw new AssertionError(node.name + " never calls " + hook);
    }

    /**
     * Disconnects a hook only in a bytecode-analysis copy, leaving loaded Minecraft classes untouched.
     * @param node private control copy whose call sites will be removed
     * @param hook handler whose declaration remains while its callers are removed
     */
    private static void removeCalls(ClassNode node, String hook) {
        List<MethodInsnNode> selected = calls(node, hook);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (selected.contains(instruction)) method.instructions.remove(instruction);
            }
        }
    }

    /**
     * Proves the validator detects a silently skipped injection instead of only finding its declaration.
     * @param node control with the handler retained but its call sites removed
     * @param hook disconnected handler that must fail validation
     * @throws AssertionError if the negative control is incorrectly accepted
     */
    private static void requireRejectedControl(ClassNode node, String hook) {
        try {
            requireCall(node, hook);
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("Missing-hook control was accepted for " + hook);
    }
}
