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

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Objects;

/**
 * Isolates transport dependencies while sharing proxy APIs without prematurely initializing them.
 */
final class RaknetifyClassLoader extends URLClassLoader {

    /**
     * Opens the packaged plugin with a parent that supplies the proxy's shared APIs.
     *
     * @param urls plugin archive locations searched for isolated classes
     * @param parent loader that owns the original plugin instance and proxy APIs
     */
    RaknetifyClassLoader(final URL[] urls, final ClassLoader parent) {
        super("raknetify wrapper", urls, Objects.requireNonNull(parent));
    }

    /**
     * Reuses previously loaded classes before attempting any definition. Resolution links a class
     * without running its static initializer, matching the ClassLoader contract.
     *
     * @param name fully qualified binary class name
     * @param resolve whether the resulting class should be linked before returning
     * @return the single class identity selected by this loader's isolation policy
     * @throws ClassNotFoundException if neither the selected parent nor the plugin contains the
     *     class
     */
    @Override
    protected Class<?> loadClass(final String name, final boolean resolve)
            throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                loaded = loadNewClass(name);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /**
     * Delegates shared APIs without initializing them and defines isolated classes from the
     * archive. A broken parent class remains an error rather than silently loading another
     * implementation.
     *
     * @param name binary name not already loaded through this loader
     * @return class found under the established dependency isolation policy
     * @throws ClassNotFoundException if no permitted source contains the requested class
     */
    private Class<?> loadNewClass(final String name) throws ClassNotFoundException {
        if (!loadsLocally(name)) {
            try {
                return getParent().loadClass(name);
            } catch (ClassNotFoundException missingFromParent) {
                return findClass(name);
            }
        }
        return findClass(name);
    }

    /**
     * Keeps proxy APIs shared and loads the bundled Fastutil dependency as one coherent unit. Some
     * proxies contain Fastutil classes without their required interfaces. Mixing those classes with
     * the plugin's copy can fail before a class is usable or create incompatible identities.
     *
     * @param name binary class name requested by either the JVM or an integration hook
     * @return whether the plugin archive must supply this class
     */
    private static boolean loadsLocally(final String name) {
        if (name.startsWith("com.ishland.raknetify.")
                && !"com.ishland.raknetify.velocity.RaknetifyVelocityPlugin".equals(name)) {
            return true;
        }
        return name.startsWith("network.ycc.raknet.") || name.startsWith("it.unimi.dsi.fastutil.");
    }
}
