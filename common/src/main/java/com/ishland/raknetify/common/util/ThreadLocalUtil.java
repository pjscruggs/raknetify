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

/**
 * Carries transport selection through synchronous platform connection and bootstrap hooks. Callers
 * must restore flags after initialization so later connections on the same thread do not inherit a
 * previous connection's transport choice.
 */
public class ThreadLocalUtil {

    /** Prevents construction of the thread-local bootstrap-state utility. */
    private ThreadLocalUtil() {}

    /**
     * Whether the current thread is constructing a RakNet connection rather than a TCP connection.
     */
    private static final ThreadLocal<Boolean> initializingRaknet =
            ThreadLocal.withInitial(() -> false);

    /** Whether the current bootstrap selected the opt-in large MTU. */
    private static final ThreadLocal<Boolean> initializingRaknetLargeMTU =
            ThreadLocal.withInitial(() -> false);

    /**
     * Sets the transport choice observed by nested bootstrap hooks on this thread.
     *
     * @param value true while initializing a RakNet connection, false when restoring normal
     *     behavior
     */
    public static void setInitializingRaknet(final boolean value) {
        initializingRaknet.set(value);
    }

    /**
     * Sets the MTU choice observed by nested bootstrap hooks on this thread.
     *
     * @param value true while initializing a large-MTU connection, false when restoring the default
     */
    public static void setInitializingRaknetLargeMTU(final boolean value) {
        initializingRaknetLargeMTU.set(value);
    }

    /**
     * Reads this thread's transport selection for the current bootstrap.
     *
     * @return true when nested connection hooks should create RakNet channels
     */
    public static boolean isInitializingRaknet() {
        return initializingRaknet.get();
    }

    /**
     * Reads this thread's MTU selection for the current bootstrap.
     *
     * @return true when the large-MTU transport prefix was selected
     */
    public static boolean isInitializingRaknetLargeMTU() {
        return initializingRaknetLargeMTU.get();
    }
}
