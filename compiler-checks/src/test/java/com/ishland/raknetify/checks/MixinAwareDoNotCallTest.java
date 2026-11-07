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

package com.ishland.raknetify.checks;

import com.google.errorprone.CompilationTestHelper;

import org.junit.jupiter.api.Test;

/** Keeps real always-throw findings enabled while modeling methods that Mixin actually replaces. */
class MixinAwareDoNotCallTest {

    /** An ordinary always-throw method still produces the original compiler diagnostic. */
    @Test
    void reportsOrdinaryThrowingMethod() {
        compiler()
                .addSourceLines(
                        "Example.java",
                        "class Example {",
                        "  // BUG: Diagnostic contains: DoNotCall",
                        "  public static void unavailable() { throw new"
                                + " UnsupportedOperationException(); }",
                        "}")
                .doTest();
    }

    /** A real invoker declaration has a body supplied by Mixin and remains callable. */
    @Test
    void acceptsInvokerInsideMixin() {
        compiler()
                .addSourceLines(
                        "Example.java",
                        "@org.spongepowered.asm.mixin.Mixin class Example {",
                        "  @org.spongepowered.asm.mixin.gen.Invoker",
                        "  public static void invokeTarget() { throw new AssertionError(); }",
                        "}")
                .doTest();
    }

    /** Applying the invoker marker to a class that is not transformed cannot hide a finding. */
    @Test
    void reportsInvokerOutsideMixin() {
        compiler()
                .addSourceLines(
                        "Example.java",
                        "class Example {",
                        "  @org.spongepowered.asm.mixin.gen.Invoker",
                        "  // BUG: Diagnostic contains: DoNotCall",
                        "  public static void unavailable() { throw new"
                                + " UnsupportedOperationException(); }",
                        "}")
                .doTest();
    }

    /** A regular method inside a Mixin still receives every original always-throw check. */
    @Test
    void reportsOrdinaryMethodInsideMixin() {
        compiler()
                .addSourceLines(
                        "Example.java",
                        "@org.spongepowered.asm.mixin.Mixin class Example {",
                        "  // BUG: Diagnostic contains: DoNotCall",
                        "  public static void unavailable() { throw new"
                                + " UnsupportedOperationException(); }",
                        "}")
                .doTest();
    }

    /**
     * Supplies only the annotation identities consumed by the checker, without running Mixin.
     *
     * @return compiler fixture configured with the actual diagnostic implementation
     */
    private CompilationTestHelper compiler() {
        return CompilationTestHelper.newInstance(MixinAwareDoNotCall.class, getClass())
                .addSourceLines(
                        "org/spongepowered/asm/mixin/Mixin.java",
                        "package org.spongepowered.asm.mixin;",
                        "public @interface Mixin {}")
                .addSourceLines(
                        "org/spongepowered/asm/mixin/gen/Invoker.java",
                        "package org.spongepowered.asm.mixin.gen;",
                        "public @interface Invoker {}");
    }
}
