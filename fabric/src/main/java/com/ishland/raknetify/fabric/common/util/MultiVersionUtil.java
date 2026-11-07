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

package com.ishland.raknetify.fabric.common.util;

import com.google.common.collect.ImmutableList;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.state.NetworkState;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Resolves legacy Minecraft members once through mappings and structural lookup. Optional handles
 * are null when their API is absent. Callers must select the appropriate version path before
 * invoking a version-specific handle.
 */
public class MultiVersionUtil {

    /** Stable mapping namespace used to locate obfuscated legacy Minecraft members. */
    private static final String INTERMEDIARY = "intermediary";

    /** Intermediary name of the protocol wrapper introduced around the legacy packet handler. */
    private static final String CLASSNAME_NetworkStatePacketHandler =
            "net.minecraft.class_2539$class_8698";

    /** Intermediary name of the legacy handler containing class-to-packet-ID mappings. */
    private static final String CLASSNAME_NetworkState$InternalPacketHandler =
            "net.minecraft.class_2539$class_4532";

    /** Intermediary name of the protocol factory whose older bind method lacks context. */
    private static final String CLASSNAME_NetworkState$Factory =
            "net.minecraft.class_9127$class_9128";

    /** Intermediary name of the holder for clientbound and serverbound play factories. */
    private static final String CLASSNAME_PlayStateFactories = "net.minecraft.class_9095";

    /** Intermediary name used to resolve legacy hunger-state serialization methods. */
    private static final String CLASSNAME_HungerManager = "net.minecraft.class_1702";

    /** Intermediary name used to resolve the older entity world accessor. */
    private static final String CLASSNAME_Entity = "net.minecraft.class_1297";

    /** Required connection field located structurally in the server play listener hierarchy. */
    public static final VarHandle ServerPlayNetworkHandler$connection;

    /** Client play listener connection field, or null on a dedicated server. */
    public static final @Nullable VarHandle ClientPlayNetworkHandler$connection;

    /**
     * Optional vanilla encrypted-state flag, absent on versions that no longer expose that field.
     */
    private static final @Nullable VarHandle ClientConnection$encrypted;

    /** Legacy player ping field used before latency moved to the common packet listener. */
    public static final @Nullable VarHandle ServerPlayerEntity$pingMillis1_20_1;

    /** Optional protocol-wrapper class used by the legacy backing-handler layout. */
    public static final @Nullable Class<?> clazzNetworkStatePacketHandler;

    /** Optional wrapper field leading to the underlying class-to-ID packet handler. */
    public static final @Nullable VarHandle NetworkStatePacketHandler$backingHandler1_20_2;

    /** Optional older protocol factory bind method used when inspecting registration order. */
    public static final @Nullable MethodHandle NetworkState$Factory$bind1_20_5;

    /** Optional runtime holder of the static play protocol factories. */
    public static final @Nullable Class<?> clazzPlayStateFactories;

    /** Optional static serverbound play factory used by the older codec-registration API. */
    public static final @Nullable VarHandle PlayStateFactories$C2S1_20_5;

    /** Optional static clientbound play factory used by the older codec-registration API. */
    public static final @Nullable VarHandle PlayStateFactories$S2C1_20_5;

    /**
     * Optional legacy hunger-state serializer used when constructing the client hunger replacement.
     */
    public static final @Nullable MethodHandle HungerManager$writeNbt1_21_5;

    /** Optional legacy hunger-state reader used to initialize the client hunger replacement. */
    public static final @Nullable MethodHandle HungerManager$readNbt1_21_5;

    /**
     * Optional older entity world getter used before the compile target's accessor became
     * available.
     */
    public static final @Nullable MethodHandle Entity$getEntity1_21_8;

