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

import com.ishland.raknetify.common.connection.RakNetSimpleMultiChannelCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import network.ycc.raknet.frame.Frame;
import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.pipeline.FrameOrderIn;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tests native signed-chat chains and their transport dependencies without an account or
 * session-service bypass.
 */
final class SignedChatTest {

    /**
     * Session establishment, messages and acknowledgements share the server's incoming chat state.
     */
    private static final String[][] SERVERBOUND = {
        {"class_7861", "ServerboundChatSessionUpdatePacket"},
        {"class_2797", "ServerboundChatPacket"},
        {"class_7472", "ServerboundChatCommandPacket"},
        {"class_7640", "ServerboundChatAckPacket"},
        {"class_2797", "ServerboundChatPacket"}
    };

    /** Player-list session keys must precede messages, deletion and removal of the same player. */
    private static final String[][] CLIENTBOUND = {
        {"class_2703", "ClientboundPlayerInfoUpdatePacket"},
        {"class_7438", "ClientboundPlayerChatPacket"},
        {"class_7438", "ClientboundPlayerChatPacket"},
        {"class_7617", "ClientboundDeleteChatPacket"},
        {"class_7828", "ClientboundPlayerInfoRemovePacket"}
    };

    /**
     * Since 1.20.5 signed command arguments use a separate packet but still advance the same chat
     * chain.
     */
    private static final String[][] SIGNED_COMMANDS = {
        {"class_7861", "ServerboundChatSessionUpdatePacket"},
        {"class_9449", "ServerboundChatCommandSignedPacket"},
        {"class_2797", "ServerboundChatPacket"},
        {"class_7640", "ServerboundChatAckPacket"}
    };

    /**
     * Selects class names while the installed game supplies all signing and validation behavior.
     */
    private static final boolean MODERN = FixtureRuntime.modern();

    /** Prevents construction of the stateless test entry point. */
    private SignedChatTest() {}

    /**
     * Exercises the session-indexed chat protocol introduced in 1.19.3, including a failing order
     * control.
     *
     * @throws Exception if a native signature, packet dependency or reflected API fails the witness
     */
    static void verify() throws Exception {
        if (!FixtureRuntime.atLeast("1.19.3")) {
            return;
        }
        GameplayOrderingTest.verifyGroup(SERVERBOUND, "serverbound chat");
        GameplayOrderingTest.verifyGroup(CLIENTBOUND, "clientbound chat");
        if (FixtureRuntime.atLeast("1.20.5")) {
            GameplayOrderingTest.verifyGroup(SIGNED_COMMANDS, "signed command");
        }
        bootstrapRegistries();
        Chain chain = new Chain();
        chain.requireRejectedOrder();
        verifyTransport(chain);
        System.out.println("PASS native signed chat chain with rejected out-of-order control");
    }

    /**
     * Initializes vanilla registries used by chat component codecs before this prelaunch-only
     * fixture signs messages.
     *
     * @throws ReflectiveOperationException if the runtime's normal bootstrap entry points are
     *     unavailable
     */
    private static void bootstrapRegistries() throws ReflectiveOperationException {
        type("class_155", "SharedConstants")
                .getMethod(MODERN ? "tryDetectVersion" : "method_36208")
                .invoke(null);
        type("class_2966", "server.Bootstrap")
                .getMethod(MODERN ? "bootStrap" : "method_12851")
                .invoke(null);
    }

