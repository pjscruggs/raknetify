package raknetify.quality

import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ConstructorNode
import org.codehaus.groovy.ast.ClassNode
import org.codenarc.rule.AbstractAstVisitor

/** Checks source declarations while leaving compiler-created Groovy members out of source policy. */
class DocumentationVisitor extends AbstractAstVisitor {
    /**
     * Checks explicit types while the standard class rule checks script headers.
     *
     * @param type declared class or compiler-created script wrapper
     */
    @Override
    void visitClassEx(ClassNode type) {
        if (!type.script) { requireDocumentation(type) }
    }

    /**
     * Requires documentation on each explicit source constructor.
     *
     * @param constructor declared constructor or compiler-created default constructor
     */
    @Override
    void visitConstructor(ConstructorNode constructor) {
        requireDocumentation(constructor)
        super.visitConstructor(constructor)
    }
    /**
     * Requires a Javadoc comment on each explicitly declared method or constructor.
     *
     * @param method callable to check, including private helpers and overrides
     */
    @Override
    void visitMethodEx(MethodNode method) {
        requireDocumentation(method)
    }

    /**
     * Requires documentation for fields explicitly declared inside classes.
     *
     * @param field source field. Compiler-generated backing state is ignored
     */
    @Override
    void visitField(FieldNode field) {
        requireDocumentation(field)
        super.visitField(field)
    }

    /**
     * Reports a declaration without an attached documentation comment.
     *
     * @param node source declaration whose preceding lines may contain annotations
     */
    private void requireDocumentation(ASTNode node) {
        if (node.lineNumber < 1 || node.synthetic) { return }
        def before = sourceCode.lines.take(node.lineNumber - 1).join('\n')
        def comment = before =~ /(?s)\/\*\*((?:(?!\*\/).)*)\*\/\s*(?:@[\w.]+(?:\([^\n]*\))?\s*)*$/
        if (!comment.find() || !hasDescription(comment.group(1))) {
            addViolation(node, 'Every declared type, method, constructor, and field requires descriptive documentation.')
        }
    }

    /**
     * Rejects empty comments and tag-only blocks that provide no declaration summary.
     *
     * @param body documentation text between its opening and closing delimiters
     * @return whether prose appears before any block tags
     */
    private static boolean hasDescription(String body) {
        def normalized = body.replaceAll(/(?m)^\s*\*\s?/, '').trim()
        return !normalized.empty && !normalized.startsWith('@')
    }
}
