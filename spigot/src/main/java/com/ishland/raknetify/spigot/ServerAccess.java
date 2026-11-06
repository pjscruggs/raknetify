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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.util.AttributeKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the tested server and Netty listener layout without bundling Minecraft classes. */
final class ServerAccess {
    /** Server-owned login key, used only to adapt the already negotiated encryption state. */
    private final PrivateKey privateKey;
    /** Snapshot of bound TCP listeners whose original child initializers will also handle UDP clients. */
    private final List<Channel> listeners;

    /**
     * Validates the modern server layout before any public UDP socket is opened.
     * @param craftServer Bukkit server exposing its native server through getServer
     * @throws ReflectiveOperationException when the running server has an unsupported layout
     * @throws IllegalArgumentException if the actual Minecraft version has not been tested
     */
    ServerAccess(Object craftServer) throws ReflectiveOperationException {
        Object server = invoke(craftServer, "getServer");
        if (!"26.3".equals(invoke(server, "getServerVersion"))) {
            throw new IllegalArgumentException("This Raknetify2 Spigot build supports Minecraft 26.3");
        }
        privateKey = ((KeyPair) invoke(server, "getKeyPair")).getPrivate();
        Object connection = invoke(server, "getConnection");
        Object channels = field(connection, "channels");
        listeners = new ArrayList<>();
        synchronized (channels) {
            for (Object item : (List<?>) channels) listeners.add(((ChannelFuture) item).channel());
        }
    }

    /**
     * Returns the login key without exposing it to configuration or logging.
     * @return private key owned by the running server
     */
    PrivateKey privateKey() { return privateKey; }

    /**
     * Returns the listener snapshot taken after server startup.
     * @return immutable list of existing TCP channels
     */
    List<Channel> listeners() { return List.copyOf(listeners); }

    /**
     * Finds the actual bootstrap acceptor rather than guessing a pipeline position.
     * @param listener initialized TCP listener
     * @return handler retaining the original child initializer and attributes
     * @throws IllegalStateException if no supported Netty acceptor is installed
     */
    static ChannelHandler acceptor(Channel listener) {
        for (Map.Entry<String, ChannelHandler> entry : listener.pipeline()) {
            if (entry.getValue().getClass().getName().equals("io.netty.bootstrap.ServerBootstrap$ServerBootstrapAcceptor")) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("Minecraft listener has no supported Netty acceptor");
    }

    /**
     * Copies bootstrap attributes and invokes the original server initializer on the accepted channel.
     * @param acceptor TCP acceptor supplying the server's child configuration
     * @param channel new RakNet application channel
     * @throws ReflectiveOperationException if Netty or the native initializer rejects this channel
     */
    static void initialize(ChannelHandler acceptor, Channel channel) throws ReflectiveOperationException {
        for (Object item : (Object[]) field(acceptor, "childAttrs")) {
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) item;
            AttributeKey<?> key = (AttributeKey<?>) entry.getKey();
            channel.attr(AttributeKey.valueOf(key.name())).set(entry.getValue());
        }
        Object initializer = field(acceptor, "childHandler");
        Method method = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
        method.setAccessible(true);
        method.invoke(initializer, channel);
    }

    /**
     * Calls an explicit public no-argument method on a supported server object.
     * @param target instance owning the method
     * @param name verified modern method name
     * @return value returned by the server
     * @throws ReflectiveOperationException if the method is absent or invocation fails
     */
    static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    /**
     * Reads one declared implementation field after making its access explicit.
     * @param target instance whose concrete class declares the field
     * @param name verified field name
     * @return field value without copying mutable server state
     * @throws ReflectiveOperationException if the expected field is unavailable
     */
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
