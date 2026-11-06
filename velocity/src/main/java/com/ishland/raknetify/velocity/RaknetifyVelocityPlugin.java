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

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandlerFactory;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Bootstraps Velocity integration with selected bundled dependencies isolated from proxy versions.
 */
@Plugin(id = "raknetify")
public class RaknetifyVelocityPlugin {

    /**
     * Fastutil class-name patterns that must load from the plugin instead of the proxy class
     * loader.
     */
    private static final java.util.List<Pattern> excludeRegex = new ArrayList<>();

    static {
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.*Int2ObjectLinked*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.*Int2ObjectSorted*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.*Int2ObjectAVL*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.*Int2ObjectRB*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.*Int2ObjectArray*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.IntList*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.AbstractIntList*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.IntSorted*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.AbstractIntSorted*"));
        excludeRegex.add(Pattern.compile("it.unimi.dsi.fastutil.ints.IntRB*"));
    }

    /**
     * Checks whether a dependency class must bypass parent-first lookup.
     *
     * @param name fully qualified class name requested by the wrapper
     * @return true when a bundled-dependency pattern matches
     */
    private static boolean isExcluded(final String name) {
        for (Pattern regex : excludeRegex) {
            if (regex.matcher(name).find()) {
                return true;
            }
        }
        return false;
    }

    /** Shared proxy instance exposed to the wrapped implementation after initialization. */
    public static ProxyServer PROXY;

    /** Proxy-provided logger exposed to the wrapped implementation after initialization. */
    public static Logger LOGGER;

    /** Original plugin instance retained for Velocity event and task ownership. */
    public static RaknetifyVelocityPlugin INSTANCE;

    /**
     * Dependency-isolating class loader used for the packaged plugin, or null for a direct launch.
     */
    public static URLClassLoader WRAPPER;

    /** Proxy instance injected into the original plugin by Velocity. */
    @Inject private ProxyServer proxy;

    /** Plugin logger injected by Velocity before the initialization event. */
    @Inject private Logger logger;

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
                final URLClassLoader urlClassLoader =
                        new RaknetifyURLClassLoader(
                                "raknetify wrapper",
                                new URL[] {codeSource.getLocation()},
                                RaknetifyVelocityPlugin.class.getClassLoader());
                final Class<?> launchWrapper =
                        urlClassLoader.loadClass(
                                "com.ishland.raknetify.velocity.RaknetifyVelocityLaunchWrapper");
                Preconditions.checkState(
                        launchWrapper.getClassLoader() == urlClassLoader,
                        "Not launched in wrapper");
                WRAPPER = urlClassLoader;
                launchWrapper.getMethod("launch").invoke(null);
                return;
            } catch (Throwable t) {
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

    /**
     * Shares proxy APIs with the parent while loading Raknetify internals and selected dependencies
     * locally.
     */
    private static class RaknetifyURLClassLoader extends URLClassLoader {

        /**
         * Creates a wrapper with explicit plugin URLs and a shared-API parent.
         *
         * @param urls plugin and bundled-dependency locations searched for local classes
         * @param parent class loader supplying shared proxy APIs
         */
        public RaknetifyURLClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        /**
         * Creates a wrapper with plugin URLs and the default parent class loader.
         *
         * @param urls plugin and bundled-dependency locations searched for local classes
         */
        public RaknetifyURLClassLoader(final URL[] urls) {
            super(urls);
        }

        /**
         * Creates a wrapper with a custom URL handler factory and shared-API parent.
         *
         * @param urls plugin and bundled-dependency locations searched for local classes
         * @param parent class loader supplying shared proxy APIs
         * @param factory factory providing URL protocol handlers
         */
        public RaknetifyURLClassLoader(
                final URL[] urls, final ClassLoader parent, final URLStreamHandlerFactory factory) {
            super(urls, parent, factory);
        }

        /**
         * Creates a named wrapper for identifying the isolated plugin environment in diagnostics.
         *
         * @param name diagnostic name assigned to this class loader
         * @param urls plugin and bundled-dependency locations searched for local classes
         * @param parent class loader supplying shared proxy APIs
         */
        public RaknetifyURLClassLoader(
                final String name, final URL[] urls, final ClassLoader parent) {
            super(name, urls, parent);
        }

        /**
         * Creates a named wrapper with custom URL handling for the isolated plugin environment.
         *
         * @param name diagnostic name assigned to this class loader
         * @param urls plugin and bundled-dependency locations searched for local classes
         * @param parent class loader supplying shared proxy APIs
         * @param factory factory providing URL protocol handlers
         */
        public RaknetifyURLClassLoader(
                final String name,
                final URL[] urls,
                final ClassLoader parent,
                final URLStreamHandlerFactory factory) {
            super(name, urls, parent, factory);
        }

        /**
         * Uses parent lookup for shared APIs and local lookup for isolated implementation classes.
         * Local class definition is serialized by the per-name class-loading lock.
         *
         * @param name fully qualified binary class name
         * @param resolve whether a locally defined class should be linked before returning
         * @return class from the parent or this wrapper according to the isolation rules
         * @throws ClassNotFoundException if neither the allowed parent lookup nor the plugin URLs
         *     supply the class
         */
        @Override
        protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
            if (!(name.startsWith("com.ishland.raknetify")
                            && !"com.ishland.raknetify.velocity.RaknetifyVelocityPlugin"
                                    .equals(name))
                    && !name.startsWith("network.ycc.raknet")
                    && !isExcluded(name)) {
                try {
                    return Class.forName(name, true, this.getParent());
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    // ignored, try our own loader
                }
            }

            synchronized (getClassLoadingLock(name)) {
                try {
                    final Class<?> clazz = this.findClass(name);
                    if (resolve) {
                        this.resolveClass(clazz);
                    }
                    return clazz;
                } catch (ClassNotFoundException e1) {
                    // then fail here, there's nothing more we can do
                    throw new ClassNotFoundException(name, e1);
                }
            }
        }
    }
}
