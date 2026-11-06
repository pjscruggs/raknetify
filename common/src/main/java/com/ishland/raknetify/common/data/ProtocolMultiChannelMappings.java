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

package com.ishland.raknetify.common.data;

import com.google.gson.Gson;

import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Stores packet-ID routing tables by Minecraft protocol number for proxy-side classification. */
public class ProtocolMultiChannelMappings {

    /**
     * Bundled routing tables loaded once from raknetify-channel-mappings.json, or empty after an
     * I/O failure.
     */
    public static final ProtocolMultiChannelMappings INSTANCE;

    static {
        final InputStream resource =
                ProtocolMultiChannelMappings.class
                        .getClassLoader()
                        .getResourceAsStream("raknetify-channel-mappings.json");
        if (resource == null) {
            System.err.println("Raknetify: Failed to load raknetify channel mappings");
            INSTANCE = new ProtocolMultiChannelMappings();
        } else {
            ProtocolMultiChannelMappings read = new ProtocolMultiChannelMappings();
            try (resource;
                    var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
                final Gson gson = new Gson();
                read = gson.fromJson(reader, ProtocolMultiChannelMappings.class);
            } catch (IOException e) {
                System.err.println("Raknetify: Failed to load raknetify channel mappings");
                e.printStackTrace();
            }
            INSTANCE = read;
        }
    }

    /**
     * Forces class initialization and loading of the bundled routing tables before connections
     * arrive.
     */
    public static void init() {}

    /** Minecraft protocol numbers mapped to separate clientbound and serverbound routing tables. */
    public Int2ObjectArrayMap<VersionMapping> mappings = new Int2ObjectArrayMap<>();

    /** Packet-ID routing tables for both directions of one Minecraft protocol version. */
    public static class VersionMapping {
        /** Server-to-client packet IDs mapped to ordering channels or special delivery policies. */
        public Int2IntArrayMap s2c = new Int2IntArrayMap();

        /** Client-to-server packet IDs mapped to ordering channels or special delivery policies. */
        public Int2IntArrayMap c2s = new Int2IntArrayMap();
    }
}
