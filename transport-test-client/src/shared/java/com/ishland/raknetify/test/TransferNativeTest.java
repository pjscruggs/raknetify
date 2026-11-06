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

import com.ishland.raknetify.common.connection.TransferSupport;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Exercises transformed native login and transfer send paths without graphics, DNS or a player
 * account.
 */
final class TransferNativeTest {

    /**
     * Runtime namespace determines only reflective class and member names, never packet behavior.
     */
    private static final boolean MODERN = FixtureRuntime.modern();

    /** Prevents constructing a stateless isolated witness. */
    private TransferNativeTest() {}

    /**
     * Verifies query handling and every native send overload on versions that have transfer
     * packets.
     *
     * @throws Exception if a native API changes, a hook is absent or a packet differs from the
     *     expected result
     */
    static void verify() throws Exception {
        if (!FixtureRuntime.atLeast("1.20.5")) {
            return;
        }
        Path config = FixtureRuntime.gameDirectory().resolve("transfer-test-config");
        Files.createDirectories(config);
        Files.writeString(
                config.resolve("raknetify2-transfers.json"),
                "{\"destinations\":[{\"host\":\"fixture.test\",\"tcpPort\":25565,\"udpPort\":25570}]}");
        TransferSupport.initialize(config);
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            Object connection = connection(channel);
            verifyQuery(connection, channel);
            verifyTransfers(connection, channel);
            Class.forName(typeName("class_3248", "server.network.ServerLoginPacketListenerImpl"));
            System.out.println("PASS native transfer capability and send overloads");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Creates the native connection with an embedded channel so its ordinary send methods remain in
     * use.
     *
     * @param channel test-owned outbound packet sink
     * @return initialized Minecraft connection with no external socket
     * @throws ReflectiveOperationException if the native connection constructor or channel field
     *     changes
     */
    private static Object connection(final EmbeddedChannel channel)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(typeName("class_2535", "network.Connection"));
        Constructor<?> constructor =
                Arrays.stream(type.getConstructors())
                        .filter(item -> item.getParameterCount() == 1)
                        .findFirst()
                        .orElseThrow();
        Object connection =
                constructor.newInstance(constructor.getParameterTypes()[0].getEnumConstants()[0]);
        Field field =
                Arrays.stream(type.getDeclaredFields())
                        .filter(item -> item.getType() == Channel.class)
                        .findFirst()
                        .orElseThrow();
        field.setAccessible(true);
        field.set(connection, channel);
        return connection;
    }

    /**
     * Runs the real client login handler and verifies its handled reply retains the requested
     * transaction ID.
     *
     * @param connection native connection used by the handler's ordinary send method
     * @param channel outbound packet sink
     * @throws ReflectiveOperationException if the native login constructor or query contract
     *     changes
     */
    private static void verifyQuery(final Object connection, final EmbeddedChannel channel)
            throws ReflectiveOperationException {
        Class<?> login =
                Class.forName(
                        typeName(
                                "class_635",
                                "client.multiplayer.ClientHandshakePacketListenerImpl"));
        Constructor<?> constructor = login.getConstructors()[0];
        Object[] arguments = new Object[constructor.getParameterCount()];
        for (int i = 0; i < arguments.length; i++) {
            arguments[i] = loginArgument(constructor.getParameterTypes()[i], connection);
        }
        Object listener = constructor.newInstance(arguments);
        Object payload =
                Class.forName("com.ishland.raknetify.fabric.common.connection.TransferQuery")
                        .getConstructor()
                        .newInstance();
        Class<?> query =
                Class.forName(
                        typeName(
                                "class_2899",
                                "network.protocol.login.ClientboundCustomQueryPacket"));
        Constructor<?> queryConstructor =
                Arrays.stream(query.getConstructors())
                        .filter(item -> item.getParameterCount() == 2)
                        .findFirst()
                        .orElseThrow();
        Method handler =
                Arrays.stream(login.getMethods())
                        .filter(item ->
                                Arrays.equals(item.getParameterTypes(), new Class<?>[] {query}))
                        .findFirst()
                        .orElseThrow();
        handler.invoke(listener, queryConstructor.newInstance(12345, payload));
        Object response = channel.readOutbound();
        require(response != null, "Missing native transfer capability response");
        require((int) component(response, int.class) == 12345, "Wrong query transaction ID");
        require(
                response.getClass().getRecordComponents()[1].getAccessor().invoke(response) != null,
                "Client declined known capability");
        require(channel.readOutbound() == null, "Duplicate native query response");
        handler.invoke(listener, queryConstructor.newInstance(54321, unknownQuery(payload)));
        Object unknown = channel.readOutbound();
        require((int) component(unknown, int.class) == 54321, "Unrelated query ID changed");
        require(
                unknown.getClass().getRecordComponents()[1].getAccessor().invoke(unknown) == null,
                "Unrelated query was claimed");
        require(channel.readOutbound() == null, "Duplicate unrelated query response");
    }

