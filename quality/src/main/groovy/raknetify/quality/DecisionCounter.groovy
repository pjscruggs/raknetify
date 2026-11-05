package raknetify.quality

import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.stmt.CaseStatement
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.DoWhileStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.IfStatement
import org.codehaus.groovy.ast.stmt.WhileStatement

/** Counts control-flow decisions within one Groovy closure. */
class DecisionCounter extends CodeVisitorSupport {
    /** Initial straight-line path plus every decision encountered in this callable. */
    int complexity = 1

    /**
     * Leaves nested bodies to the closure visitor's independent measurement.
     *
     * @param expression nested callable measured separately rather than folded into its parent
     */
    @Override void visitClosureExpression(ClosureExpression expression) { }

    /**
     * Adds the conditional branch and traverses both possible bodies.
     *
     * @param statement if/else whose condition and bodies may contain further decisions
     */
    @Override void visitIfElse(IfStatement statement) { complexity++; super.visitIfElse(statement) }
    /**
     * Adds the loop decision and traverses its collection and body.
     *
     * @param statement classic or enhanced for loop within the current callable
     */
    @Override void visitForLoop(ForStatement statement) { complexity++; super.visitForLoop(statement) }
    /**
     * Adds the loop decision and traverses its condition and body.
     *
     * @param statement while loop within the current callable
     */
    @Override void visitWhileLoop(WhileStatement statement) { complexity++; super.visitWhileLoop(statement) }
    /**
     * Adds the terminating condition of a do/while loop.
     *
     * @param statement do/while loop within the current callable
     */
    @Override void visitDoWhileLoop(DoWhileStatement statement) { complexity++; super.visitDoWhileLoop(statement) }
    /**
     * Counts each switch case independently of the enclosing switch statement.
     *
     * @param statement case whose label expression and body are visited
     */
    @Override void visitCaseStatement(CaseStatement statement) { complexity++; super.visitCaseStatement(statement) }
    /**
     * Adds the exception-handling path and traverses its body.
     *
     * @param statement catch block within the current callable
     */
    @Override void visitCatchStatement(CatchStatement statement) { complexity++; super.visitCatchStatement(statement) }
    /**
     * Adds one conditional expression and visits its condition and alternatives.
     *
     * @param expression ordinary ternary expression to count
     */
    @Override void visitTernaryExpression(TernaryExpression expression) { complexity++; super.visitTernaryExpression(expression) }
    /**
     * Counts an Elvis decision once and visits its shared condition only once.
     *
     * @param expression short ternary whose true value reuses its tested expression
     */
    @Override
    void visitShortTernaryExpression(ElvisOperatorExpression expression) {
        complexity++
        expression.booleanExpression.visit(this)
        expression.falseExpression.visit(this)
    }
    /**
     * Counts short-circuit boolean operators and then visits both operands.
     *
     * @param expression binary operator that may introduce another execution path
     */
    @Override void visitBinaryExpression(BinaryExpression expression) {
        if (expression.operation.text in ['&&', '||']) { complexity++ }
        super.visitBinaryExpression(expression)
    }
}