    /**
     * Sends real native signatures through the production frame codec, then validates the reversed
     * network delivery.
     *
     * @param chain native signed bodies and independently initialized receiving chain
     * @throws ReflectiveOperationException if native signature decoding fails after transport
     *     restoration
     */
    private static void verifyTransport(final Chain chain) throws ReflectiveOperationException {
        int route = GameplayOrderingTest.nativeRoutes(new String[][] {SERVERBOUND[1]})[0];
        RakNetSimpleMultiChannelCodec codec = new RakNetSimpleMultiChannelCodec(253);
        codec.addHandler((bytes, silent) -> route);
        EmbeddedChannel sender = new EmbeddedChannel(codec);
        EmbeddedChannel receiver = new TransportTestChannel(new FrameOrderIn());
        List<Frame> frames = new ArrayList<>();
        try {
            sender.writeOutbound(RakNetSimpleMultiChannelCodec.SIGNAL_START_MULTICHANNEL);
            ((FrameData) sender.readOutbound()).release();
            for (int i = 0; i < chain.signatures.size(); i++) {
                sender.writeOutbound(
                        Unpooled.buffer().writeByte(i).writeBytes(chain.signatures.get(i)));
                FrameData data = sender.readOutbound();
                try {
                    frames.add(Frame.createOrdered(data, i, 0));
                } finally {
                    data.release();
                }
            }
            while (!frames.isEmpty()) {
                receiver.writeInbound(frames.remove(frames.size() - 1));
            }
            Object decoder = chain.newDecoder();
            for (int i = 0; i < chain.signatures.size(); i++) {
                validateNext(chain, decoder, receiver, i);
            }
            require(receiver.readInbound() == null, "Duplicate signed chat message");
        } finally {
            frames.forEach(Frame::release);
            sender.finishAndReleaseAll();
            receiver.finishAndReleaseAll();
        }
    }

    /**
     * Reconstructs each signature from received bytes and asks Minecraft to verify its signature
     * and chain index.
     *
     * @param chain source bodies and native unpacking method
     * @param decoder receiver with its own next-message index
     * @param receiver production reorderer containing the delivered frames
     * @param expected original signing position
     * @throws ReflectiveOperationException if Minecraft rejects the delivered signature or chain
     *     order
     */
    private static void validateNext(
            final Chain chain,
            final Object decoder,
            final EmbeddedChannel receiver,
            final int expected)
            throws ReflectiveOperationException {
        FrameData frame = receiver.readInbound();
        require(frame != null, "Missing signed chat frame");
        try {
            ByteBuf bytes = frame.createData();
            try {
                bytes.skipBytes(1);
                require(bytes.readUnsignedByte() == expected, "Signed chat order changed");
                byte[] signature = new byte[bytes.readableBytes()];
                bytes.readBytes(signature);
                chain.unpack.invoke(
                        decoder,
                        chain.signature.getConstructor(byte[].class).newInstance(signature),
                        chain.bodies.get(expected));
            } finally {
                bytes.release();
            }
        } finally {
            frame.release();
        }
    }

    /**
     * Owns a synthetic profile key and native signing chain. The test key has no Mojang
     * certification.
     */
    private static final class Chain {
        /** Stable sender identity included in every native signature. */
        private final UUID sender = new UUID(1, 2);

        /** Stable session identity prevents accepting a signature from a different chat session. */
        private final UUID session = new UUID(3, 4);

        /** Native chain class used to give sender and receiver independent indices. */
        private final Class<?> chain = type("class_7610", "network.chat.SignedMessageChain");

        /** Native signature wrapper reconstructed from transported bytes. */
        private final Class<?> signature = type("class_7469", "network.chat.MessageSignature");

        /**
         * Native body class retains content, timestamp, salt and last-seen data in the signed
         * payload.
         */
        private final Class<?> body = type("class_7608", "network.chat.SignedMessageBody");

        /** Native profile public key used for real cryptographic validation. */
        private final Object key;

        /**
         * Interface method avoids invoking inaccessible synthetic decoder implementation classes.
         */
        private final Method unpack =
                type("class_7610$class_7611", "network.chat.SignedMessageChain$Decoder")
                        .getMethod("unpack", this.signature, this.body);

        /** Bodies signed consecutively by the native encoder. */
        private final List<Object> bodies = new ArrayList<>();

        /** Encoded signature bytes that must survive RakNet transport unchanged. */
        private final List<byte[]> signatures = new ArrayList<>();

        /**
         * Generates an isolated RSA key and three genuinely signed messages with native sequence
         * indices.
         *
         * @throws Exception if the runtime no longer supports the native chat crypto contract
         */
        private Chain() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            Class<?> data =
                    type("class_7428$class_7443", "world.entity.player.ProfilePublicKey$Data");
            Object publicData =
                    data.getConstructor(Instant.class, PublicKey.class, byte[].class)
                            .newInstance(
                                    Instant.now().plusSeconds(3600), pair.getPublic(), new byte[0]);
            this.key =
                    type("class_7428", "world.entity.player.ProfilePublicKey")
                            .getConstructor(data)
                            .newInstance(publicData);
            Class<?> signer = type("class_7501", "util.Signer");
            Object nativeSigner =
                    signer.getMethod(
                                    MODERN ? "from" : "method_44179",
                                    PrivateKey.class,
                                    String.class)
                            .invoke(null, pair.getPrivate(), "SHA256withRSA");
            Object encoder =
                    this.chain
                            .getMethod(MODERN ? "encoder" : "method_44880", signer)
                            .invoke(newChain(), nativeSigner);
            signBodies(encoder);
        }

