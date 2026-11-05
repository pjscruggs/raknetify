package raknetify.quality

import org.codenarc.rule.AbstractAstVisitorRule

/** Requires documentation for declared Groovy methods and class fields at every visibility. */
class DocumentationRule extends AbstractAstVisitorRule {
    /** Stable rule identifier used in reports and the mandatory rule set. */
    String name = 'Documentation'
    /** Highest priority: missing documentation fails the build. */
    int priority = 1
    /** AST visitor that checks declaration-attached documentation. */
    Class astVisitorClass = DocumentationVisitor

    /**
     * Describes declaration documentation requirements in analysis reports.
     *
     * @return human-readable documentation policy
     */
    @Override
    String getDescription() {
        'Every declared method, constructor, and class field requires attached documentation.'
    }
}
