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

package com.ishland.raknetify.fabric.common.util;

import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.spongepowered.asm.util.asm.ASM;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects class references from generic field signatures to link packet types with implementation
 * classes.
 */
public class FieldSignatureParser extends SignatureVisitor {

    /** Class types encountered by ASM in signature traversal order. */
    private final List<Type> results = new ArrayList<>();

    /** Creates an ASM visitor using the API level supported by the installed Mixin environment. */
    private FieldSignatureParser() {
        super(ASM.API_VERSION);
    }

    /**
     * Extracts referenced class types from a generic field signature without loading those classes.
     *
     * @param signature JVM field signature, or null when the field has no generic signature
     * @return encountered class types, or an empty list for a missing signature
     */
    public static List<Type> parse(final String signature) {
        if (signature == null || signature.isEmpty()) {
            return List.of();
        }
        FieldSignatureParser parser = new FieldSignatureParser();
        new SignatureReader(signature).acceptType(parser);
        return parser.results;
    }

    /**
     * Records a class reference encountered while ASM traverses the field signature.
     *
     * @param name JVM internal class name using slash separators
     */
    @Override
    public void visitClassType(final String name) {
        results.add(Type.getObjectType(name));
    }
}