    /**
     * Creates an unrelated native query identifier to prove ordinary query fallback remains
     * available.
     *
     * @param known production payload used only to locate the native identifier interface
     * @return native payload carrying an unknown feature name
     * @throws ReflectiveOperationException if native identifier parsing changes
     */
    private static Object unknownQuery(final Object known) throws ReflectiveOperationException {
        Class<?> payload = known.getClass().getInterfaces()[0];
        Method id =
                Arrays.stream(payload.getMethods())
                        .filter(item -> item.getParameterCount() == 0)
                        .findFirst()
                        .orElseThrow();
        Class<?> identifier = id.getReturnType();
        Object unknown =
                identifier
                        .getMethod(MODERN ? "tryParse" : "method_12829", String.class)
                        .invoke(null, "fixture:unsupported");
        return java.lang.reflect.Proxy.newProxyInstance(
                payload.getClassLoader(),
                new Class<?>[] {payload},
                (proxy, method, args) -> method.getReturnType() == identifier ? unknown : null);
    }

    /**
     * Supplies inert values to the native login constructor without starting Minecraft or replacing
     * its methods.
     *
     * @param type native constructor argument type
     * @param connection real connection retained by the login handler
     * @return suitable constructor value, normally null for unused client services
     */
    private static Object loginArgument(final Class<?> type, final Object connection) {
        if (type.isInstance(connection)) {
            return connection;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == Consumer.class) {
            return (Consumer<Object>) ignored -> {};
        }
        return null;
    }

    /**
     * Exercises all public send overloads, preserving TCP fallback and each completion callback.
     *
     * @param connection real Minecraft connection with destination injection applied
     * @param channel isolated outbound packet sink and capability owner
     * @throws ReflectiveOperationException if native transfer packet or send contracts change
     */
    private static void verifyTransfers(final Object connection, final EmbeddedChannel channel)
            throws ReflectiveOperationException {
        Class<?> packet =
                Class.forName(
                        typeName(
                                "class_9151", "network.protocol.common.ClientboundTransferPacket"));
        Constructor<?> constructor =
                Arrays.stream(packet.getConstructors())
                        .filter(item -> item.getParameterTypes()[0] == String.class)
                        .findFirst()
                        .orElseThrow();
        Object original = transfer(constructor, "fixture.test", 25565);
        Method[] sends =
                Arrays.stream(connection.getClass().getMethods())
                        .filter(item ->
                                item.getName().equals(MODERN ? "send" : "method_10743")
                                        || "method_10752".equals(item.getName())
                                        || "method_52906".equals(item.getName()))
                        .toArray(Method[]::new);
        require(sends.length == 3, "Expected all three native send overloads");
        for (Method send : sends) {
            verifySend(send, connection, channel, original, "fixture.test", 25565);
        }
        TransferSupport.Negotiation negotiation = new TransferSupport.Negotiation();
        negotiation.start();
        negotiation.answer(negotiation.transactionId(), true, channel);
        for (Method send : sends) {
            verifySend(send, connection, channel, original, "raknet;fixture.test", 25570);
            verifySend(
                    send,
                    connection,
                    channel,
                    transfer(constructor, "other.test", 25565),
                    "other.test",
                    25565);
            verifySend(
                    send,
                    connection,
                    channel,
                    transfer(constructor, "fixture.test", 25566),
                    "fixture.test",
                    25566);
        }
    }

