package org.spongepowered.asm.mixin.injection;

/** Identifies a callback whose signature is supplied by the transformation framework. */
public @interface Inject {
    /**
     * Records the target name for the signature fixture.
     *
     * @return target method name without running a transformation
     */
    String method();
}
