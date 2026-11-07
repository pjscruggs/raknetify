package raknetify.quality.fixtures;

import com.velocitypowered.api.event.Subscribe;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;

/** Distinguishes genuine unused parameters from arguments supplied by framework contracts. */
final class CallbackCases {
    /**
     * Deliberately leaves an ordinary method argument unread so the query must report it.
     *
     * @param unused redundant ordinary argument that the fixture expects to be diagnosed
     */
    private void ordinary(final int unused) {}

    /**
     * An injection annotation outside a mixin cannot exempt an ordinary unused argument.
     *
     * @param unused argument that remains invalid because the enclosing class is not a mixin
     */
    @Inject(method = "target")
    private void annotationWithoutMixin(final Object unused) {}

    /** Models a genuine transformed class while retaining a negative ordinary-helper witness. */
    @Mixin(Object.class)
    private static final class Transformed {
        /**
         * Receives a framework argument required by the target signature.
         *
         * @param callback transformation-supplied callback intentionally left unchanged
         */
        @Inject(method = "target")
        private void injected(final Object callback) {}

        /**
         * Leaves an ordinary helper argument unread despite its enclosing class being a mixin.
         *
         * @param unused redundant helper argument that must still be diagnosed
         */
        private void helper(final int unused) {}
    }

    /** Provides an optional extension callback and an ordinary static helper for comparison. */
    private interface Observer {
        /**
         * Defines an optional callback whose implementations may consume the event value.
         *
         * @param delta measurement supplied to implementations of the extension contract
         */
        default void observe(final int delta) {}

        /**
         * Leaves a static helper argument unused without any callback contract.
         *
         * @param unused ordinary static argument that must still be diagnosed
         */
        static void helper(final int unused) {}
    }

    /**
     * Receives the single event argument required by Velocity's subscription contract.
     *
     * @param event event supplied by the registry, with no cancellation requested
     */
    @Subscribe
    public void onEvent(final Object event) {}

    /**
     * Declares an invalid listener shape which cannot qualify as a framework signature.
     *
     * @param first first unused argument that must be diagnosed
     * @param second second unused argument that must also be diagnosed
     */
    @Subscribe
    public void wrongArity(final Object first, final Object second) {}
}
