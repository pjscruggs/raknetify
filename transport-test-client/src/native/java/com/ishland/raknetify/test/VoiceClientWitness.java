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

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.Event;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/** Requires the real voice client's authenticated UDP connection without opening a microphone in tests. */
@ForgeVoicechatPlugin
public final class VoiceClientWitness implements VoicechatPlugin {
    /** Published by the voice connection event after both authentication and nonce checks succeed. */
    private static volatile boolean connected;

    /**
     * Identifies the isolated witness independently of the production socket provider.
     * @return fixture-only plugin identifier
     */
    @Override
    public String getPluginId() {
        return "transport_voice_fixture";
    }

    /**
     * Observes the ordinary connection event and cancels microphone startup before capture can begin.
     * @param registration real voice plugin registry
     * @throws IllegalStateException if the pinned voice release lacks its microphone cancellation event
     */
    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientVoicechatConnectionEvent.class, VoiceClientWitness::changed);
        try {
            // This internal event is pinned by the fixture, not used by the production integration.
            var event = Class.forName("de.maxhenkel.voicechat.api.internal.events.StartMicEvent").asSubclass(Event.class);
            registration.registerEvent(event, VoiceClientWitness::cancelMicrophone);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Cannot prevent microphone startup in the voice fixture", failure);
        }
    }

    /**
     * Fails the fixture rather than allowing audio capture if cancellation stops being supported.
     * @param event microphone pre-start callback
     * @throws IllegalStateException if the pinned event cannot prevent microphone capture
     */
    private static void cancelMicrophone(Event event) {
        if (!event.cancel()) throw new IllegalStateException("Voice fixture could not cancel microphone startup");
    }

    /**
     * Records only the event emitted after the native encrypted voice handshake completes.
     * @param event native client connection transition
     */
    private static void changed(ClientVoicechatConnectionEvent event) {
        connected = event.isConnected();
        if (connected) System.out.println("PASS native authenticated voice connection");
    }

    /**
     * Keeps the gameplay witness alive until the independent voice handshake also completes.
     * @return whether the real voice client currently has an authenticated connection
     */
    static boolean connected() {
        return connected;
    }
}
