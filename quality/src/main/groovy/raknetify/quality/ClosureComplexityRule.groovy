package raknetify.quality

import org.codenarc.rule.AbstractAstVisitorRule

/** Checks every closure rather than only the closure fields covered by CodeNarc. */
class ClosureComplexityRule extends AbstractAstVisitorRule {
    /** Stable rule name used in violation reports. */
    String name = 'ClosureComplexity'
    /** Severity that makes every reported violation fail the quality build. */
    int priority = 1
    /** Visitor used by CodeNarc to inspect independent closure bodies. */
    Class astVisitorClass = ClosureComplexityVisitor

    /**
     * Describes the hard callback limit without relying on external resource bundles.
     *
     * @return human-readable policy included in CodeNarc reports
     */
    @Override
    String getDescription() {
        'Every method, constructor, and independent closure must have cyclomatic complexity at most 10.'
    }
}
