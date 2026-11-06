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

import io.netty.buffer.ByteBuf;

import network.ycc.raknet.frame.FrameData;
import network.ycc.raknet.packet.FramedPacket.Reliability;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Authenticates each frame independently so RakNet channels remain independently deliverable. */
final class AuthenticatedFrameCipher {
    /** Thread-safe entropy source reused across connections instead of reseeding each sender. */
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();

    /** Four-byte protocol marker followed by version one. Older CFB8 frames are never accepted. */
    private static final int MAGIC = 0x524e3245;

    /** Header bytes: magic, version, sender salt, reliability lane, and per-lane sequence. */
    static final int HEADER_SIZE = 30;

    /** Complete overhead includes the header and the full 128-bit GCM authentication tag. */
    static final int OVERHEAD = HEADER_SIZE + 16;

    /** Invocation ceiling across every lane sharing a traffic key. */
    private static final long MAX_PACKETS = 1L << 32;

    /** Conservative per-key byte budget bounds total authenticated data to 64 GiB. */
    private static final long MAX_BYTES = 1L << 36;

    /** Minecraft secret used only to derive this direction's independent traffic key. */
    private final SecretKey secret;

    /** True when this instance produces ciphertext rather than verifies incoming ciphertext. */
    private final boolean encrypting;

    /** Direction domain for HKDF, independent of whether this endpoint sends or receives. */
    private final boolean clientSender;

    /** JCA implementation of standard AES-GCM, confined to the connection event loop. */
    private final Cipher cipher;

    /** Reused nonce serialization storage confined to this direction's event loop. */
    private final ByteBuffer nonce = ByteBuffer.allocate(12);

    /** Reused outer packet identifier authenticated together with each frame header. */
    private final byte[] packetIdentifier = new byte[1];

    /** Untrusted receive scratch that is copied only when the first valid salt is established. */
    private final byte[] incomingSalt = new byte[16];

    /** Fresh sender salt, or the peer salt established by its first valid authenticated frame. */
    private byte[] salt;

    /** Traffic key retained after initialization or the first successful authentication. */
    private SecretKey trafficKey;

    /** Next nonce sequence for each reliability and ordering lane. */
    private final long[] sequences = new long[64];

    /** Receipt history allocated only for lanes actually used by the remote peer. */
    private final FrameReplayWindow[] replay = new FrameReplayWindow[64];

    /**
     * Limits retained gap ranges for the entire connection rather than separately for each lane.
     */
    private final FrameReplayWindow.Budget replayBudget = new FrameReplayWindow.Budget();

    /** Number of frames processed under the traffic key. */
    private long packets;

    /** Total plaintext and header bytes processed under the traffic key. */
    private long bytes;

    /**
     * Initializes one direction without sharing nonce space or traffic keys with the other
     * direction.
     *
     * @param secret negotiated Minecraft session secret
     * @param encrypting true for an outbound cipher
     * @param clientSender true for client-to-server traffic, false for server-to-client traffic
     * @throws GeneralSecurityException if AES-GCM or HKDF cannot be initialized
     */
    AuthenticatedFrameCipher(
            final SecretKey secret, final boolean encrypting, final boolean clientSender)
            throws GeneralSecurityException {
        this.secret = secret;
        this.encrypting = encrypting;
        this.clientSender = clientSender;
        cipher = Cipher.getInstance("AES/GCM/NoPadding");
        if (encrypting) {
            salt = new byte[16];
            SESSION_RANDOM.nextBytes(salt);
            trafficKey = FrameKeyDerivation.derive(secret, salt, clientSender);
        }
    }

    /**
     * Transforms a payload while leaving source ownership and indices with the caller.
     *
     * @param frame routing metadata bound to the ciphertext
     * @param input payload excluding RakNet's packet identifier
     * @param output destination that is forwarded only after successful authentication
     * @return false when an authentic duplicate or stale unreliable packet should be discarded
     * @throws GeneralSecurityException on malformed, incompatible, or unauthenticated traffic
     */
    boolean transform(final FrameData frame, final ByteBuf input, final ByteBuf output)
            throws GeneralSecurityException {
        if (encrypting) {
            encrypt(frame, input, output);
            return true;
        }
        return decrypt(frame, input, output);
    }

