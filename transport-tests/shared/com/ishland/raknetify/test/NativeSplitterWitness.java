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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Checks native frame-decoder buffer ownership directly, without waiting for garbage collection.
 */
public final class NativeSplitterWitness {

    /** Prevents construction of the static native codec witness. */
    private NativeSplitterWitness() {}

    /**
     * Decodes repeated frames and requires every replaced helper buffer to lose its owning
     * reference. Older decoders without a persistent helper buffer have no replacement ownership to
     * check.
     *
     * @param type native frame decoder after installed mods have transformed it
     * @throws ReflectiveOperationException if the constructor or helper field cannot be accessed
     * @throws IllegalStateException if a helper buffer survives its owner or a frame is corrupted
     */
    public static void verify(final Class<?> type) throws ReflectiveOperationException {
        Field field = helperField(type);
        if (field != null) {
            ChannelHandler handler = construct(type);
            EmbeddedChannel channel = new EmbeddedChannel(handler);
            try {
                for (int count = 0; count < 3; count++) {
                    verifyFrame(channel, handler, field);
                }
            } finally {
                channel.finishAndReleaseAll();
            }
            if (((ByteBuf) field.get(handler)).refCnt() != 0) {
                throw new IllegalStateException("Native frame helper survived handler removal");
            }
        }
        System.out.println("PASS native frame helper ownership");
    }

    /**
     * Finds the decoder's private reusable buffer without depending on mapped field names.
     *
     * @param type native frame-decoder class
     * @return accessible helper field, or null for versions without a persistent helper
     */
    private static Field helperField(final Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() == ByteBuf.class && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /**
     * Constructs the frame decoder with its optional packet-size logger disabled.
     *
     * @param type native class containing the historical or logger-taking constructor
     * @return new decoder with its ordinary constructor-owned helper buffer
     * @throws ReflectiveOperationException if neither supported constructor is available
     * @throws NoSuchMethodException if the decoder exposes no supported constructor
     */
    private static ChannelHandler construct(final Class<?> type)
            throws ReflectiveOperationException {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() == 0) {
                return (ChannelHandler) constructor.newInstance();
            }
            if (constructor.getParameterCount() == 1
                    && !constructor.getParameterTypes()[0].isPrimitive()) {
                return (ChannelHandler) constructor.newInstance((Object) null);
            }
        }
        throw new NoSuchMethodException("Unsupported native frame decoder constructor");
    }

    /**
     * Checks one complete frame and cleans a detected leak so a failed witness creates no extra
     * leak.
     *
     * @param channel embedded native decoder pipeline
     * @param handler native decoder owning the reusable helper field
     * @param field reflected helper field belonging to this decoder
     * @throws IllegalAccessException if the previously accessible field becomes unavailable
     * @throws IllegalStateException if replacing the helper abandons an owning reference
     */
    private static void verifyFrame(
            final EmbeddedChannel channel, final ChannelHandler handler, final Field field)
            throws IllegalAccessException {
        ByteBuf previous = (ByteBuf) field.get(handler);
        try {
            channel.writeInbound(Unpooled.buffer().writeByte(1).writeByte(42));
            verifyDecoded(channel);
            if (previous != field.get(handler) && previous.refCnt() != 0) {
                throw new IllegalStateException(
                        "Native frame decoder abandoned an unreleased helper");
            }
        } finally {
            if (previous != field.get(handler) && previous.refCnt() != 0) {
                previous.release();
            }
        }
    }

    /**
     * Requires one intact decoded payload and releases the witness's output ownership.
     *
     * @param channel native pipeline whose next inbound message should contain the test byte
     * @throws IllegalStateException if the native decoder loses or changes the payload
     */
    private static void verifyDecoded(final EmbeddedChannel channel) {
        ByteBuf payload = channel.readInbound();
        if (payload == null) {
            throw new IllegalStateException("Native frame decoder produced no payload");
        }
        try {
            if (payload.readableBytes() != 1 || payload.readByte() != 42) {
                throw new IllegalStateException("Native frame payload changed");
            }
        } finally {
            payload.release();
        }
    }
}
