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

package com.ishland.raknetify.fabric.common.client;

import com.ishland.raknetify.fabric.common.util.MinecraftVersions;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Replaces a server address host and port while retaining snapshot-specific query properties.
 */
public final class ServerAddressUtil {

    /**
     * Prevents construction of the server-address compatibility helper.
     */
    private ServerAddressUtil() {
    }

    /**
     * Copies the address through the snapshot API when it carries additional connection properties.
     * Earlier versions use the ordinary host-and-port constructor.
     *
     * @param original parsed address whose additional properties must survive prefix removal
     * @param host replacement hostname without the transport prefix
     * @param port replacement Minecraft port
     * @return equivalent address with the requested host and port
     * @throws IllegalStateException if the snapshot copy API cannot be invoked
     */
    public static ServerAddress withAddress(ServerAddress original, String host, int port) {
        if (!MinecraftVersions.IS_26_4_SNAPSHOT) return new ServerAddress(host, port);
        try {
            // Resolve the snapshot copy method at runtime to preserve query properties while compiling against 26.3.
            return (ServerAddress) ServerAddress.class.getMethod("withHostAndPort", String.class, int.class)
                    .invoke(original, host, port);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot preserve server address properties", exception);
        }
    }
}
