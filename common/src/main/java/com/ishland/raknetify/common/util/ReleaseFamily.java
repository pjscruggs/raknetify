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

package com.ishland.raknetify.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numeric release family used to guard access to version-specific Minecraft APIs.
 *
 * @param major release year or major version number
 * @param minor release within that year or major version
 */
public record ReleaseFamily(int major, int minor) {

    /** Nine decimal digits fit an int, and a suffix must start at a version separator. */
    private static final Pattern FAMILY =
            Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})(?:[.\\-+].*)?");

    /**
     * Parses the first two numeric components without assuming a loader supplied a release name.
     * Patch, prerelease and build suffixes do not affect the API family. Unrecognized names fail
     * explicitly instead of selecting an arbitrary compatibility path.
     *
     * @param version loader-provided Minecraft version
     * @return validated numeric API family
     * @throws IllegalArgumentException if the name lacks two bounded decimal components
     */
    public static ReleaseFamily parse(final String version) {
        final Matcher match = FAMILY.matcher(version);
        if (!match.matches()) {
            throw new IllegalArgumentException("Unrecognized Minecraft release family: " + version);
        }
        return new ReleaseFamily(
                Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)));
    }

    /**
     * Tests whether this family reaches the specified API introduction boundary.
     *
     * @param requiredMajor earliest release year or major version
     * @param requiredMinor earliest minor release within that family
     * @return whether this release is at or after the boundary
     */
    public boolean atLeast(final int requiredMajor, final int requiredMinor) {
        return major > requiredMajor || (major == requiredMajor && minor >= requiredMinor);
    }
}