    static {
        try {
            final MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();

            final Field encrypted =
                    getOrNull(
                            () ->
                                    ClientConnection.class.getDeclaredField(
                                            resolver.mapFieldName(
                                                    INTERMEDIARY,
                                                    "net.minecraft.class_2535",
                                                    "field_11647",
                                                    "Z")),
                            List.of(NoSuchFieldException.class));
            ClientConnection$encrypted =
                    encrypted == null
                            ? null
                            : MethodHandles.privateLookupIn(
                                            ClientConnection.class, MethodHandles.lookup())
                                    .unreflectVarHandle(encrypted);

            {
                final List<Field> connFields =
                        tryLocateFields(
                                ServerPlayNetworkHandler.class, ClientConnection.class, false);
                if (connFields.size() != 1) {
                    throw new IllegalStateException(
                            "Ambiguous fields for ClientConnection in ServerPlayNetworkHandler:"
                                    + " found "
                                    + Arrays.toString(connFields.toArray()));
                }
                if (connFields.isEmpty()) {
                    throw new IllegalStateException(
                            "Cannot find field for ClientConnection in ServerPlayNetworkHandler");
                }
                final Field connField = connFields.get(0);
                connField.setAccessible(true);
                ServerPlayNetworkHandler$connection =
                        MethodHandles.privateLookupIn(
                                        ServerPlayNetworkHandler.class, MethodHandles.lookup())
                                .unreflectVarHandle(connField);
            }

            if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
                final List<Field> connFields =
                        tryLocateFields(
                                ClientPlayNetworkHandler.class, ClientConnection.class, false);
                if (connFields.size() != 1) {
                    throw new IllegalStateException(
                            "Ambiguous fields for ClientConnection in ClientPlayNetworkHandler:"
                                    + " found "
                                    + Arrays.toString(connFields.toArray()));
                }
                if (connFields.isEmpty()) {
                    throw new IllegalStateException(
                            "Cannot find field for ClientConnection in ClientPlayNetworkHandler");
                }
                final Field connField = connFields.get(0);
                connField.setAccessible(true);
                ClientPlayNetworkHandler$connection =
                        MethodHandles.privateLookupIn(
                                        ClientPlayNetworkHandler.class, MethodHandles.lookup())
                                .unreflectVarHandle(connField);
            } else {
                ClientPlayNetworkHandler$connection = null;
            }

            ServerPlayerEntity$pingMillis1_20_1 = findLegacyPing(resolver);

            clazzNetworkStatePacketHandler =
                    getOrNull(
                            () ->
                                    Class.forName(
                                            resolver.mapClassName(
                                                    INTERMEDIARY,
                                                    CLASSNAME_NetworkStatePacketHandler)),
                            List.of(ClassNotFoundException.class));
            NetworkStatePacketHandler$backingHandler1_20_2 = findBackingHandler(resolver);

            NetworkState$Factory$bind1_20_5 = findFactoryBinding(resolver);

            clazzPlayStateFactories =
                    getOrNull(
                            () ->
                                    Class.forName(
                                            resolver.mapClassName(
                                                    INTERMEDIARY, CLASSNAME_PlayStateFactories),
                                            false,
                                            MultiVersionUtil.class.getClassLoader()),
                            List.of(ClassNotFoundException.class));

            PlayStateFactories$C2S1_20_5 = findServerboundFactory(resolver);

            PlayStateFactories$S2C1_20_5 = findClientboundFactory(resolver);

            HungerManager$writeNbt1_21_5 = findHungerWriter(resolver);

            HungerManager$readNbt1_21_5 = findHungerReader(resolver);

            Entity$getEntity1_21_8 = findEntityWorldGetter(resolver);

        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Resolves the player latency field used before latency moved to the common packet listener.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable VarHandle findLegacyPing(final MappingResolver resolver)
            throws IllegalAccessException {
        final Field pingMillis1_20_1 =
                getOrNull(
                        () ->
                                ServerPlayerEntity.class.getDeclaredField(
                                        resolver.mapFieldName(
                                                INTERMEDIARY,
                                                "net.minecraft.class_3222",
                                                "field_13967",
                                                "I")),
                        List.of(NoSuchFieldException.class));
        if (pingMillis1_20_1 != null) {
            pingMillis1_20_1.setAccessible(true);
            return MethodHandles.privateLookupIn(ServerPlayerEntity.class, MethodHandles.lookup())
                    .unreflectVarHandle(pingMillis1_20_1);
        } else {
            return null;
        }
    }

    /**
     * Resolves the packet-state wrapper field used to reach its packet registration table.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable VarHandle findBackingHandler(final MappingResolver resolver)
            throws IllegalAccessException {
        if (clazzNetworkStatePacketHandler != null) {
            final Field backingHandler1_20_2 =
                    getOrNull(
                            () ->
                                    clazzNetworkStatePacketHandler.getDeclaredField(
                                            resolver.mapFieldName(
                                                    INTERMEDIARY,
                                                    CLASSNAME_NetworkStatePacketHandler,
                                                    "field_45674",
                                                    "L"
                                                            + resolver.mapClassName(
                                                                    INTERMEDIARY,
                                                                    CLASSNAME_NetworkState$InternalPacketHandler)
                                                            + ";")),
                            List.of(NoSuchFieldException.class));
            if (backingHandler1_20_2 != null) {
                backingHandler1_20_2.setAccessible(true);
                return MethodHandles.privateLookupIn(
                                clazzNetworkStatePacketHandler, MethodHandles.lookup())
                        .unreflectVarHandle(backingHandler1_20_2);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Resolves the packet-state factory binding method across its named and intermediary variants.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable MethodHandle findFactoryBinding(final MappingResolver resolver)
            throws IllegalAccessException {
        Method factoryBind =
                getOrNull(
                        () ->
                                NetworkState.Unbound.class.getDeclaredMethod(
                                        "bind" /* actually not obfuscated in 1.20.6 */,
                                        Function.class),
                        List.of(NoSuchMethodException.class, NoClassDefFoundError.class));
        if (factoryBind == null) {
            factoryBind =
                    getOrNull(
                            () ->
                                    NetworkState.Unbound.class.getDeclaredMethod(
                                            resolver.mapMethodName(
                                                    INTERMEDIARY,
                                                    CLASSNAME_NetworkState$Factory,
                                                    "method_61107",
                                                    "(Ljava/util/function/Function;)Lnet/minecraft/class_9127;"),
                                            Function.class),
                            List.of(NoSuchMethodException.class, NoClassDefFoundError.class));
        }
        if (factoryBind != null) {
            factoryBind.setAccessible(true);
            return MethodHandles.privateLookupIn(NetworkState.Unbound.class, MethodHandles.lookup())
                    .unreflect(factoryBind);
        } else {
            return null;
        }
    }

    /**
     * Resolves the serverbound play-state factory in versions that expose the legacy factory
     * holder.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable VarHandle findServerboundFactory(final MappingResolver resolver)
            throws IllegalAccessException {
        if (clazzPlayStateFactories != null) {
            final Field playStateFactoriesC2S1_20_5 =
                    getOrNull(
                            () ->
                                    clazzPlayStateFactories.getDeclaredField(
                                            resolver.mapFieldName(
                                                    INTERMEDIARY,
                                                    CLASSNAME_PlayStateFactories,
                                                    "field_48172",
                                                    "L"
                                                            + CLASSNAME_NetworkState$Factory
                                                                    .replace('.', '/')
                                                            + ";")),
                            List.of(NoSuchFieldException.class));
            if (playStateFactoriesC2S1_20_5 != null) {
                playStateFactoriesC2S1_20_5.setAccessible(true);
                return MethodHandles.privateLookupIn(
                                clazzPlayStateFactories, MethodHandles.lookup())
                        .unreflectVarHandle(playStateFactoriesC2S1_20_5);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Resolves the clientbound play-state factory in versions that expose the legacy factory
     * holder.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable VarHandle findClientboundFactory(final MappingResolver resolver)
            throws IllegalAccessException {
        if (clazzPlayStateFactories != null) {
            final Field playStateFactoriesS2C1_20_5 =
                    getOrNull(
                            () ->
                                    clazzPlayStateFactories.getDeclaredField(
                                            resolver.mapFieldName(
                                                    INTERMEDIARY,
                                                    CLASSNAME_PlayStateFactories,
                                                    "field_48173",
                                                    "L"
                                                            + CLASSNAME_NetworkState$Factory
                                                                    .replace('.', '/')
                                                            + ";")),
                            List.of(NoSuchFieldException.class));
            if (playStateFactoriesS2C1_20_5 != null) {
                playStateFactoriesS2C1_20_5.setAccessible(true);
                return MethodHandles.privateLookupIn(
                                clazzPlayStateFactories, MethodHandles.lookup())
                        .unreflectVarHandle(playStateFactoriesS2C1_20_5);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Resolves the hunger-state NBT writer used before value input and output replaced NBT access.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable MethodHandle findHungerWriter(final MappingResolver resolver)
            throws IllegalAccessException {
        final Method writeNbt1_21_5 =
                getOrNull(
                        () ->
                                HungerManager.class.getDeclaredMethod(
                                        resolver.mapMethodName(
                                                INTERMEDIARY,
                                                CLASSNAME_HungerManager,
                                                "method_7582",
                                                "(Lnet/minecraft/class_2487;)V"),
                                        NbtCompound.class),
                        List.of(NoSuchMethodException.class));
        if (writeNbt1_21_5 != null) {
            writeNbt1_21_5.setAccessible(true);
            return MethodHandles.privateLookupIn(HungerManager.class, MethodHandles.lookup())
                    .unreflect(writeNbt1_21_5);
        } else {
            return null;
        }
    }

    /**
     * Resolves the hunger-state NBT reader used before value input and output replaced NBT access.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable MethodHandle findHungerReader(final MappingResolver resolver)
            throws IllegalAccessException {
        final Method readNbt1_21_5 =
                getOrNull(
                        () ->
                                HungerManager.class.getDeclaredMethod(
                                        resolver.mapMethodName(
                                                INTERMEDIARY,
                                                CLASSNAME_HungerManager,
                                                "method_7584",
                                                "(Lnet/minecraft/class_2487;)V"),
                                        NbtCompound.class),
                        List.of(NoSuchMethodException.class));
        if (readNbt1_21_5 != null) {
            readNbt1_21_5.setAccessible(true);
            return MethodHandles.privateLookupIn(HungerManager.class, MethodHandles.lookup())
                    .unreflect(readNbt1_21_5);
        } else {
            return null;
        }
    }

    /**
     * Resolves the legacy entity world getter used by version-dependent player checks.
     *
     * @param resolver mappings for the running Minecraft version
     * @return handle to the legacy member, or null when that API is absent
     * @throws IllegalAccessException if an existing member cannot be made accessible
     */
    private static @Nullable MethodHandle findEntityWorldGetter(final MappingResolver resolver)
            throws IllegalAccessException {
        final Method getEntity1_21_8 =
                getOrNull(
                        () ->
                                Entity.class.getDeclaredMethod(
                                        resolver.mapFieldName(
                                                INTERMEDIARY,
                                                CLASSNAME_Entity,
                                                "method_37908",
                                                "()Lnet/minecraft/class_1937;")),
                        List.of(NoSuchMethodException.class));
        if (getEntity1_21_8 != null) {
            getEntity1_21_8.setAccessible(true);
            return MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup())
                    .unreflect(getEntity1_21_8);
        } else {
            return null;
        }
    }

    /**
     * Searches a class hierarchy for fields of an exact type, optionally excluding Mixin-added
     * fields. Superclass fields appear first so ambiguity can be detected before choosing a
     * connection field.
     *
     * @param clazz class whose declared fields and superclass chain are inspected
     * @param fieldType exact field type to match
     * @param includeMixinMerged whether fields marked as merged by Mixin are eligible
     * @return all matching fields without changing their accessibility
     */
    private static List<Field> tryLocateFields(
            final Class<?> clazz, final Class<?> fieldType, final boolean includeMixinMerged) {
        final ImmutableList.Builder<Field> builder = ImmutableList.builder();
        final Class<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            builder.addAll(tryLocateFields(superclass, fieldType, includeMixinMerged));
        }
        __outerloop:
        for (Field field : clazz.getDeclaredFields()) {
            if (!includeMixinMerged) {
                for (Annotation annotation : field.getDeclaredAnnotations()) {
                    if (annotation.annotationType() == MixinMerged.class) {
                        continue __outerloop;
                    }
                }
            }

            if (field.getType() == fieldType) {
                builder.add(field);
            }
        }
        return builder.build();
    }

    /**
     * Requires a reflective hook only after the caller has selected its version-specific path.
     * Optional lookups remain absent on other releases without breaking class initialization.
     *
     * @param feature optional class, field or method resolved for the running game
     * @param description compatibility hook required by the selected path
     * @param <T> reflected feature type preserved for the caller
     * @return available feature with its original type
     * @throws IllegalStateException if the selected version path lacks its required feature
     */
    public static <T> T requireCompatible(final @Nullable T feature, final String description) {
        if (feature == null) {
            throw new IllegalStateException("Minecraft runtime does not provide " + description);
        }
        return feature;
    }

    /** Forces compatibility-member resolution before normal Minecraft initialization proceeds. */
    public static void init() {}

    /**
     * Updates vanilla's encrypted-state flag on versions where that flag still exists.
     *
     * @param connection Minecraft connection whose displayed encryption state is being updated
     * @param encrypted whether frame encryption has been enabled
     */
    public static void setEncrypted(final ClientConnection connection, final boolean encrypted) {
        if (ClientConnection$encrypted != null) {
            ClientConnection$encrypted.set(connection, encrypted);
        }
    }

    /**
     * Attempts to load an optional runtime class by its already-resolved binary name.
     *
     * @param name fully qualified runtime class name
     * @return loaded class, or null when the class is absent
     */
    public static @Nullable Class<?> tryLocateClass(final String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /**
     * Treats specified lookup failures as an absent optional API while propagating other failures.
     *
     * @param <T> lookup result type
     * @param supplier lookup operation that may throw reflection or linkage failures
     * @param catchExceptions exception types that mean the optional member is unavailable
     * @return lookup result, or null when an allowed absence exception occurs
     * @throws RuntimeException if the lookup fails with an unlisted exception type
     */
    private static <T> @Nullable T getOrNull(
            final SupplierThrowable<T> supplier,
            final List<Class<? extends Throwable>> catchExceptions) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            for (Class<? extends Throwable> catchException : catchExceptions) {
                if (catchException.isInstance(t)) {
                    return null;
                }
            }

            throw new RuntimeException(t);
        }
    }

    /**
     * Represents a compatibility lookup that can expose checked reflection failures.
     *
     * @param <T> result supplied by the lookup
     */
    @FunctionalInterface
    private interface SupplierThrowable<T> {
        /**
         * Runs the compatibility lookup without narrowing its possible failure types.
         *
         * @return located class, field, method, or other lookup result
         * @throws Throwable if the lookup cannot complete
         */
        T get() throws Throwable;
    }
}
