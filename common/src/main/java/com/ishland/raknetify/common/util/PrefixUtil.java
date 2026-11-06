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

import com.ishland.raknetify.common.Constants;

/**
 * Separates a user-selected RakNet transport prefix from the address sent to normal host
 * resolution.
 */
public class PrefixUtil {

    /**
     * Recognizes a normal or large-MTU RakNet prefix at the beginning of a server address.
     *
     * @param address user-entered server address before DNS or server-list processing
     * @return transport selection and the address with any recognized prefix removed
     */
    public static Info getInfo(final String address) {
        if (address.startsWith(Constants.RAKNET_PREFIX)) {
            return new Info(true, address.substring(Constants.RAKNET_PREFIX.length()), false);
        } else if (address.startsWith(Constants.RAKNET_LARGE_MTU_PREFIX)) {
            return new Info(
                    true, address.substring(Constants.RAKNET_LARGE_MTU_PREFIX.length()), true);
        } else {
            return new Info(false, address, false);
        }
    }

    /**
     * Carries transport selection alongside the address used for ordinary resolution.
     *
     * @param useRakNet whether the original address explicitly selected RakNet
     * @param stripped address after removing a recognized transport prefix
     * @param largeMTU whether the large-MTU prefix was selected
     */
    public record Info(boolean useRakNet, String stripped, boolean largeMTU) {}
}
