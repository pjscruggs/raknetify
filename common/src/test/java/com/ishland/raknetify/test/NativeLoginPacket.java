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

import io.netty.buffer.ByteBuf;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.security.Key;
import java.security.PublicKey;

import javax.crypto.SecretKey;

/** Uses the installed Minecraft version's login packet construction and wire serialization. */
public final class NativeLoginPacket {
    /** Prevents instances of this native protocol adapter. */
    private NativeLoginPacket() {}

    /**
     * Writes a native key response after its packet identifier, preserving version-specific fields.
     * Minecraft owns the login cipher and padding. This does not change the security properties of
     * that protocol or substitute a new handshake for the one the server accepts.
     *
     * @param output caller-owned response buffer with the login packet identifier already written
     * @param secret synthetic session secret used by the isolated transport probe
     * @param key public key supplied by the loopback server
     * @param challenge original server challenge or the deliberately corrupted rejection control
     * @throws ReflectiveOperationException if the native packet contract has changed
     */
    public static void write(
            final ByteBuf output,
            final SecretKey secret,
            final PublicKey key,
            final byte[] challenge)
            throws ReflectiveOperationException {
        boolean modern =
                FabricLoader.getInstance()
                        .getModContainer("minecraft")
                        .orElseThrow()
                        .getMetadata()
                        .getVersion()
                        .getFriendlyString()
                        .startsWith("26.");
        Class<?> packetType =
                Class.forName(
                        modern
                                ? "net.minecraft.network.protocol.login.ServerboundKeyPacket"
                                : "net.minecraft.class_2917");
        Object packet = create(packetType, secret, key, challenge);
        Class<?> bufferType =
                Class.forName(
                        modern
                                ? "net.minecraft.network.FriendlyByteBuf"
                                : "net.minecraft.class_2540");
        Object buffer = bufferType.getConstructor(ByteBuf.class).newInstance(output);
        serialize(packetType, bufferType, packet, buffer);
    }

    /**
     * Constructs the real packet using Minecraft's encryption entry point for this generation.
     * Newer snapshots moved encryption from the packet constructor into the login listener, which
     * calls Crypt before constructing the packet record.
     *
     * @param packetType native serverbound login response class
     * @param secret synthetic AES secret, never a real account credential
     * @param key loopback server's advertised RSA key
     * @param challenge bytes that the native server will validate
     * @return native packet whose serializer determines the complete wire representation
     * @throws ReflectiveOperationException if Minecraft no longer exposes the expected login API
     */
    private static Object create(
            final Class<?> packetType,
            final SecretKey secret,
            final PublicKey key,
            final byte[] challenge)
            throws ReflectiveOperationException {
        if (!packetType.isRecord()) {
            return packetType
                    .getConstructor(SecretKey.class, PublicKey.class, byte[].class)
                    .newInstance(secret, key, challenge);
        }
        Method encrypt =
                Class.forName("net.minecraft.util.Crypt")
                        .getMethod("encryptUsingKey", Key.class, byte[].class);
        return packetType
                .getConstructor(byte[].class, byte[].class)
                .newInstance(
                        encrypt.invoke(null, key, secret.getEncoded()),
                        encrypt.invoke(null, key, challenge));
    }

    /**
     * Delegates field ordering and optional fields to the native writer or record stream codec.
     *
     * @param packetType actual packet class from the selected Minecraft installation
     * @param bufferType native packet buffer class for that installation's namespace
     * @param packet populated native login response
     * @param buffer native wrapper over the caller's output bytes
     * @throws ReflectiveOperationException if no supported native serializer is present
     * @throws NoSuchMethodException if the native packet exposes neither a codec nor a known writer
     */
    private static void serialize(
            final Class<?> packetType,
            final Class<?> bufferType,
            final Object packet,
            final Object buffer)
            throws ReflectiveOperationException {
        if (packetType.isRecord()) {
            Object codec = packetType.getField("STREAM_CODEC").get(null);
            Class.forName("net.minecraft.network.codec.StreamCodec")
                    .getMethod("encode", Object.class, Object.class)
                    .invoke(codec, buffer, packet);
            return;
        }
        for (String name : new String[] {"write", "method_56021", "method_11052"}) {
            for (Method method : packetType.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && java.util.Arrays.equals(
                                method.getParameterTypes(), new Class<?>[] {bufferType})) {
                    method.setAccessible(true);
                    method.invoke(packet, buffer);
                    return;
                }
            }
        }
        throw new NoSuchMethodException("Native login response writer: " + packetType.getName());
    }
}
