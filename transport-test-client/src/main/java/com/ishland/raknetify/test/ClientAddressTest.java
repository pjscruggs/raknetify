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

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Optional;

/** Exercises the real client address classes with both mods transformed by Fabric before graphics start. */
public final class ClientAddressTest implements PreLaunchEntrypoint {

    /** Runs only in an explicitly requested fixture process and exits before starting the game window. */
    @Override
    public void onPreLaunch() {
        if (!Boolean.getBoolean("raknetify.test.addresses")) return;
        try {
            if (!Boolean.getBoolean("raknetify.test.baseline")) {
                GameplayOrderingTest.verify();
                SignedChatTest.verify();
                TransferNativeTest.verify();
            }
            if (!Boolean.getBoolean("raknetify.test.orderingOnly")) {
                AddressChecks checks = new AddressChecks();
                if (Boolean.getBoolean("raknetify.test.baseline")) checks.verifyBaseline();
                else checks.verify();
            }
            System.out.println("PASS native client address compatibility");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    /** Resolves namespace differences while leaving all address behavior to the installed client classes. */
    private static final class AddressChecks {

        /** Whether the runtime uses unobfuscated Minecraft names. */
        private final boolean modern = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                .getMetadata().getVersion().getFriendlyString().startsWith("26.");

        /** Native address class with Raknetify's prefix-preserving parser applied. */
        private final Class<?> address = type("net.minecraft.class_639", "net.minecraft.client.multiplayer.resolver.ServerAddress");

        /** Native resolver interface receiving Fast IP Ping's literal-address patch. */
        private final Class<?> resolver = type("net.minecraft.class_6369", "net.minecraft.client.multiplayer.resolver.ServerAddressResolver");

        /** Native resolved-address interface used to extract the original socket destination. */
        private final Class<?> resolved = type("net.minecraft.class_6368", "net.minecraft.client.multiplayer.resolver.ResolvedServerAddress");

        /** Native parser shared by server-list and direct-connection inputs. */
        private final Method parse = address.getMethod(modern ? "parseString" : "method_2950", String.class);

        /** Native hostname accessor after parsing and transport-prefix removal. */
        private final Method host = address.getMethod(modern ? "getHost" : "method_2952");

        /** Native port accessor that must retain explicit ports across both mod hooks. */
        private final Method port = address.getMethod(modern ? "getPort" : "method_2954");

        /** Native resolver implementation selected by Minecraft. */
        private final Object defaultResolver = resolver.getField(modern ? "SYSTEM" : "field_33744").get(null);

        /** Native connection thread whose constructor removes the transport prefix before DNS resolution. */
        private final Class<?> connector = type("net.minecraft.class_412$1", "net.minecraft.client.gui.screens.ConnectScreen$1");

        /**
         * Initializes reflective handles for the active native address contract.
         *
         * @throws ReflectiveOperationException if a required accessor or resolver field is absent
         */
        private AddressChecks() throws ReflectiveOperationException {
        }

        /**
         * Checks ordinary TCP and both RakNet prefixes through ping and connection hooks without external DNS.
         *
         * @throws ReflectiveOperationException if a native constructor or address hook cannot be exercised
         */
        private void verify() throws ReflectiveOperationException {
            for (String prefix : new String[]{"", "raknet;", "raknetl;"}) {
                for (String destination : new String[]{"192.0.2.1:25570", "[2001:db8::1]:25571", "fixture.test:25572", "192.0.2.1", "[2001:db8::1]", "fixture.test"}) {
                    Object parsed = parse.invoke(null, prefix + destination);
                    checkConnection(parsed, prefix, destination);
                    checkPing(prefix + destination, destination);
                }
            }
        }

        /**
         * Checks Fast IP Ping alone to distinguish an upstream failure from interaction with Raknetify.
         *
         * @throws ReflectiveOperationException if the native resolver cannot be exercised
         */
        private void verifyBaseline() throws ReflectiveOperationException {
            require(!FabricLoader.getInstance().isModLoaded("raknetify"), "Baseline requires Raknetify to be absent");
            for (String destination : new String[]{"192.0.2.1:25570", "[2001:db8::1]:25571", "fixture.test:25572"}) {
                checkResolved(parse.invoke(null, destination), destination);
            }
            System.out.println("PASS standalone resolver baseline");
        }

        /**
         * Constructs the actual connection thread without starting it and checks its captured address and flags.
         *
         * @param parsed native parsed address retaining the user's original prefix
         * @param prefix expected transport-selection prefix
         * @param destination expected destination without transport syntax
         * @throws ReflectiveOperationException if a native connection-thread constructor or captured field changed
         */
        private void checkConnection(Object parsed, String prefix, String destination) throws ReflectiveOperationException {
            Constructor<?> constructor = connector.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object[] arguments = new Object[constructor.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) arguments[i] = argument(constructor.getParameterTypes()[i], parsed);
            Object thread = constructor.newInstance(arguments);
            Field captured = Arrays.stream(connector.getDeclaredFields()).filter(f -> f.getType() == address).findFirst().orElseThrow();
            captured.setAccessible(true);
            require(booleanField(thread, "isRaknet") == !prefix.isEmpty(), "Wrong connection transport");
            require(booleanField(thread, "raknetLargeMTU") == prefix.equals("raknetl;"), "Wrong MTU selection");
            checkResolved(captured.get(thread), destination);
            System.out.println("PASS connect address " + prefix + destination);
        }

        /**
         * Invokes the transformed server-list parsing hook with the native parser as its original operation.
         *
         * @param input saved server-list address, including any transport prefix
         * @param destination expected address passed to the resolver
         * @throws ReflectiveOperationException if the transformed ping hook or native parser cannot be invoked
         */
        private void checkPing(String input, String destination) throws ReflectiveOperationException {
            Class<?> pinger = type("net.minecraft.class_644", "net.minecraft.client.multiplayer.ServerStatusPinger");
            Method hook = Arrays.stream(pinger.getDeclaredMethods()).filter(m -> m.getName().contains("modifyRaknetAddress")).findFirst().orElseThrow();
            hook.setAccessible(true);
            Class<?> operation = hook.getParameterTypes()[1];
            Object original = Proxy.newProxyInstance(operation.getClassLoader(), new Class<?>[]{operation},
                    (proxy, method, args) -> parse.invoke(null, ((Object[]) args[0])[0]));
            Object result = hook.invoke(pinger.getDeclaredConstructor().newInstance(), input, original);
            checkResolved(result, destination);
            System.out.println("PASS ping address " + input);
        }

        /**
         * Requires exact destination bytes and port, with literal hostnames already populated before reverse lookup.
         *
         * @param input unprefixed native address from a production client hook
         * @param destination original unprefixed text used to establish expected bytes and port
         * @throws ReflectiveOperationException if a native resolver or socket-address accessor changed
         */
        private void checkResolved(Object input, String destination) throws ReflectiveOperationException {
            Object expected = parse.invoke(null, destination);
            require(host.invoke(input).equals(host.invoke(expected)), "Hostname changed");
            Optional<?> result = (Optional<?>) resolver.getMethod("resolve", address).invoke(defaultResolver, input);
            Object value = result.orElseThrow();
            InetSocketAddress socket = (InetSocketAddress) resolved.getMethod(modern ? "asInetSocketAddress" : "method_36902").invoke(value);
            int explicitPort = java.net.URI.create("minecraft://" + destination).getPort();
            int expectedPort = explicitPort < 0 ? 25565 : explicitPort;
            require((int) port.invoke(input) == expectedPort, "Parsed port changed");
            require(socket.getPort() == expectedPort, "Resolved port changed");
            String expectedHost = (String) host.invoke(expected);
            InetAddress actual = socket.getAddress();
            byte[] expectedBytes = expectedHost.contains(":")
                    ? java.util.HexFormat.of().parseHex("20010db8000000000000000000000001")
                    : new byte[]{(byte) 192, 0, 2, (byte) (expectedHost.equals("fixture.test") ? 2 : 1)};
            require(Arrays.equals(actual.getAddress(), expectedBytes), "Destination address bytes changed");
            require(!actual.toString().startsWith("/"), "Literal address still permits reverse DNS");
            if (expectedHost.equals("fixture.test")) {
                require(actual.getHostName().equals(expectedHost), "Named host was replaced");
            } else {
                require(actual.getHostName().equals(actual.getHostAddress()), "Literal hostname was not pinned");
            }
        }

        /**
         * Supplies only the captured address and inert constructor values without creating a client session.
         *
         * @param type constructor parameter type
         * @param parsed captured native address
         * @return argument accepted by the vanilla thread constructor
         * @throws ReflectiveOperationException if the inert parent screen cannot be constructed
         */
        private Object argument(Class<?> type, Object parsed) throws ReflectiveOperationException {
            if (type == connector.getEnclosingClass()) {
                // The enclosing screen is only captured, never run. Avoid its graphics-dependent constructor.
                Class<?> allocator = Class.forName("sun.misc.Unsafe");
                Field instance = allocator.getDeclaredField("theUnsafe");
                instance.setAccessible(true);
                return allocator.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type);
            }
            if (type == address) return parsed;
            if (type == String.class) return "Address test";
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            return null;
        }

        /**
         * Reads the actual flags injected into the native connection thread by Raknetify.
         *
         * @param target constructed connection thread
         * @param suffix stable portion of the unique mixin field name
         * @return selected flag value
         * @throws ReflectiveOperationException if the injected flag cannot be read
         */
        private boolean booleanField(Object target, String suffix) throws ReflectiveOperationException {
            Field field = Arrays.stream(connector.getDeclaredFields()).filter(f -> f.getName().endsWith(suffix)).findFirst().orElseThrow();
            field.setAccessible(true);
            return field.getBoolean(target);
        }

        /**
         * Loads a real Minecraft client class through Fabric's active transforming class loader.
         *
         * @param intermediary stable binary name in obfuscated releases
         * @param official unobfuscated binary name in modern releases
         * @return transformed runtime class
         * @throws IllegalStateException if the expected native class is missing
         */
        private Class<?> type(String intermediary, String official) {
            try {
                return Class.forName(modern ? official : intermediary);
            } catch (ClassNotFoundException failure) {
                throw new IllegalStateException("Missing native address class", failure);
            }
        }

        /**
         * Fails the fixture process on a behavior mismatch without depending on enabled Java assertions.
         *
         * @param condition required compatibility property
         * @param message diagnostic explaining the violated property
         * @throws IllegalStateException if the compatibility property does not hold
         */
        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalStateException(message);
        }
    }
}
