/*
 * This file is a part of the Velocity implementation of the Raknetify
 * project, licensed under GPLv3.
 *
 * Copyright (c) 2022-2025 ishland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ishland.raknetify.velocity;

import com.google.common.base.Equivalence;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.Objects;

/**
 * Bootstraps Velocity integration with selected bundled dependencies isolated from proxy versions.
 */
@Plugin(id = "raknetify")
public class RaknetifyVelocityPlugin {

    /** Shared proxy instance exposed to the wrapped implementation after initialization. */
    private static volatile @Nullable ProxyServer PROXY;

    /** Proxy-provided logger exposed to the wrapped implementation after initialization. */
    public static volatile Logger LOGGER = LoggerFactory.getLogger("Raknetify2");

    /** Original plugin instance retained for Velocity event and task ownership. */
    private static volatile @Nullable RaknetifyVelocityPlugin INSTANCE;

    /**
     * Dependency-isolating class loader used for the packaged plugin, or null for a direct launch.
     */
    public static volatile @Nullable URLClassLoader WRAPPER;

    /** Proxy instance injected into the original plugin by Velocity. */
    private final ProxyServer proxy;

    /** Plugin logger injected by Velocity before the initialization event. */
    private final Logger logger;

    /**
     * Receives required proxy services before any lifecycle callback can run.
     *
     * @param proxy running proxy that owns plugin events and tasks
     * @param logger plugin-specific logger supplied by the proxy
     */
    @Inject
    public RaknetifyVelocityPlugin(final ProxyServer proxy, final Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /**
     * Returns the initialized proxy service shared with the isolated implementation.
     *
     * @return proxy whose initialization callback has started
     * @throws NullPointerException if accessed before plugin initialization
     */
    public static ProxyServer proxy() {
        return Objects.requireNonNull(PROXY, "Raknetify2 proxy services are not initialized");
    }

    /**
     * Returns the original plugin instance used to own Velocity registrations.
     *
     * @return initialized plugin instance from the parent class loader
     * @throws NullPointerException if accessed before plugin initialization
     */
    public static RaknetifyVelocityPlugin instance() {
        return Objects.requireNonNull(INSTANCE, "Raknetify2 is not initialized");
    }

    /**
     * Publishes injected services and launches the integration through its dependency wrapper.
     * Development launches and wrapper failures fall back to the ordinary plugin class loader.
     *
     * @param e proxy initialization event triggering one-time plugin setup
     * @throws RuntimeException if the fallback integration entry point cannot be loaded or invoked
     */
    @Subscribe
    public void onProxyInit(final ProxyInitializeEvent e) {
        INSTANCE = this;
        PROXY = this.proxy;
        LOGGER = this.logger;

        final CodeSource codeSource =
                RaknetifyVelocityPlugin.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && !isDevLaunch()) {
            try {
                LOGGER.info("Bootstrapping raknetify in wrapped environment");
                launchIsolated(codeSource.getLocation());
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
                LOGGER.error(
                        "Error bootstrapping raknetify inside wrapped environment, running in"
                                + " normal environment instead",
                        t);
            }
        }

        try {
            Class.forName("com.ishland.raknetify.velocity.RaknetifyVelocityLaunchWrapper")
                    .getMethod("launch")
                    .invoke(null);
        } catch (IllegalAccessException
                | InvocationTargetException
                | NoSuchMethodException
                | ClassNotFoundException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Retains a successful wrapper and closes a failed one without hiding its original failure.
     *
     * @param archive packaged plugin location used for isolated implementation loading
     * @throws ReflectiveOperationException if the isolated launch entry point cannot run
     */
    private static void launchIsolated(final URL archive) throws ReflectiveOperationException {
        final RaknetifyClassLoader wrapper =
                new RaknetifyClassLoader(
                        new URL[] {archive}, RaknetifyVelocityPlugin.class.getClassLoader());
        try {
            final Class<?> launch =
                    wrapper.loadClass(
                            "com.ishland.raknetify.velocity.RaknetifyVelocityLaunchWrapper");
            Preconditions.checkState(
                    Equivalence.identity().equivalent(launch.getClassLoader(), wrapper),
                    "Not launched in wrapper");
            launch.getMethod("launch").invoke(null);
            WRAPPER = wrapper;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            try {
                wrapper.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Detects a development launch where the plugin is already on the system class path.
     *
     * @return true when the system class loader can load the plugin entry point
     */
    private static boolean isDevLaunch() {
        try {
            ClassLoader.getSystemClassLoader()
                    .loadClass("com.ishland.raknetify.velocity.RaknetifyVelocityPlugin");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
