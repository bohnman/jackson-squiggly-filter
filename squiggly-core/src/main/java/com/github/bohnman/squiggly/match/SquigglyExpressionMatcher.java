package com.github.bohnman.squiggly.match;

import com.github.bohnman.squiggly.name.AnyDeepName;
import com.github.bohnman.squiggly.name.NeverMatchName;
import com.github.bohnman.squiggly.node.support.ExpressionNode;
import com.github.bohnman.squiggly.node.support.StatementNode;
import com.github.bohnman.squiggly.path.support.DefaultObjectPath;

public interface SquigglyExpressionMatcher {
    /**
     * Indicate to never match the path.
     */
    ExpressionNode NEVER_MATCH = ExpressionNode.createNamed(NeverMatchName.get());
    /**
     * Indicate to always match the path.
     */
    ExpressionNode ALWAYS_MATCH = ExpressionNode.createNamed(AnyDeepName.get());

    ExpressionNode match(DefaultObjectPath path, String filter, ExpressionNode expression);

    default ExpressionNode match(DefaultObjectPath path, String filter, StatementNode statement) {
        return match(path, filter, statement.getRoot());
    }
}
