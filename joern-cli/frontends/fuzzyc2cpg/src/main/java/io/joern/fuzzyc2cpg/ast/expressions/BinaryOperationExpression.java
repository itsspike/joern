package io.joern.fuzzyc2cpg.ast.expressions;

import io.joern.fuzzyc2cpg.ast.walking.ASTNodeVisitor;

public class BinaryOperationExpression extends BinaryExpression {
  @Override
  public void accept(ASTNodeVisitor visitor) {
    visitor.visit(this);
  }

}