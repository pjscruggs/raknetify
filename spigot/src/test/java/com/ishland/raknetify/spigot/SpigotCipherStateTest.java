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

package com.ishland.raknetify.spigot;

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.common.Constants;
import com.ishland.raknetify.common.connection.MultiChannelingStreamingCompression;
import com.ishland.raknetify.common.connection.MultiChannellingEncryption;
import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;
import com.ishland.raknetify.common.data.ProtocolMultiChannelMappings;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.FrameData;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Exercises native-handler transitions, encrypted frame bytes, and ownership on rejected logins.
 */
class SpigotCipherStateTest {
    /** Ephemeral server key used to verify that capture forwards the actual native key owner. */
    private static final PrivateKey SERVER_KEY = serverKey();

    /**
     * Creates a real RSA private key for the fixture without reusing production credentials.
     *
     * @return freshly generated test server key
     * @throws ExceptionInInitializerError if the runtime has no supported RSA key generator
     */
    private static PrivateKey serverKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair().getPrivate();
        } catch (GeneralSecurityException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** Deterministic fixture key with no relationship to a Minecraft account. */
    private static final SecretKey KEY = new SecretKeySpec(new byte[16], "AES");

    /**
     * A status response sharing the play bundle delimiter's ID must reach the client instead of
     * being discarded.
     */
    @Test
    void statusPacketNeverUsesPlayDiscardRules() {
        SpigotCipherState state = new SpigotCipherState(SERVER_KEY);
        var mapping = ProtocolMultiChannelMappings.INSTANCE.mappings.get(777);
        var classifier =
                new RakNetSimpleMultiChannelCodec.PacketIdBasedOverrideHandler(mapping.s2c, "test");
        SpigotPacketHandler packets =
                new SpigotPacketHandler(state, new SpigotTransportGuard(state), classifier);
        ByteBuf payload = Unpooled.buffer().writeByte(0).writeByte(2).writeByte('{').writeByte('}');
        try {
            assertEquals(Integer.MIN_VALUE, classifier.getChannelOverride(payload, true));
            assertEquals(0, packets.classify(payload, true));
            assertEquals(0, payload.readerIndex());
        } finally {
            payload.release();
        }
    }

    /**
     * A recovered candidate secret cannot enable encryption before native challenge validation.
     *
     * @throws Exception if fixture key extraction or cipher initialization fails
     */
    @Test
    void candidateAloneDoesNotEnableEncryption() throws Exception {
        EmbeddedChannel channel = pipeline();
        try {
            SpigotCipherState state = new SpigotCipherState(SERVER_KEY);
            state.capture(new KeyResponse());
            state.refresh(channel.pipeline());
            assertNull(channel.pipeline().get(MultiChannellingEncryption.NAME));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Native cipher cleanup runs once and replacement frames preserve both plaintext and routing
     * metadata.
     *
     * @throws Exception if fixture key extraction or cipher initialization fails
     */
    @Test
    void acceptedKeyEncryptsFramesAndClosesNativeCiphers() throws Exception {
        EmbeddedChannel channel = pipeline();
        EmbeddedChannel peer = new EmbeddedChannel(new MultiChannellingEncryption(KEY, true));
        RemovalWitness inbound = new RemovalWitness();
        RemovalWitness outbound = new RemovalWitness();
        try {
            SpigotCipherState state = new SpigotCipherState(SERVER_KEY);
            state.capture(new KeyResponse());
            channel.pipeline().addLast("decrypt", inbound).addLast("encrypt", outbound);
            state.refresh(channel.pipeline());
            assertEquals(1, inbound.removals);
            assertEquals(1, outbound.removals);
            assertNull(channel.pipeline().get("encrypt"));
            ByteBuf bytes = Unpooled.wrappedBuffer(new byte[] {4, 8, 15, 16, 23, 42});
            FrameData original = FrameData.create(channel.alloc(), 0xfd, bytes);
            bytes.release();
            original.setOrderChannel(3);
            channel.writeOutbound(original);
            assertEquals(0, original.refCnt());
            FrameData encrypted = channel.readOutbound();
            assertEquals(3, encrypted.getOrderChannel());
            peer.writeInbound(encrypted);
            FrameData decoded = peer.readInbound();
            ByteBuf data = decoded.createData();
            try {
                assertEquals(0xfd, data.readUnsignedByte());
                byte[] actual = new byte[data.readableBytes()];
                data.readBytes(actual);
                assertArrayEquals(new byte[] {4, 8, 15, 16, 23, 42}, actual);
                assertEquals(3, decoded.getOrderChannel());
            } finally {
                data.release();
                decoded.release();
            }
            state.refresh(channel.pipeline());
            assertEquals(1, inbound.removals);
        } finally {
            channel.finishAndReleaseAll();
            peer.finishAndReleaseAll();
        }
    }

    /**
     * An incomplete native transition fails before installing an asymmetric transport cipher.
     *
     * @throws Exception if fixture key extraction or cipher initialization fails
     */
    @Test
    void rejectsOneSidedNativeEncryption() throws Exception {
        EmbeddedChannel channel = pipeline();
        try {
            SpigotCipherState state = new SpigotCipherState(SERVER_KEY);
            state.capture(new KeyResponse());
            channel.pipeline().addLast("encrypt", new ChannelDuplexHandler());
            assertThrows(IllegalStateException.class, () -> state.refresh(channel.pipeline()));
            assertNull(channel.pipeline().get(MultiChannellingEncryption.NAME));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * A repeated key packet cannot replace a pending shared secret before native processing.
     *
     * @throws Exception if fixture key extraction or cipher initialization fails
     */
    @Test
    void rejectsDuplicateKeyResponse() throws Exception {
        SpigotCipherState state = new SpigotCipherState(SERVER_KEY);
        state.capture(new KeyResponse());
        assertThrows(IllegalStateException.class, () -> state.capture(new KeyResponse()));
    }

    /**
     * Unexpected encryption closes the connection and releases the frame that could not be safely
     * decrypted.
     */
    @Test
    void invalidTransitionReleasesIncomingFrame() {
        EmbeddedChannel channel = pipeline();
        SpigotTransportGuard guard = new SpigotTransportGuard(new SpigotCipherState(SERVER_KEY));
        channel.pipeline().addFirst("guard", guard).addLast("encrypt", new ChannelDuplexHandler());
        ByteBuf payload = Unpooled.buffer().writeByte(7);
        FrameData frame = FrameData.create(channel.alloc(), 0xfd, payload);
        payload.release();
        try {
            assertThrows(IllegalStateException.class, () -> channel.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Redundant native compressors are removed only after a negotiated streaming capability was
     * observed.
     *
     * @throws Exception if fixture key extraction or cipher initialization fails
     */
    @Test
    void negotiatedCompressionClosesNativeHandlers() throws Exception {
        EmbeddedChannel channel = pipeline();
        RemovalWitness compressor = new RemovalWitness();
        RemovalWitness decompressor = new RemovalWitness();
        try {
            SpigotTransportGuard guard =
                    new SpigotTransportGuard(new SpigotCipherState(SERVER_KEY));
            channel.pipeline().addLast("compress", compressor).addLast("decompress", decompressor);
            assertFalse(guard.suppressCompression(channel.pipeline()));
            guard.refresh(channel.pipeline());
            assertEquals(0, compressor.removals);
            channel.pipeline()
                    .replace(
                            MultiChannelingStreamingCompression.NAME,
                            MultiChannelingStreamingCompression.NAME,
                            new NegotiatedCompression());
            assertTrue(guard.suppressCompression(channel.pipeline()));
            guard.refresh(channel.pipeline());
            assertEquals(1, compressor.removals);
            assertEquals(1, decompressor.removals);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Creates a pipeline anchor without starting streaming negotiation or transport timers.
     *
     * @return owned embedded channel that each test must close
     */
    private static EmbeddedChannel pipeline() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline()
                .addLast(MultiChannelingStreamingCompression.NAME, new ChannelDuplexHandler());
        return channel;
    }

    /** Public fixture matching only the native key response's shared-secret accessor contract. */
    public static final class KeyResponse {
        /**
         * Supplies a known test secret while leaving native challenge acceptance to the separate
         * handler markers.
         *
         * @param privateKey actual server key passed through the reflective native accessor
         * @return deterministic AES secret
         */
        public SecretKey getSecretKey(final PrivateKey privateKey) {
            assertSame(SERVER_KEY, privateKey);
            return KEY;
        }
    }

    /** Counts native resource cleanup calls when transport handlers are removed. */
    private static final class RemovalWitness extends ChannelDuplexHandler {
        /** Number of times this handler's native cleanup would have run. */
        private int removals;

        /**
         * Records cleanup without owning an actual native allocation.
         *
         * @param context removed handler context
         */
        @Override
        public void handlerRemoved(final ChannelHandlerContext context) {
            removals++;
        }
    }

    /**
     * Models a completed peer negotiation without requiring UDP scheduling in the ownership
     * regression.
     */
    private static final class NegotiatedCompression extends MultiChannelingStreamingCompression {
        /** Creates the same frame-ID contract used by production streaming compression. */
        private NegotiatedCompression() {
            super(
                    Constants.RAKNET_GAME_PACKET_ID,
                    Constants.RAKNET_STREAMING_COMPRESSION_PACKET_ID);
        }

        /**
         * Reports the completed negotiation modeled by this fixture.
         *
         * @return true for the established streaming capability
         */
        @Override
        public boolean isActive() {
            return true;
        }
    }
}