        /**
         * Signs increasing timestamps and salts using Minecraft's own body serialization.
         *
         * @param encoder native sender whose index advances after each signature
         * @throws ReflectiveOperationException if native body construction or signing fails
         */
        private void signBodies(final Object encoder) throws ReflectiveOperationException {
            Class<?> seen = type("class_7635", "network.chat.LastSeenMessages");
            Object empty = seen.getConstructor(List.class).newInstance(List.of());
            Method pack =
                    type("class_7610$class_7612", "network.chat.SignedMessageChain$Encoder")
                            .getMethod("pack", this.body);
            Method bytes = this.signature.getMethod(MODERN ? "bytes" : "comp_925");
            Instant now = Instant.now();
            for (int i = 0; i < 3; i++) {
                Object message =
                        this.body
                                .getConstructor(String.class, Instant.class, long.class, seen)
                                .newInstance(
                                        "Signed transport fixture " + i,
                                        now.plusMillis(i),
                                        (long) i,
                                        empty);
                this.bodies.add(message);
                this.signatures.add((byte[]) bytes.invoke(pack.invoke(encoder, message)));
            }
        }

        /**
         * Creates a chain at index zero for the same sender and chat session.
         *
         * @return independent native chain with no accepted messages
         * @throws ReflectiveOperationException if the native constructor changes
         */
        private Object newChain() throws ReflectiveOperationException {
            return this.chain
                    .getConstructor(UUID.class, UUID.class)
                    .newInstance(this.sender, this.session);
        }

        /**
         * Binds a fresh receiving chain to the synthetic public key, retaining native signature
         * checks.
         *
         * @return native decoder that has not accepted the first signed message
         * @throws ReflectiveOperationException if native decoder creation fails
         */
        private Object newDecoder() throws ReflectiveOperationException {
            return this.chain
                    .getMethod(MODERN ? "decoder" : "method_44874", this.key.getClass())
                    .invoke(newChain(), this.key);
        }

        /**
         * Proves this fixture detects the reported broken-chain condition when a later signature
         * arrives first.
         *
         * @throws ReflectiveOperationException if native APIs change or rejection is not the native
         *     chain exception
         * @throws AssertionError if a later signature is incorrectly accepted at the start of a
         *     fresh chain
         */
        private void requireRejectedOrder() throws ReflectiveOperationException {
            Object later =
                    this.signature.getConstructor(byte[].class).newInstance(this.signatures.get(1));
            try {
                this.unpack.invoke(newDecoder(), later, this.bodies.get(1));
            } catch (InvocationTargetException failure) {
                Class<?> expected =
                        type(
                                "class_7610$class_7825",
                                "network.chat.SignedMessageChain$DecodeException");
                if (!expected.isInstance(failure.getCause())) {
                    throw failure;
                }
                return;
            }
            throw new AssertionError(
                    "Native decoder accepted a later chat signature at index zero");
        }
    }

    /**
     * Selects runtime class names without replacing any native packet or signature implementation.
     *
     * @param legacy intermediary name used by obfuscated releases
     * @param modern unobfuscated name relative to net.minecraft
     * @return loaded native class in the fixture's installed runtime
     * @throws ClassNotFoundException if the supported runtime lacks the named chat contract
     */
    private static Class<?> type(final String legacy, final String modern)
            throws ClassNotFoundException {
        return Class.forName("net.minecraft." + (MODERN ? modern : legacy));
    }

    /**
     * Fails without requiring the launcher to enable Java assertions.
     *
     * @param condition invariant established by the transport witness
     * @param message diagnostic preserved in the fixture log
     * @throws AssertionError if the transport violates the checked invariant
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
