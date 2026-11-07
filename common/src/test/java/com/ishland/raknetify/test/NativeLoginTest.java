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

import com.ishland.raknetify.common.connection.RaknetEncryptionProbe;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Runs a bounded loopback login probe with native packet classes before graphics initialization.
 */
public final class NativeLoginTest implements PreLaunchEntrypoint {
    /**
     * Requires an explicit fixture property and terminates the disposable process after the probe.
     * No game window, saved account, or normal multiplayer connection is opened.
     */
    @Override
    public void onPreLaunch() {
        String arguments = System.getProperty("raknetify.test.encryption");
        if (arguments == null) {
            return;
        }
        try {
            run(arguments);
            System.out.println("PASS native client login packet serialization");
            System.exit(0);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            failure.printStackTrace();
            System.exit(1);
        } catch (Exception failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Keeps consecutive rejection controls in one native process so startup cannot outlast the
     * server's reconnect throttle. The nonce control follows the throttle's normal expiry.
     *
     * @param arguments loopback port, native protocol, and bounded challenge size
     * @throws Exception if a probe fails or the inter-probe wait is interrupted
     * @throws IllegalArgumentException if the fixture property omits or adds connection fields
     */
    private static void run(final String arguments) throws Exception {
        String[] connection = arguments.split(",", -1);
        if (connection.length != 3) {
            throw new IllegalArgumentException("Expected port, protocol, and payload size");
        }
        for (String mode :
                System.getProperty("raknetify.test.encryptionModes", "echo").split(",", -1)) {
            RaknetEncryptionProbe.main(
                    new String[] {connection[0], connection[1], connection[2], mode});
            if ("rejected-throttle".equals(mode)) {
                Thread.sleep(4100);
            }
        }
    }
}
