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

package com.ishland.raknetify.common.connection;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises opt-in destination selection and connection-scoped query ownership without network or account dependencies. */
class TransferSupportTest {

    /** Disposable operator configuration used to test startup and the native platform bridge. */
    @TempDir Path folder;
    /** Different TCP and UDP ports make unintended fallback or port reuse observable. */
    private static final String RULE = "{\"destinations\":[{\"host\":\"play.example\",\"tcpPort\":25565,\"udpPort\":25570}]}";

    /** Requires both an exact destination and an affirmative capability result. */
    @Test
    void selectsOnlyAdvertisedConfiguredDestinations() {
        TransferDestinations policy = TransferDestinations.parse(RULE);
        assertEquals(new TransferDestinations.Endpoint("raknet;PLAY.EXAMPLE.", 25570), policy.select("PLAY.EXAMPLE.", 25565, true));
        assertNull(policy.select("play.example", 25565, false));
        assertNull(policy.select("play.example", 25566, true));
        assertNull(policy.select("other.example", 25565, true));
        assertNull(policy.select("sub.play.example", 25565, true));
        assertNull(policy.select("raknet;play.example", 25565, true));
        assertNull(policy.select("raknetl;play.example", 25565, true));
        assertNull(policy.select("bad host", 25565, true));
    }

    /** Matches equivalent IPv6 spelling and IDN hosts without invoking DNS. */
    @Test
    void normalizesLiteralAndInternationalHosts() {
        TransferDestinations ipv6 = TransferDestinations.parse(RULE.replace("play.example", "[2001:db8::1]"));
        assertEquals(25570, ipv6.select("2001:db8:0:0:0:0:0:1", 25565, true).port());
        TransferDestinations idn = TransferDestinations.parse(RULE.replace("play.example", "bücher.example"));
        assertEquals(25570, idn.select("xn--bcher-kva.example", 25565, true).port());
    }

    /**
     * Rejects unsafe or misspelled host forms instead of broadening matching.
     * @param host invalid operator host value
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "*.example", "bad host", "raknet;play.example", "host:25565", "[::1", "fe80::1%eth0", "https://example.com"})
    void rejectsInvalidHosts(String host) {
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse(RULE.replace("play.example", host)));
    }

    /**
     * Prevents truncation, coercion and out-of-range values in port declarations.
     * @param port invalid JSON port expression
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536", "25565.5", "99999999999999999999999", "\"25565\"", "null", "true"})
    void rejectsInvalidPorts(String port) {
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse(RULE.replace("25565", port)));
    }

    /**
     * Detects unknown schema keys, malformed documents and excessive lists before enabling any rule.
     * @throws IOException if the disposable configuration cannot be written
     */
    @Test
    void rejectsMalformedAndOversizedPolicies() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse(RULE.replace("udpPort", "udpport")));
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse("null"));
        String entry = "{\"host\":\"play.example\",\"tcpPort\":25565,\"udpPort\":25570}";
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse("{\"destinations\":[" + entry + "," + entry + "]}"));
        String huge = "{\"destinations\":[" + (entry + ",").repeat(128) + entry + "]}";
        assertThrows(IllegalArgumentException.class, () -> TransferDestinations.parse(huge));
        Path config = this.folder.resolve("oversized.json");
        Files.writeString(config, " ".repeat(65537));
        assertThrows(IOException.class, () -> TransferDestinations.load(config));
    }

    /**
     * Keeps an unconfigured installation passive and fails initialization on an invalid existing file.
     * @throws IOException if the disposable operator configuration cannot be written
     */
    @Test
    void defaultsToDisabledAndRejectsInvalidConfiguration() throws IOException {
        TransferSupport.initialize(this.folder);
        assertFalse(TransferSupport.enabled());
        assertFalse(TransferDestinations.load(this.folder.resolve("absent.json")).enabled());
        assertFalse(TransferDestinations.parse("{\"destinations\":[]}").enabled());
        Files.writeString(this.folder.resolve("raknetify2-transfers.json"), "{}");
        assertThrows(IllegalStateException.class, () -> TransferSupport.initialize(this.folder));
    }

    /**
     * Requires the owned pending response and scopes the positive result to its channel only.
     * @throws IOException if the disposable configured policy cannot be written
     */
    @Test
    void handlesOnlyOneOwnedReplyAndNeverSharesCapability() throws IOException {
        Files.writeString(this.folder.resolve("raknetify2-transfers.json"), RULE);
        TransferSupport.initialize(this.folder);
        EmbeddedChannel capable = new EmbeddedChannel();
        EmbeddedChannel ordinary = new EmbeddedChannel();
        try {
            TransferSupport.Negotiation query = new TransferSupport.Negotiation();
            assertFalse(query.answer(query.transactionId(), true, capable));
            assertTrue(query.start());
            assertFalse(query.start());
            assertTrue(query.waiting());
            assertFalse(query.answer(0, true, capable));
            assertNull(TransferSupport.select(capable, "play.example", 25565));
            assertTrue(query.answer(query.transactionId(), true, capable));
            assertFalse(query.waiting());
            assertFalse(query.answer(query.transactionId(), false, capable));
            assertEquals(25570, TransferSupport.select(capable, "play.example", 25565).port());
            assertNull(TransferSupport.select(ordinary, "play.example", 25565));
            assertNull(TransferSupport.select(null, "play.example", 25565));
        } finally {
            capable.finishAndReleaseAll();
            ordinary.finishAndReleaseAll();
        }
    }

    /** Proceeds with vanilla behavior when the client answers that it does not handle the query. */
    @Test
    void unsupportedReplyCompletesNegotiation() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            TransferSupport.Negotiation query = new TransferSupport.Negotiation();
            query.start();
            assertTrue(query.answer(query.transactionId(), false, channel));
            assertFalse(query.waiting());
            assertNull(TransferSupport.select(channel, "play.example", 25565));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
