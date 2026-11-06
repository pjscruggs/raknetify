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

package com.ishland.raknetify.common.voice;

import com.google.gson.JsonParser;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartingEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Optional server integration discovered through the voice API on Fabric, Forge, NeoForge and
 * Bukkit.
 */
@ForgeVoicechatPlugin
public final class VoicePlugin implements VoicechatPlugin {
    /**
     * Identifies this socket provider in Simple Voice Chat's plugin registry.
     *
     * @return stable provider identifier
     */
    @Override
    public String getPluginId() {
        return "raknetify2";
    }

    /**
     * Selects the shared socket before voice opens its configured UDP port.
     *
     * @param registration voice server lifecycle event registry
     * @throws UncheckedIOException if the operator's configuration cannot be read
     */
    @Override
    public void registerEvents(final EventRegistration registration) {
        try {
            if (!enabled(Path.of("config", "raknetify2-voicechat.json"))) {
                return;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Cannot read Raknetify2 voice sharing configuration", failure);
        }
        registration.registerEvent(VoicechatServerStartingEvent.class, VoicePlugin::starting);
    }

    /**
     * Refuses to replace another socket provider after the operator explicitly enabled shared-port
     * voice.
     *
     * @param event voice server's pre-bind event
     * @throws IllegalStateException if another plugin already selected a custom socket
     */
    private static void starting(final VoicechatServerStartingEvent event) {
        if (event.getSocketImplementation() != null) {
            throw new IllegalStateException("Another voice plugin already supplies a UDP socket");
        }
        event.setSocketImplementation(new SharedVoiceSocket());
    }

    /**
     * Requires an explicit boolean opt-in and rejects misspelled or oversized configuration.
     *
     * @param path server-local configuration file
     * @return enabled setting, or false only when the file is absent
     * @throws IOException if reading fails or the document exceeds one KiB
     * @throws IllegalArgumentException if its schema is not exactly one boolean enabled field
     */
    static boolean enabled(final Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(1025);
            if (bytes.length > 1024) {
                throw new IOException("Voice sharing configuration exceeds one KiB");
            }
            var root =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (!root.keySet().equals(Set.of("enabled"))
                    || !root.get("enabled").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("enabled").isBoolean()) {
                throw new IllegalArgumentException("Expected only a boolean enabled field");
            }
            return root.get("enabled").getAsBoolean();
        } catch (NoSuchFileException absent) {
            return false;
        }
    }
}
