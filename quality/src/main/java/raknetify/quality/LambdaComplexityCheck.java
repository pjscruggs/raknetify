package raknetify.quality;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.util.Set;

/** Checks lambdas independently, including lambdas declared in field initializers. */
public final class LambdaComplexityCheck extends AbstractCheck {
  /** Creates an independent lambda check with the mandatory ten-path ceiling. */
  public LambdaComplexityCheck() {}

  /** Hard ceiling shared with the ordinary Checkstyle complexity check. */
  private static final int LIMIT = 10;

  /** Decision tokens counted by Checkstyle when switch cases are counted separately. */
  private static final Set<Integer> DECISIONS =
      Set.of(
          TokenTypes.LITERAL_WHILE,
          TokenTypes.LITERAL_DO,
          TokenTypes.LITERAL_FOR,
          TokenTypes.LITERAL_IF,
          TokenTypes.LITERAL_CASE,
          TokenTypes.LITERAL_CATCH,
          TokenTypes.QUESTION,
          TokenTypes.LAND,
          TokenTypes.LOR,
          TokenTypes.LITERAL_WHEN);

  /**
   * Subscribes to every lambda, including field initializers.
   *
   * @return the lambda declaration token
   */
  @Override
  public int[] getDefaultTokens() {
    return new int[] {TokenTypes.LAMBDA};
  }

  /**
   * Restricts configuration to the independently measured lambda token.
   *
   * @return the supported lambda token
   */
  @Override
  public int[] getAcceptableTokens() {
    return getDefaultTokens();
  }

  /**
   * Requires lambda analysis so a token override cannot disable the callback limit.
   *
   * @return the mandatory lambda token
   */
  @Override
  public int[] getRequiredTokens() {
    return getDefaultTokens();
  }

  /**
   * Reports each lambda exceeding the same ten-path ceiling as methods and constructors.
   *
   * @param ast lambda whose descendant decisions are counted
   */
  @Override
  public void visitToken(DetailAST ast) {
    int complexity = 1 + decisions(ast);
    if (complexity > LIMIT) {
      log(ast, "Lambda cyclomatic complexity is {0}. Maximum allowed is {1}.", complexity, LIMIT);
    }
  }

  /**
   * Counts decisions in a lambda subtree using the same tokens as the method check.
   *
   * @param node root whose descendants are inspected
   * @return number of conditional branches, loops, guards, and short-circuit operators
   */
  private static int decisions(DetailAST node) {
    int count = 0;
    for (DetailAST child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (DECISIONS.contains(child.getType())) {
        count++;
      }
      count += decisions(child);
    }
    return count;
  }
}
