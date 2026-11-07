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

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.DoNotCallSuggester;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;

/**
 * Applies the ordinary always-throw diagnostic while recognizing Mixin-generated invoker bodies.
 */
@BugPattern(
        summary = "An ordinary method that always throws should prohibit callers with @DoNotCall",
        severity = BugPattern.SeverityLevel.WARNING,
        linkType = BugPattern.LinkType.NONE)
public final class MixinAwareDoNotCall extends DoNotCallSuggester {

    /** Serialization identity inherited from the compiler's diagnostic extension contract. */
    private static final long serialVersionUID = 1L;

    /**
     * Distinguishes a transformed invoker from an ordinary method that cannot complete. Only an
     * invoker declared inside a Mixin has its implementation replaced before runtime use.
     *
     * @param tree method currently being analyzed
     * @param state compiler symbols and the enclosing source path
     * @return the original diagnostic, or no match for a framework-generated implementation
     */
    @Override
    public Description matchMethod(final MethodTree tree, final VisitorState state) {
        final ClassTree enclosing = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
        if (enclosing != null
                && ASTHelpers.hasAnnotation(enclosing, "org.spongepowered.asm.mixin.Mixin", state)
                && ASTHelpers.hasAnnotation(
                        tree, "org.spongepowered.asm.mixin.gen.Invoker", state)) {
            return Description.NO_MATCH;
        }
        return super.matchMethod(tree, state);
    }
}
