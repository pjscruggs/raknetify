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

package com.ishland.raknetify.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

/** Keeps reusable protocol code independent of Minecraft versions and platform entry points. */
class ArchitectureTest {
    /** Actual production bytecode, excluding fixtures that intentionally use platform protocols. */
    private static final JavaClasses PRODUCTION =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(
                            "com.ishland.raknetify.common",
                            "com.ishland.raknetify.api",
                            "network.ycc.raknet");

    /**
     * Shared transport must compile and operate without loading any game's or plugin platform's
     * types.
     */
    @Test
    void sharedProtocolDoesNotDependOnPlatformCode() {
        noClasses()
                .that()
                .resideInAnyPackage("com.ishland.raknetify.common..", "network.ycc.raknet..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "net.minecraft..",
                        "net.fabricmc..",
                        "net.minecraftforge..",
                        "net.neoforged..",
                        "org.bukkit..",
                        "com.velocitypowered..",
                        "net.md_5.bungee..",
                        "com.ishland.raknetify.fabric..",
                        "com.ishland.raknetify.forge..",
                        "com.ishland.raknetify.neoforge..",
                        "com.ishland.raknetify.spigot..",
                        "com.ishland.raknetify.velocity..",
                        "com.ishland.raknetify.bungee..")
                .check(PRODUCTION);
    }

    /**
     * Mods declaring payload order should not acquire a transport, loader or game dependency
     * through the API.
     */
    @Test
    void orderingApiDependsOnlyOnJdkAndContractAnnotations() {
        classes()
                .that()
                .resideInAPackage("com.ishland.raknetify.api..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..", "com.ishland.raknetify.api..", "org.jspecify.annotations..")
                .check(PRODUCTION);
    }

    /**
     * Packet encoding and reusable transport primitives cannot depend on the mod that consumes
     * them.
     */
    @Test
    void transportLibraryDoesNotDependOnModImplementation() {
        noClasses()
                .that()
                .resideInAPackage("network.ycc.raknet..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.ishland.raknetify..")
                .check(PRODUCTION);
    }
}
