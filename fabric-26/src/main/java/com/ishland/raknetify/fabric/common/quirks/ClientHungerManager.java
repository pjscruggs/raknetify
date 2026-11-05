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

package com.ishland.raknetify.fabric.common.quirks;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;

/**
 * Keeps client food consumption from modifying hunger state that should come from server updates.
 */
public class ClientHungerManager extends FoodData {

    /**
     * Copies accessible mutable public hunger fields into the client-only replacement.
     * Individual field-copy failures are logged while remaining fields are still attempted.
     *
     * @param hungerManager existing client hunger state to preserve
     * @return replacement that ignores local food-consumption increments
     */
    public static ClientHungerManager from(FoodData hungerManager) {
        ClientHungerManager clientHungerManager = new ClientHungerManager();
        for (Field field : FoodData.class.getFields()) {
            if ((field.getModifiers() & Modifier.STATIC) == 0 && (field.getModifiers() & Modifier.FINAL) == 0) {
                if (!field.trySetAccessible()) {
                    System.err.println("Failed to set field " + field.getName() + " accessible in HungerManager");
                    continue;
                }
                try {
                    Object value = field.get(hungerManager);
                    field.set(clientHungerManager, value);
                } catch (Throwable e) {
                    System.err.println("Failed to copy field " + field.getName() + " from HungerManager to ClientHungerManager");
                    e.printStackTrace();
                }
            }
        }
        return clientHungerManager;
    }

    /**
     * Ignores local food and saturation increments so server-reported hunger remains authoritative.
     *
     * @param food food-level increment intentionally ignored
     * @param saturationModifier saturation increment factor intentionally ignored
     */
    public void eat(int food, float saturationModifier) {
        // nop
    }

    /**
     * Ignores client-side consumption of a food component.
     *
     * @param foodComponent food properties whose local hunger increment is intentionally suppressed
     */
    public void eat(FoodProperties foodComponent) {
        // nop
    }

}
