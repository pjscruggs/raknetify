package raknetify.quality

import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.ClassNode
import org.codenarc.rule.AbstractAstVisitor

/** Applies the hard complexity limit to task, plugin, and nested DSL closures. */
class ClosureComplexityVisitor extends AbstractAstVisitor {
    /**
     * Checks explicit constructor bodies independently from ordinary methods.
     *
     * @param constructor constructor whose nested callbacks are measured separately
     */
    @Override
    void visitConstructor(ConstructorNode constructor) {
        visitMethodEx(constructor)
        super.visitConstructor(constructor)
    }

    /**
     * Counts instance initializer blocks, which do not have a declared method node.
     *
     * @param type class whose initializer statements execute during construction
     */
    @Override
    void visitClassEx(ClassNode type) {
        def counter = new DecisionCounter()
        type.objectInitializerStatements.each { statement -> statement.visit(counter) }
        if (counter.complexity > 10) {
            addViolation(type, "Initializer cyclomatic complexity is ${counter.complexity}. Maximum allowed is 10.")
        }
    }
    /**
     * Counts method and constructor decisions using the same rule as closures.
     *
     * @param method declared callable whose nested closures are measured separately
     */
    @Override
    void visitMethodEx(MethodNode method) {
        def counter = new DecisionCounter()
        method.code?.visit(counter)
        if (counter.complexity > 10) {
            def location = method.lineNumber > 0 ? method : method.declaringClass
            addViolation(location, "Method cyclomatic complexity is ${counter.complexity}. Maximum allowed is 10.")
        }
    }
    /**
     * Reports closures whose own body exceeds ten independent execution paths.
     *
     * @param expression closure whose nested closures are visited independently
     */
    @Override
    void visitClosureExpression(ClosureExpression expression) {
        def counter = new DecisionCounter()
        expression.code.visit(counter)
        if (counter.complexity > 10) {
            addViolation(expression, "Closure cyclomatic complexity is ${counter.complexity}. Maximum allowed is 10.")
        }
        super.visitClosureExpression(expression)
    }
}
