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
import io.netty.buffer.ByteBufUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies complete login-start wire layouts at each native protocol boundary used by admission probes. */
class LoginProbeTest {
    /** Optional login negotiation must retain its transaction ID while ordinary disconnects remain untouched. */
    @Test
    void declinesOnlyOptionalQueries() {
        ByteBuf response = RaknetProxyProbe.declineQuery(new byte[]{4, (byte) 0xac, 2, 0});
        try {
            assertEquals("02ac0200", ByteBufUtil.hexDump(response));
        } finally {
            response.release();
        }
        assertNull(RaknetProxyProbe.declineQuery(new byte[]{0, 2, 123, 125}));
        assertNull(RaknetProxyProbe.declineQuery(new byte[]{1}));
    }

    /** Checks that older servers receive neither unknown UUID bytes nor omitted profile-key presence flags. */
    @Test
    void nativeLoginLayouts() {
        verify(757, "");
        verify(758, "");
        verify(759, "00");
        verify(760, "000112345678123456781234567812345678");
        verify(761, "0112345678123456781234567812345678");
        verify(763, "0112345678123456781234567812345678");
        verify(764, "12345678123456781234567812345678");
        verify(777, "12345678123456781234567812345678");
    }

    /**
     * Compares the complete frame against an independent literal wire vector and releases its storage.
     * @param protocol native Minecraft protocol number
     * @param suffix exact fields following the login name for this protocol generation
     */
    private static void verify(int protocol, String suffix) {
        ByteBuf packet = RaknetProxyProbe.loginHello(protocol);
        try {
            assertEquals("000f446973636f6e6e65637450726f6265" + suffix,
                    ByteBufUtil.hexDump(packet), "Protocol " + protocol);
        } finally {
            packet.release();
        }
    }
}