    /**
     * Allocates a unique nonce before invoking GCM, including when an encryption attempt fails.
     *
     * @param frame outgoing routing policy
     * @param input plaintext payload
     * @param output header and ciphertext destination
     * @throws GeneralSecurityException if the session budget or cipher operation fails
     */
    private void encrypt(final FrameData frame, final ByteBuf input, final ByteBuf output)
            throws GeneralSecurityException {
        int lane = lane(frame);
        consumeBudget(input.readableBytes());
        long sequence = sequences[lane]++;
        int start = output.writerIndex();
        output.writeInt(MAGIC).writeByte(1).writeBytes(salt).writeByte(lane).writeLong(sequence);
        initialize(Cipher.ENCRYPT_MODE, trafficKey, lane, sequence);
        authenticateHeader(frame, output, start);
        crypt(input, input.readerIndex(), input.readableBytes(), output);
    }

    /**
     * Verifies the complete header and payload before committing the peer salt or replay state.
     *
     * @param frame received routing policy, possibly promoted to reliable during fragmentation
     * @param input encoded authenticated frame
     * @param output private plaintext destination that must not escape on failure
     * @return whether this authentic frame is new and deliverable
     * @throws GeneralSecurityException if framing, authentication, routing, or session limits fail
     */
    private boolean decrypt(final FrameData frame, final ByteBuf input, final ByteBuf output)
            throws GeneralSecurityException {
        int start = input.readerIndex();
        validateHeader(input, start);
        input.getBytes(start + 5, incomingSalt);
        SecretKey key = receiveKey(incomingSalt);
        int lane = input.getUnsignedByte(start + 21);
        long sequence = input.getLong(start + 22);
        validateRouting(frame, lane, sequence);
        initialize(Cipher.DECRYPT_MODE, key, lane, sequence);
        authenticateHeader(frame, input, start);
        crypt(input, start + HEADER_SIZE, input.readableBytes() - HEADER_SIZE, output);
        return accept(incomingSalt, key, lane, sequence, output.readableBytes());
    }

    /**
     * Rejects old wire formats with an actionable error before attempting any decryption.
     *
     * @param input incoming frame payload
     * @param start first readable byte
     * @throws GeneralSecurityException if the complete versioned envelope is absent
     */
    private static void validateHeader(final ByteBuf input, final int start)
            throws GeneralSecurityException {
        if (input.readableBytes() < OVERHEAD
                || input.getInt(start) != MAGIC
                || input.getUnsignedByte(start + 4) != 1) {
            throw new GeneralSecurityException(
                    "Incompatible RakNet encryption. Update Raknetify2 on both ends.");
        }
    }

    /**
     * Derives a candidate key without letting a forged first frame pin the receiver's salt.
     *
     * @param incomingSalt salt carried in the incoming header
     * @return existing traffic key or an uncommitted candidate
     * @throws GeneralSecurityException if the established salt changes or derivation fails
     */
    private SecretKey receiveKey(final byte[] incomingSalt) throws GeneralSecurityException {
        if (salt == null) {
            return FrameKeyDerivation.derive(secret, incomingSalt, clientSender);
        }
        if (!MessageDigest.isEqual(salt, incomingSalt)) {
            throw new GeneralSecurityException("Encrypted session salt changed");
        }
        return trafficKey;
    }

    /**
     * Commits only authenticated state and drops duplicates without exposing their plaintext.
     *
     * @param incomingSalt verified sender salt
     * @param key key that verified the authentication tag
     * @param lane verified reliability and channel identifier
     * @param sequence verified nonce sequence
     * @param length authenticated plaintext size
     * @return whether this authenticated sequence is new
     * @throws GeneralSecurityException if bounded receipt history or key usage is exhausted
     */
    private boolean accept(
            final byte[] incomingSalt,
            final SecretKey key,
            final int lane,
            final long sequence,
            final int length)
            throws GeneralSecurityException {
        if (replay[lane] == null) {
            replay[lane] = new FrameReplayWindow(replayBudget);
        }
        Reliability reliability = Reliability.get(lane / 8);
        boolean accepted =
                reliability.isOrdered
                        ? replay[lane].acceptOrdered(sequence)
                        : replay[lane].accept(sequence, !reliability.isReliable);
        if (!accepted) {
            return false;
        }
        consumeBudget(length);
        if (salt == null) {
            salt = incomingSalt.clone();
            trafficKey = key;
        }
        return true;
    }

