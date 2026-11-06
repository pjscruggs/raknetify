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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket.Reliability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Tests the wire format, independent directions, authentication, replay, and buffer ownership. */
class AuthenticatedFrameCipherTest {
    /**
     * Empty and large direct-buffer payloads retain exact bytes through the NIO cipher path.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void directBuffersPreserveEmptyAndLargePayloads() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        var encoder = new AuthenticatedFrameCipher(key, true, true);
        var decoder = new AuthenticatedFrameCipher(key, false, true);
        FrameData metadata = frame(new byte[0]);
        try {
            for (int size : new int[] {0, 1, 4096, 1048576}) {
                ByteBuf input = Unpooled.directBuffer(size).writeZero(size);
                ByteBuf encoded = Unpooled.directBuffer(size + AuthenticatedFrameCipher.OVERHEAD);
                ByteBuf decoded = Unpooled.directBuffer(size);
                try {
                    assertTrue(encoder.transform(metadata, input, encoded));
                    assertTrue(decoder.transform(metadata, encoded, decoded));
                    assertEquals(size, decoded.readableBytes());
                    assertTrue(ByteBufUtil.equals(input, decoded));
                } finally {
                    input.release();
                    encoded.release();
                    decoded.release();
                }
            }
        } finally {
            metadata.release();
        }
    }

    /**
     * Authentication binds the outer packet identifier as well as the payload and channel.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void packetIdentifierCannotBeChanged() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf encoded = fixture.encrypt(0);
            FrameData changed = FrameData.create(UnpooledByteBufAllocator.DEFAULT, 0xfe, encoded);
            changed.setReliability(Reliability.RELIABLE_ORDERED);
            try {
                assertThrows(
                        GeneralSecurityException.class,
                        () -> fixture.decoder.transform(changed, encoded, fixture.output));
                assertTrue(fixture.decrypt(encoded));
            } finally {
                encoded.release();
                changed.release();
            }
        }
    }

    /**
     * Tests the HKDF extract-and-expand implementation against RFC 5869 test case one.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void hkdfMatchesPublishedVector() throws Exception {
        byte[] material = new byte[22];
        Arrays.fill(material, (byte) 0x0b);
        HexFormat hex = HexFormat.of();
        byte[] actual =
                FrameKeyDerivation.extractAndExpand(
                        material,
                        hex.parseHex("000102030405060708090a0b0c"),
                        hex.parseHex("f0f1f2f3f4f5f6f7f8f9"));
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf",
                hex.formatHex(actual));
    }

    /**
     * Repeated plaintext receives distinct nonces and cannot be reflected into the sending side.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void repeatedPlaintextAndDirectionsAreIndependent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf first = fixture.encrypt(0);
            ByteBuf second = fixture.encrypt(0);
            try {
                assertFalse(ByteBufUtil.equals(first, second));
                assertFalse(
                        ByteBufUtil.equals(
                                first.slice(AuthenticatedFrameCipher.HEADER_SIZE, 6),
                                second.slice(AuthenticatedFrameCipher.HEADER_SIZE, 6)));
                assertTrue(fixture.decrypt(first));
                assertTrue(fixture.decrypt(second));
                var reflected = new AuthenticatedFrameCipher(fixture.key, false, false);
                assertThrows(
                        GeneralSecurityException.class,
                        () -> reflected.transform(fixture.frame, first, fixture.output));
            } finally {
                first.release();
                second.release();
            }
        }
    }

    /**
     * Every envelope, ciphertext, and tag byte is authenticated before any state is accepted.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void tamperingCannotPinSaltOrConsumeReplaySequence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf encoded = fixture.encrypt(0);
            try {
                for (int index = 0; index < encoded.readableBytes(); index++) {
                    encoded.setByte(index, encoded.getByte(index) ^ 1);
                    assertThrows(GeneralSecurityException.class, () -> fixture.decrypt(encoded));
                    assertEquals(0, fixture.output.readableBytes());
                    encoded.setByte(index, encoded.getByte(index) ^ 1);
                }
                assertTrue(fixture.decrypt(encoded));
                assertFalse(fixture.decrypt(encoded));
            } finally {
                encoded.release();
            }
        }
    }

    /**
     * Truncated envelopes fail before header reads can exceed the readable payload.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void everyTruncatedLengthIsRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf encoded = fixture.encrypt(0);
            try {
                for (int length = 0; length < encoded.readableBytes(); length++) {
                    ByteBuf truncated = encoded.slice(0, length);
                    assertThrows(GeneralSecurityException.class, () -> fixture.decrypt(truncated));
                }
                assertTrue(fixture.decrypt(encoded));
            } finally {
                encoded.release();
            }
        }
    }

    /**
     * A delayed reliable packet remains valid after more than a sliding window of later traffic.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void reliableUnorderedTrafficSurvivesLongReordering() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.frame.setReliability(Reliability.RELIABLE);
            ByteBuf delayed = fixture.encrypt(0);
            try {
                for (int index = 0; index < 5000; index++) {
                    fixture.roundTrip(0);
                    fixture.roundTrip(1);
                }
                fixture.frame.setOrderChannel(0);
                assertTrue(fixture.decrypt(delayed));
                assertFalse(fixture.decrypt(delayed));
            } finally {
                delayed.release();
            }
        }
    }

    /**
     * Faster ordered lanes cannot expire an authentic packet that is delayed on another lane.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void orderedChannelsHaveIndependentReplayHistory() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf delayed = fixture.encrypt(0);
            try {
                for (int index = 0; index < 5000; index++) {
                    fixture.roundTrip(1);
                }
                fixture.frame.setOrderChannel(0);
                assertTrue(fixture.decrypt(delayed));
            } finally {
                delayed.release();
            }
        }
    }

    /**
     * Altered outer ordering indices cannot make older authenticated actions follow newer ones.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void orderedLaneCannotDeliverReversedAuthenticatedSequences() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf delayed = fixture.encrypt(0);
            try {
                fixture.roundTrip(0);
                assertFalse(fixture.decrypt(delayed));
            } finally {
                delayed.release();
            }
        }
    }

    /**
     * Fragmentation may strengthen delivery guarantees without invalidating the encrypted frame.
     *
     * @param reliability original delivery policy before transport fragmentation
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @ParameterizedTest
    @EnumSource(Reliability.class)
    void reliabilityPromotionSurvivesFragmentation(final Reliability reliability) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.frame.setReliability(reliability);
            ByteBuf encoded = fixture.encrypt(0);
            try {
                fixture.frame.setReliability(reliability.makeReliable());
                assertTrue(fixture.decrypt(encoded));
            } finally {
                encoded.release();
            }
        }
    }

    /**
     * Outer ordering and reliability changes cannot redirect an otherwise valid plaintext.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void changedRoutingIsRejected() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf encoded = fixture.encrypt(0);
            try {
                fixture.frame.setOrderChannel(1);
                assertThrows(GeneralSecurityException.class, () -> fixture.decrypt(encoded));
                fixture.frame.setOrderChannel(0);
                fixture.frame.setReliability(Reliability.UNRELIABLE);
                assertThrows(GeneralSecurityException.class, () -> fixture.decrypt(encoded));
                fixture.frame.setReliability(Reliability.RELIABLE_ORDERED);
                assertTrue(fixture.decrypt(encoded));
            } finally {
                encoded.release();
            }
        }
    }

    /**
     * An independent JCA decoder verifies the documented nonce, header, and HKDF layout.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void wireFormatMatchesIndependentGcmDecoder() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ByteBuf encoded = fixture.encrypt(3);
            try {
                byte[] salt = new byte[16];
                encoded.getBytes(5, salt);
                SecretKey traffic = FrameKeyDerivation.derive(fixture.key, salt, true);
                byte[] nonce = new byte[12];
                nonce[3] = (byte) (Reliability.RELIABLE_ORDERED.code() * 8 + 3);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, traffic, new GCMParameterSpec(128, nonce));
                cipher.updateAAD(encoded.nioBuffer(0, 30));
                cipher.updateAAD(new byte[] {(byte) 0xfd});
                byte[] decoded =
                        cipher.doFinal(
                                ByteBufUtil.getBytes(encoded, 30, encoded.readableBytes() - 30));
                assertArrayEquals(ByteBufUtil.getBytes(fixture.plaintext), decoded);
            } finally {
                encoded.release();
            }
        }
    }

    /**
     * The sender refuses another invocation before either the nonce or aggregate byte budget wraps.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void keyUsageLimitsRequireReconnect() throws Exception {
        try (Fixture fixture = new Fixture()) {
            setCounter(fixture.encoder, "packets", 1L << 32);
            assertThrows(
                    GeneralSecurityException.class,
                    () ->
                            fixture.encoder.transform(
                                    fixture.frame, fixture.plaintext, fixture.output));
            setCounter(fixture.encoder, "packets", 0);
            setCounter(fixture.encoder, "bytes", 1L << 36);
            assertThrows(
                    GeneralSecurityException.class,
                    () ->
                            fixture.encoder.transform(
                                    fixture.frame, fixture.plaintext, fixture.output));
            assertEquals(0, fixture.output.readableBytes());
        }
    }

    /**
     * A failed tag closes the handler's connection and releases the received frame.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void pipelineRejectsTamperingAndReleasesOwnedFrames() throws Exception {
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        var client = new EmbeddedChannel(new MultiChannellingEncryption(key, true));
        var server = new EmbeddedChannel(new MultiChannellingEncryption(key, false));
        try {
            FrameData input = frame(new byte[] {1, 2, 3});
            assertTrue(client.writeOutbound(input));
            assertEquals(0, input.refCnt());
            FrameData encoded = client.readOutbound();
            ByteBuf data = encoded.createData();
            data.setByte(data.writerIndex() - 1, data.getByte(data.writerIndex() - 1) ^ 1);
            data.release();
            assertThrows(GeneralSecurityException.class, () -> server.writeInbound(encoded));
            assertEquals(0, encoded.refCnt());
            assertFalse(server.isOpen());
            assertNull(server.readInbound());
        } finally {
            client.finishAndReleaseAll();
            server.finishAndReleaseAll();
        }
    }

    /**
     * Distinct sender sessions cannot inject frames into an already authenticated connection.
     *
     * @throws Exception if fixture setup or a cryptographic operation fails
     */
    @Test
    void senderRestartCannotReuseAnEstablishedSession() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.roundTrip(0);
            var restarted = new AuthenticatedFrameCipher(fixture.key, true, true);
            ByteBuf encoded = Unpooled.buffer();
            try {
                restarted.transform(fixture.frame, fixture.plaintext, encoded);
                assertThrows(GeneralSecurityException.class, () -> fixture.decrypt(encoded));
            } finally {
                encoded.release();
            }
        }
    }

    /**
     * Sets a counter at its limit without billions of synthetic packets or a production bypass.
     *
     * @param cipher cipher whose exhaustion path is under test
     * @param name private counter field
     * @param value boundary value to install
     * @throws ReflectiveOperationException if the internal counter cannot be located
     */
    private static void setCounter(
            final AuthenticatedFrameCipher cipher, final String name, final long value)
            throws ReflectiveOperationException {
        Field field = AuthenticatedFrameCipher.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(cipher, value);
    }

    /**
     * Wraps fixture payload bytes in a reliable ordered application frame.
     *
     * @param payload owned fixture bytes copied by FrameData
     * @return reference-counted frame whose caller must release it
     */
    private static FrameData frame(final byte[] payload) {
        ByteBuf bytes = Unpooled.wrappedBuffer(payload);
        try {
            FrameData result = FrameData.create(UnpooledByteBufAllocator.DEFAULT, 0xfd, bytes);
            result.setReliability(Reliability.RELIABLE_ORDERED);
            return result;
        } finally {
            bytes.release();
        }
    }

    /** Owns one independent client-to-server cipher pair and its test buffers. */
    private static final class Fixture implements AutoCloseable {
        /**
         * Fresh test key prevents ciphertext fixtures from depending on a static production key.
         */
        private final SecretKey key = KeyGenerator.getInstance("AES").generateKey();

        /** Client-to-server sender under test. */
        private final AuthenticatedFrameCipher encoder =
                new AuthenticatedFrameCipher(key, true, true);

        /** Matching server-side receiver under test. */
        private final AuthenticatedFrameCipher decoder =
                new AuthenticatedFrameCipher(key, false, true);

        /** Frame metadata reused only within this fixture's single test thread. */
        private final FrameData frame = frame(new byte[] {4, 8, 15, 16, 23, 42});

        /** Known plaintext used for all round-trip assertions. */
        private final ByteBuf plaintext = Unpooled.wrappedBuffer(new byte[] {4, 8, 15, 16, 23, 42});

        /** Private output buffer cleared before every receive attempt. */
        private final ByteBuf output = Unpooled.buffer();

        /**
         * Initializes fresh keys and ciphers for one isolated test.
         *
         * @throws GeneralSecurityException if the runtime lacks the required algorithms
         */
        private Fixture() throws GeneralSecurityException {}

        /**
         * Encodes one packet on the selected channel, preserving caller ownership of the result.
         *
         * @param channel outgoing ordering channel
         * @return encoded envelope owned by the caller
         * @throws GeneralSecurityException if the test cipher rejects encryption
         */
        private ByteBuf encrypt(final int channel) throws GeneralSecurityException {
            frame.setOrderChannel(channel);
            ByteBuf encoded = Unpooled.buffer();
            try {
                encoder.transform(frame, plaintext, encoded);
                return encoded;
            } catch (GeneralSecurityException failure) {
                encoded.release();
                throw failure;
            }
        }

        /**
         * Attempts authentication with reusable output that never contains a previous result.
         *
         * @param encoded complete incoming envelope
         * @return whether the frame was newly accepted
         * @throws GeneralSecurityException if authentication or framing fails
         */
        private boolean decrypt(final ByteBuf encoded) throws GeneralSecurityException {
            output.clear();
            boolean accepted = decoder.transform(frame, encoded, output);
            if (accepted) {
                assertArrayEquals(ByteBufUtil.getBytes(plaintext), ByteBufUtil.getBytes(output));
            }
            return accepted;
        }

        /**
         * Performs and releases one successful send/receive exchange.
         *
         * @param channel channel whose independent nonce space is exercised
         * @throws GeneralSecurityException if the codec rejects a fresh authentic frame
         */
        private void roundTrip(final int channel) throws GeneralSecurityException {
            ByteBuf encoded = encrypt(channel);
            try {
                assertTrue(decrypt(encoded));
            } finally {
                encoded.release();
            }
        }

        /** Releases every reference-counted fixture resource after success or failure. */
        @Override
        public void close() {
            frame.release();
            plaintext.release();
            output.release();
        }
    }
}
