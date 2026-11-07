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

package com.ishland.raknetify.common;

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.common.util.ReleaseFamily;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Guards version-specific API selection against malformed loader metadata and numeric overflow. */
class ReleaseFamilyTest {

    /** Stable, patch, snapshot and build identifiers preserve their leading numeric family. */
    @Test
    void recognizesSupportedVersionForms() {
        for (String version :
                List.of("26.4", "26.4.1", "26.4-alpha.7", "26.4-snapshot-7", "26.4+build.1")) {
            assertEquals(new ReleaseFamily(26, 4), ReleaseFamily.parse(version));
        }
        assertEquals(new ReleaseFamily(1, 21), ReleaseFamily.parse("1.21.11"));
    }

    /**
     * Invalid names fail deliberately rather than indexing missing fields or overflowing an int.
     */
    @Test
    void rejectsMissingNonnumericAndOversizedComponents() {
        for (String version :
                List.of(
                        "",
                        "26",
                        "26.",
                        ".4",
                        "26w40a",
                        "26.x",
                        "-26.4",
                        "26.4x",
                        "2147483648.4",
                        "26.2147483648",
                        "26.4\n")) {
            assertThrows(
                    IllegalArgumentException.class, () -> ReleaseFamily.parse(version), version);
        }
    }

    /** A new major release outranks any minor release in the previous family. */
    @Test
    void comparesBothSidesOfApiBoundaries() {
        assertFalse(ReleaseFamily.parse("26.1").atLeast(26, 2));
        assertTrue(ReleaseFamily.parse("26.2").atLeast(26, 2));
        assertTrue(ReleaseFamily.parse("26.3").atLeast(26, 2));
        assertTrue(ReleaseFamily.parse("27.1").atLeast(26, 4));
        assertFalse(ReleaseFamily.parse("25.9").atLeast(26, 1));
    }
}