    /**
     * Supplies nonempty transfer properties on versions that define them, making metadata loss
     * observable.
     *
     * @param constructor native record constructor for this Minecraft release
     * @param host server-selected destination hostname
     * @param port original TCP port
     * @return native transfer packet containing fixture metadata when supported
     * @throws ReflectiveOperationException if the native constructor cannot be invoked
     */
    private static Object transfer(
            final Constructor<?> constructor, final String host, final int port)
            throws ReflectiveOperationException {
        if (constructor.getParameterCount() == 2) {
            return constructor.newInstance(host, port);
        }
        return constructor.newInstance(
                host, port, java.util.Map.of("fixture:marker", "preserve-exactly"));
    }

    /**
     * Checks a packet produced by one public native send entry point and its optional completion
     * callback.
     *
     * @param send native overload under test
     * @param connection native connection receiving the call
     * @param channel test-owned packet sink
     * @param packet original native transfer packet
     * @param host expected outgoing hostname
     * @param port expected outgoing port
     * @throws ReflectiveOperationException if the native send or record accessors cannot be called
     */
    private static void verifySend(
            final Method send,
            final Object connection,
            final EmbeddedChannel channel,
            final Object packet,
            final String host,
            final int port)
            throws ReflectiveOperationException {
        AtomicInteger completions = new AtomicInteger();
        Object[] arguments = new Object[send.getParameterCount()];
        arguments[0] = packet;
        if (arguments.length > 1) {
            arguments[1] = completion(send.getParameterTypes()[1], completions);
        }
        if (arguments.length > 2) {
            arguments[2] = true;
        }
        send.invoke(connection, arguments);
        channel.runPendingTasks();
        Object result = channel.readOutbound();
        require(result != null, "Native transfer was not sent");
        require(host.equals(component(result, String.class)), "Unexpected transfer host " + result);
        require((int) component(result, int.class) == port, "Unexpected transfer port " + result);
        if (result.getClass().getRecordComponents().length == 3) {
            require(
                    component(packet, java.util.Map.class)
                            .equals(component(result, java.util.Map.class)),
                    "Transfer properties changed");
        }
        if (arguments.length > 1) {
            require(completions.get() == 1, "Completion callback count changed");
        }
        require(channel.readOutbound() == null, "Duplicate native transfer");
    }

    /**
     * Adapts the callback type replaced by Minecraft without changing how the native send invokes
     * it.
     *
     * @param type native completion interface
     * @param completions counter shared with the witness
     * @return callback implementing the version's native completion contract
     */
    private static Object completion(final Class<?> type, final AtomicInteger completions) {
        if (type == ChannelFutureListener.class) {
            return (ChannelFutureListener) future -> completions.incrementAndGet();
        }
        return java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getReturnType() == void.class) {
                        completions.incrementAndGet();
                    }
                    return null;
                });
    }

    /**
     * Reads a uniquely typed record component without depending on obfuscated accessor names.
     *
     * @param record native packet record
     * @param type component's declared type
     * @return decoded component value
     * @throws ReflectiveOperationException if the native record accessor cannot be invoked
     */
    private static Object component(final Object record, final Class<?> type)
            throws ReflectiveOperationException {
        return Arrays.stream(record.getClass().getRecordComponents())
                .filter(item -> item.getType() == type)
                .findFirst()
                .orElseThrow()
                .getAccessor()
                .invoke(record);
    }

    /**
     * Selects an exact native runtime class name without changing the behavior being tested.
     *
     * @param legacy intermediary class suffix for obfuscated releases
     * @param modern unobfuscated class suffix for modern releases
     * @return fully qualified runtime class name
     */
    private static String typeName(final String legacy, final String modern) {
        return "net.minecraft." + (MODERN ? modern : legacy);
    }

    /**
     * Fails the isolated fixture independently of JVM assertion flags.
     *
     * @param condition native behavior that must hold
     * @param message diagnostic written to the fixture log on failure
     * @throws AssertionError if the checked native behavior differs from the contract
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
