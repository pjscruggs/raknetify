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

package com.ishland.raknetify.test.mixin;

import com.ishland.raknetify.common.connection.TransferSupport;
import com.ishland.raknetify.test.EncryptionExchange;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops a loopback fixture login only after production capability negotiation releases vanilla verification. */
@Mixin(value = ServerLoginPacketListenerImpl.class, priority = 1200)
public abstract class TransferLoginMixin {

    /** Native connection receives the real query response through Minecraft's packet decoder. */
    @Shadow @Final private Connection connection;
    /** Prevents a second probe response while the first asynchronous disconnect flushes. */
    @Unique private boolean raknetify$probeFinished;

    /**
     * Reports the negotiated policy result without admitting a player or replacing any production login code.
     * @param callback always cancels final admission in this explicitly enabled isolated fixture
     * @throws ReflectiveOperationException if the native connection no longer exposes its Netty channel
     * @throws IllegalStateException if a runner attempts to use this fixture outside loopback
     */
    @Inject(method = "verifyLoginAndFinishConnectionSetup", at = @At("HEAD"), cancellable = true)
    private void stopAfterNegotiation(CallbackInfo callback) throws ReflectiveOperationException {
        callback.cancel();
        if (this.raknetify$probeFinished) return;
        this.raknetify$probeFinished = true;
        Channel channel = EncryptionExchange.channel(this.connection);
        if (!(channel.remoteAddress() instanceof java.net.InetSocketAddress remote) || !remote.getAddress().isLoopbackAddress()) {
            throw new IllegalStateException("Transfer login fixture requires loopback");
        }
        boolean capable = TransferSupport.select(channel, "fixture.test", 25565) != null;
        String result = "transfer-probe:capable=" + capable;
        channel.writeAndFlush(new ClientboundLoginDisconnectPacket(Component.literal(result))).addListener(ChannelFutureListener.CLOSE);
        System.out.println("PASS native login " + result);
    }
}
