package raknetify.quality

import org.codenarc.source.SourceString
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** Exercises closure and documentation checks against temporary Groovy source strings. */
class QualityRulesTest {
    /**
     * Builds a closure with an exact number of independently executable decisions.
     *
     * @param count number of branch statements, excluding the initial path
     * @return source containing a top-level DSL-style closure
     */
    private String closure(int count) {
        'tasks.register("probe") { value ->\n' + ('if (value) { println value }\n' * count) + '}\n'
    }

    /** Requires ten-path closures to pass and eleven-path DSL closures to fail. */
    @Test void taskClosuresHaveAHardTenPathLimit() {
        def rule = new ClosureComplexityRule()
        assertEquals([], rule.applyTo(new SourceString(closure(9))))
        assertEquals(1, rule.applyTo(new SourceString(closure(10))).size())
    }

    /** Counts short-circuit decisions inside both methods and nested closures. */
    @Test void booleanExpressionsAndNestedClosuresAreMeasured() {
        def expression = (['value'] * 11).join(' && ')
        def rule = new ClosureComplexityRule()
        assertFalse(rule.applyTo(new SourceString('def action = { value -> ' + expression + ' }')).empty)
        assertFalse(rule.applyTo(new SourceString('boolean action(boolean value) { ' + expression + ' }')).empty)
        assertFalse(rule.applyTo(new SourceString('tasks.register("outer") { ' + closure(10) + ' }')).empty)
    }

    /** Counts Elvis expressions once rather than charging twice for their shared condition. */
    @Test void elvisExpressionsCountAsOneDecision() {
        def rule = new ClosureComplexityRule()
        assertEquals([], rule.applyTo(new SourceString('def action = { value -> ' + ('println(value ?: value)\n' * 9) + '}')))
        assertFalse(rule.applyTo(new SourceString('def action = { value -> ' + ('println(value ?: value)\n' * 10) + '}')).empty)
    }

    /** Includes constructors, initializer blocks, and top-level build script bodies. */
    @Test void constructorsAndInitializersAreMeasured() {
        def decisions = 'if (true) { println 1 }\n' * 10
        def rule = new ClosureComplexityRule()
        assertFalse(rule.applyTo(new SourceString('class Probe { Probe() { ' + decisions + '} }')).empty)
        assertFalse(rule.applyTo(new SourceString('class Probe { { ' + decisions + '} }')).empty)
        assertFalse(rule.applyTo(new SourceString('class Probe { static { ' + decisions + '} }')).empty)
        assertFalse(rule.applyTo(new SourceString(decisions)).empty)
    }

    /** Rejects undocumented private helpers and fields while accepting documented declarations. */
    @Test void declaredDocumentationIsRequiredAtEveryVisibility() {
        def rule = new DocumentationRule()
        assertFalse(rule.applyTo(new SourceString('class Probe { private void hidden() {} }')).empty)
        assertFalse(rule.applyTo(new SourceString('class Probe { private int value = 1 }')).empty)
        assertTrue(rule.applyTo(new SourceString('/** Hosts a helper. */\nclass Probe {\n/** Explains the helper. */\nprivate void helper() {}\n}')).empty)
    }

    /** Rejects empty, tag-only, and ordinary comments without borrowing an earlier declaration's documentation. */
    @Test void missingSummariesCannotReuseEarlierComments() {
        def rule = new DocumentationRule()
        assertFalse(rule.applyTo(new SourceString('/** */\nclass Probe {}')).empty)
        def prefix = '/** Hosts the helpers. */\nclass Probe {\n/** Describes the first helper. */\nvoid first() {}\n'
        ['/** */', '/**\n *\n */', '/** @return result */', '/* Ordinary comment. */', ''].each { comment ->
            assertFalse(rule.applyTo(new SourceString(prefix + comment + '\nvoid second() {}\n}')).empty)
        }
    }
}