    /**
     * Caps key usage before a nonce can wrap and before the aggregate data budget is exceeded.
     *
     * @param length plaintext bytes in the next frame
     * @throws GeneralSecurityException when a fresh connection and key are required
     */
    private void consumeBudget(final int length) throws GeneralSecurityException {
        long nextBytes = bytes + length + OVERHEAD;
        if (packets >= MAX_PACKETS || nextBytes > MAX_BYTES) {
            throw new GeneralSecurityException(
                    "Encrypted session limit reached. Reconnect to renew keys.");
        }
        packets++;
        bytes = nextBytes;
    }

    /**
     * Encodes an authenticated nonce domain without relying on mutable outer frame indices.
     *
     * @param frame source frame
     * @return reliability code times eight plus normalized ordering channel
     * @throws GeneralSecurityException if the source channel is outside RakNet's eight channels
     */
    private static int lane(final FrameData frame) throws GeneralSecurityException {
        int channel = frame.getReliability().isOrdered ? frame.getOrderChannel() : 0;
        if (channel < 0 || channel >= 8) {
            throw new GeneralSecurityException("Invalid encrypted ordering channel");
        }
        return frame.getReliability().code() * 8 + channel;
    }

    /**
     * Allows only the reliability upgrade RakNet performs when fragmenting an unreliable frame.
     *
     * @param frame received outer metadata
     * @param lane original authenticated routing lane
     * @param sequence original authenticated nonce sequence
     * @throws GeneralSecurityException for an invalid counter or changed routing policy
     */
    private static void validateRouting(final FrameData frame, final int lane, final long sequence)
            throws GeneralSecurityException {
        if (lane >= 64 || sequence < 0 || sequence >= MAX_PACKETS) {
            throw new GeneralSecurityException("Invalid encrypted frame sequence or lane");
        }
        Reliability original = Reliability.get(lane / 8);
        if (frame.getReliability() != original
                && frame.getReliability() != original.makeReliable()) {
            throw new GeneralSecurityException("Encrypted reliability changed");
        }
        int channel = original.isOrdered ? frame.getOrderChannel() : 0;
        if (lane % 8 != channel) {
            throw new GeneralSecurityException("Encrypted ordering channel changed");
        }
    }

    /**
     * Forms a 96-bit GCM nonce from the lane and its monotonically increasing sequence.
     *
     * @param mode JCA encryption or decryption mode
     * @param key direction-specific traffic key
     * @param lane nonce domain within that traffic key
     * @param sequence never-repeated sequence within the lane
     * @throws GeneralSecurityException if the provider rejects cipher initialization
     */
    private void initialize(
            final int mode, final SecretKey key, final int lane, final long sequence)
            throws GeneralSecurityException {
        nonce.clear();
        nonce.putInt(lane).putLong(sequence);
        cipher.init(mode, key, new GCMParameterSpec(128, nonce.array()));
    }

    /**
     * Binds every envelope byte and the outer packet identifier to the authentication tag.
     *
     * @param frame source of the outer packet identifier
     * @param envelope buffer containing the complete header
     * @param start first header byte
     */
    private void authenticateHeader(
            final FrameData frame, final ByteBuf envelope, final int start) {
        cipher.updateAAD(envelope.nioBuffer(start, HEADER_SIZE));
        packetIdentifier[0] = (byte) frame.getPacketId();
        cipher.updateAAD(packetIdentifier);
    }

    /**
     * Uses NIO views to avoid retaining plaintext-sized scratch arrays on the connection.
     *
     * @param input source ciphertext or plaintext buffer
     * @param start first input byte
     * @param length number of input bytes
     * @param output writable result buffer owned exclusively by the caller
     * @throws GeneralSecurityException if authentication fails or the provider cannot transform
     *     data
     */
    private void crypt(final ByteBuf input, final int start, final int length, final ByteBuf output)
            throws GeneralSecurityException {
        int capacity = cipher.getOutputSize(length);
        output.ensureWritable(capacity);
        int written =
                cipher.doFinal(
                        input.nioBuffer(start, length),
                        output.nioBuffer(output.writerIndex(), capacity));
        output.writerIndex(output.writerIndex() + written);
    }
}
