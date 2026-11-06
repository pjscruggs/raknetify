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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Derives independent frame keys with RFC 5869 HKDF-SHA-256 from Minecraft's session secret. */
final class FrameKeyDerivation {
    /** Prevents construction of the stateless key derivation helper. */
    private FrameKeyDerivation() {}

    /**
     * Separates sending directions and independently initialized cipher sessions.
     *
     * @param secret Minecraft's negotiated AES session secret
     * @param salt random per-sender session salt transmitted in authenticated frame headers
     * @param clientSender whether this key protects client-to-server traffic
     * @return independent AES-128 traffic key
     * @throws GeneralSecurityException if HMAC-SHA-256 is unavailable
     */
    static SecretKey derive(final SecretKey secret, final byte[] salt, final boolean clientSender)
            throws GeneralSecurityException {
        byte[] material = secret.getEncoded();
        byte[] info =
                ("Raknetify2 frame encryption v1 " + (clientSender ? "client" : "server"))
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] expanded = null;
        try {
            expanded = extractAndExpand(material, salt, info);
            return new SecretKeySpec(expanded, 0, 16, "AES");
        } finally {
            Arrays.fill(material, (byte) 0);
            if (expanded != null) {
                Arrays.fill(expanded, (byte) 0);
            }
        }
    }

    /**
     * Computes the first complete HKDF output block, sufficient for an AES-128 traffic key.
     *
     * @param material input keying material whose ownership stays with the caller
     * @param salt HKDF extraction salt
     * @param info protocol and direction separation label
     * @return 32-byte first output block from RFC 5869 extract-and-expand
     * @throws GeneralSecurityException if HMAC initialization fails
     */
    static byte[] extractAndExpand(final byte[] material, final byte[] salt, final byte[] info)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] extracted = mac.doFinal(material);
        try {
            mac.init(new SecretKeySpec(extracted, "HmacSHA256"));
            mac.update(info);
            return mac.doFinal(new byte[] {1});
        } finally {
            Arrays.fill(extracted, (byte) 0);
        }
    }

    /**
     * Recovers the session secret from Minecraft's specified key-as-IV cipher setup.
     *
     * @param cipher unused vanilla AES/CFB8 cipher supplied during encryption installation
     * @return copied session secret for the independent authenticated transport
     * @throws IllegalArgumentException if the cipher does not match Minecraft's cipher contract
     */
    static SecretKey minecraftSecret(final Cipher cipher) {
        byte[] iv = cipher.getIV();
        if (!"AES/CFB8/NoPadding".equalsIgnoreCase(cipher.getAlgorithm())
                || iv == null
                || iv.length != 16) {
            throw new IllegalArgumentException("Unsupported Minecraft encryption cipher");
        }
        try {
            return new SecretKeySpec(iv, "AES");
        } finally {
            Arrays.fill(iv, (byte) 0);
        }
    }

    /**
     * Rejects an inconsistent pair before either direction begins exchanging encrypted packets.
     *
     * @param inbound unused vanilla inbound cipher
     * @param outbound unused vanilla outbound cipher
     * @return shared Minecraft secret after both cipher IVs agree
     * @throws IllegalArgumentException if the supplied directions use different session secrets
     */
    static SecretKey minecraftSecret(final Cipher inbound, final Cipher outbound) {
        SecretKey first = minecraftSecret(inbound);
        SecretKey second = minecraftSecret(outbound);
        if (!MessageDigest.isEqual(first.getEncoded(), second.getEncoded())) {
            throw new IllegalArgumentException("Minecraft cipher directions use different secrets");
        }
        return first;
    }
}
