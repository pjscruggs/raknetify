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

package com.ishland.raknetify.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Declares which vanilla traffic group must remain ordered with a mod's custom payloads.
 * The available groups are {@code ui} for world-independent menus and interactions,
 * {@code entity} for entity spawn, removal, and movement, {@code world_interaction} for
 * inventories and other world-dependent interactions, {@code effects} for sound and particles,
 * and {@code chunk} for terrain, block, and lighting updates. All groups retain reliable ordered delivery.
 *
 * <p>Fabric mods can implement the following provider using only JDK types and list its class under
 * the {@code raknetify:packet_channels} key in their {@code fabric.mod.json} entrypoints:</p>
 * <pre>{@code
 * public final class OrderingProvider implements Consumer<BiConsumer<String, String>> {
 *     public void accept(BiConsumer<String, String> register) {
 *         register.accept("example:entity_path", "entity");
 *     }
 * }
 * }</pre>
 * <p>Fabric does not invoke this entrypoint when Raknetify2 is absent. Providers should only declare
 * identifiers and groups, without depending on another mod's initialization having finished.
 * Plugins can call {@link #register(String, String)} during their own initialization instead.</p>
 *
 * <p>Each new RakNet connection advertises its declarations before Minecraft traffic. Updated servers
 * and proxies use peer declarations for custom payloads that have no local rule. This lets a modded
 * client describe its requirements to a proxy whose backend does not run Raknetify2. Local declarations
 * take precedence, and rules never affect another connection or classify ordinary vanilla packets.
 * Older peers ignore the advertisement and retain their existing routing behavior.</p>
 */
public final class PacketOrdering {

    /** Maximum declarations sent by one endpoint during connection setup. */
    public static final int MAX_ENTRIES = 128;
    /** Maximum ASCII identifier length accepted by the declaration protocol. */
    public static final int MAX_IDENTIFIER_LENGTH = 255;
    /** Namespaced custom-payload syntax shared by the supported Minecraft versions. */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    /** Named groups retain their existing vanilla ordering-channel assignments. */
    private static final Map<String, Integer> GROUPS = Map.of(
            "ui", 1, "entity", 2, "world_interaction", 3, "effects", 4, "chunk", 7);
    /** Startup declarations copied into each new connection instead of changing active connections. */
    private static final Map<String, Integer> RULES = new LinkedHashMap<>(Map.of(
            "porting_lib:extra_data_entity_spawn", 2, "porting_lib:extra_entity_spawn_data", 2));

    /** Prevents construction of the process-wide registration API. */
    private PacketOrdering() {
    }

    /**
     * Registers a payload during mod or plugin initialization. Repeating the same rule is harmless.
     * Fabric mods can expose a {@code raknetify:packet_channels} entrypoint implementing
     * {@code Consumer<BiConsumer<String, String>>} and call the supplied registrar instead.
     * The named group keeps reliable ordered delivery. Existing connections retain their snapshot.
     *
     * @param identifier namespaced mod payload identifier, excluding Minecraft's reserved namespace
     * @param group one of ui, entity, world_interaction, effects, or chunk
     * @throws IllegalArgumentException if the identifier, group, duplicate rule, or entry count is invalid
     */
    public static synchronized void register(String identifier, String group) {
        Integer channel = GROUPS.get(Objects.requireNonNull(group));
        if (channel == null) throw new IllegalArgumentException("Unknown packet ordering group: " + group);
        validate(identifier, channel);
        Integer previous = RULES.get(identifier);
        if (previous != null && !previous.equals(channel)) {
            throw new IllegalArgumentException("Conflicting packet ordering for " + identifier);
        }
        if (previous == null && RULES.size() >= MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many packet ordering declarations");
        }
        RULES.put(identifier, channel);
    }

    /**
     * Returns an immutable snapshot for a newly initialized transport connection.
     *
     * @return registered custom payloads mapped to reliable ordering channels
     */
    public static synchronized Map<String, Integer> snapshot() {
        return Map.copyOf(RULES);
    }

    /**
     * Checks local and received declarations against the same identifier and group restrictions.
     *
     * @param identifier complete custom-payload identifier
     * @param channel existing reliable ordering channel associated with a named group
     * @throws IllegalArgumentException if the declaration refers to a reserved name or unsupported group
     */
    public static void validate(String identifier, int channel) {
        Objects.requireNonNull(identifier);
        if (identifier.length() > MAX_IDENTIFIER_LENGTH || !IDENTIFIER.matcher(identifier).matches()
                || identifier.startsWith("minecraft:")) {
            throw new IllegalArgumentException("Invalid mod payload identifier: " + identifier);
        }
        if (!GROUPS.containsValue(channel)) {
            throw new IllegalArgumentException("Invalid packet ordering channel: " + channel);
        }
    }
}
