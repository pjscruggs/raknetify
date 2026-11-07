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

import static org.junit.jupiter.api.Assertions.*;

import com.ishland.raknetify.velocity.fixture.IsolatedMarker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/** Checks repeated class resolution, dependency isolation and parent initialization boundaries. */
class RaknetifyClassLoaderTest {

    /**
     * Repeated lookups reuse one local class identity instead of attempting another definition.
     *
     * @throws IOException if the test wrapper cannot be closed
     * @throws ClassNotFoundException if the compiled marker cannot be loaded
     */
    @Test
    void repeatedLookupsPreserveIsolatedIdentity() throws IOException, ClassNotFoundException {
        try (RaknetifyClassLoader loader =
                new RaknetifyClassLoader(
                        new URL[] {fixtureLocation()}, getClass().getClassLoader())) {
            Class<?> first = loader.loadClass(IsolatedMarker.class.getName());
            assertNotSame(IsolatedMarker.class, first);
            assertSame(loader, first.getClassLoader());
            assertSame(first, loader.loadClass(IsolatedMarker.class.getName()));
            assertSame(first, loader.loadClass(IsolatedMarker.class.getName(), true));
            assertSame(String.class, loader.loadClass("java.lang.String"));
        }
    }

    /**
     * Loading a parent-owned class leaves its static initializer for the first active use.
     *
     * @throws IOException if a fixture class loader cannot be closed
     * @throws ClassNotFoundException if the parent fixture cannot be found
     */
    @Test
    @ResourceLock("raknetify.test.parent.initialized")
    void parentLookupDoesNotInitializeClass() throws IOException, ClassNotFoundException {
        String previous = System.getProperty("raknetify.test.parent.initialized");
        System.clearProperty("raknetify.test.parent.initialized");
        try (URLClassLoader parent =
                        new URLClassLoader(
                                new URL[] {fixtureLocation()},
                                ClassLoader.getPlatformClassLoader());
                RaknetifyClassLoader loader = new RaknetifyClassLoader(new URL[0], parent)) {
            Class<?> loaded = loader.loadClass("org.example.raknetify.ParentMarker", true);
            assertSame(parent, loaded.getClassLoader());
            assertNull(System.getProperty("raknetify.test.parent.initialized"));
            assertSame(loaded, Class.forName(loaded.getName(), true, parent));
            assertEquals("true", System.getProperty("raknetify.test.parent.initialized"));
        } finally {
            if (previous == null) {
                System.clearProperty("raknetify.test.parent.initialized");
            } else {
                System.setProperty("raknetify.test.parent.initialized", previous);
            }
        }
    }

    /**
     * An absent isolated dependency fails explicitly rather than borrowing a different parent copy.
     *
     * @throws IOException if the wrapper cannot be closed
     */
    @Test
    void missingIsolatedClassDoesNotFallBackToParent() throws IOException {
        try (RaknetifyClassLoader loader =
                new RaknetifyClassLoader(new URL[0], getClass().getClassLoader())) {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> loader.loadClass(IsolatedMarker.class.getName()));
        }
    }

    /**
     * A partially bundled parent Fastutil cannot supply classes for the plugin's coherent copy.
     *
     * @throws IOException if the isolated dependency archive cannot be closed
     * @throws ReflectiveOperationException if the real bundled list cannot be loaded or constructed
     */
    @Test
    void bundledFastutilDoesNotBorrowIncompleteParent()
            throws IOException, ReflectiveOperationException {
        final URL archive =
                it.unimi.dsi.fastutil.ints.IntList.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation();
        try (RaknetifyClassLoader loader =
                new RaknetifyClassLoader(
                        new URL[] {archive},
                        new IncompleteFastutilParent(getClass().getClassLoader()))) {
            final Class<?> type = loader.loadClass("it.unimi.dsi.fastutil.ints.IntArrayList");
            final Object instance = type.getConstructor().newInstance();
            assertSame(loader, type.getClassLoader());
            final Class<?> listApi = loader.loadClass("it.unimi.dsi.fastutil.ints.IntList");
            assertSame(loader, listApi.getClassLoader());
            assertTrue(listApi.isInstance(instance));
            type.getMethod("add", int.class).invoke(instance, 42);
            assertEquals(42, type.getMethod("getInt", int.class).invoke(instance, 0));
        }
    }

    /** Models a proxy whose selected Fastutil classes cannot resolve their omitted dependencies. */
    private static final class IncompleteFastutilParent extends ClassLoader {
        /**
         * Shares normal platform classes while rejecting Fastutil use through the parent.
         *
         * @param parent test runtime supplying JDK and non-Fastutil classes
         */
        private IncompleteFastutilParent(final ClassLoader parent) {
            super(parent);
        }

        /**
         * Fails exactly as a parent class with a missing Fastutil dependency would fail.
         *
         * @param name binary class name requested by the plugin
         * @param resolve whether shared classes should be linked before returning
         * @return shared class when the request does not belong to Fastutil
         * @throws ClassNotFoundException if the normal parent cannot locate a shared class
         * @throws NoClassDefFoundError if the plugin incorrectly asks its parent for Fastutil
         */
        @Override
        protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("it.unimi.dsi.fastutil.")) {
                throw new NoClassDefFoundError("Fixture parent omits Fastutil interfaces");
            }
            return super.loadClass(name, resolve);
        }
    }

    /**
     * Locates compiled fixture classes without embedding a machine-specific test directory.
     *
     * @return class-directory URL shared by the two fixture packages
     */
    private static URL fixtureLocation() {
        return IsolatedMarker.class.getProtectionDomain().getCodeSource().getLocation();
    }
}
