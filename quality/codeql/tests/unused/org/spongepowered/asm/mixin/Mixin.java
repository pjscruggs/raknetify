package org.spongepowered.asm.mixin;

/** Declares the target class for an isolated transformation-signature analysis fixture. */
public @interface Mixin {
    /**
     * Identifies the target without implementing any bytecode transformation.
     *
     * @return class whose callbacks the fixture models
     */
    Class<?> value();
}
