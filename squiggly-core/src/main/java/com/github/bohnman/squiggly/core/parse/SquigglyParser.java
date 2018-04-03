package com.github.bohnman.squiggly.core.parse;

import com.github.bohnman.core.antlr4.ThrowingErrorListener;
import com.github.bohnman.core.cache.CoreCache;
import com.github.bohnman.core.cache.CoreCacheBuilder;
import com.github.bohnman.core.lang.CoreStrings;
import com.github.bohnman.core.tuple.CorePair;
import com.github.bohnman.squiggly.core.config.SquigglyConfig;
import com.github.bohnman.squiggly.core.config.SystemFunctionName;
import com.github.bohnman.squiggly.core.metric.SquigglyMetrics;
import com.github.bohnman.squiggly.core.metric.source.CoreCacheSquigglyMetricsSource;
import com.github.bohnman.squiggly.core.name.AnyDeepName;
import com.github.bohnman.squiggly.core.name.AnyShallowName;
import com.github.bohnman.squiggly.core.name.ExactName;
import com.github.bohnman.squiggly.core.name.RegexName;
import com.github.bohnman.squiggly.core.name.SquigglyName;
import com.github.bohnman.squiggly.core.name.VariableName;
import com.github.bohnman.squiggly.core.name.WildcardName;
import com.github.bohnman.squiggly.core.parse.antlr4.SquigglyExpressionBaseVisitor;
import com.github.bohnman.squiggly.core.parse.antlr4.SquigglyExpressionLexer;
import com.github.bohnman.squiggly.core.parse.antlr4.SquigglyExpressionParser;
import com.github.bohnman.squiggly.core.parse.node.ArgumentNode;
import com.github.bohnman.squiggly.core.parse.node.ArgumentNodeType;
import com.github.bohnman.squiggly.core.parse.node.FunctionNode;
import com.github.bohnman.squiggly.core.parse.node.FunctionNodeType;
import com.github.bohnman.squiggly.core.parse.node.IfNode;
import com.github.bohnman.squiggly.core.parse.node.IntRangeNode;
import com.github.bohnman.squiggly.core.parse.node.LambdaNode;
import com.github.bohnman.squiggly.core.parse.node.SquigglyNode;
import com.github.bohnman.squiggly.core.view.PropertyView;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * The parser takes a filter expression and compiles it to an Abstract Syntax Tree (AST).  In this parser's case, the
 * tree doesn't have a root node but rather just returns top level nodes.
 */
@ThreadSafe
public class SquigglyParser {


    public static final String OP_DOLLAR_BRACKET_LEFT_SAFE = "$?[";
    public static final String OP_BRACKET_LEFT_SAFE = "?[";
    public static final String OP_SAFE_NAVIGATION = "?.";
    public static final String OP_DOLLAR_DOT_SAFE = "$?.";
    public static final String OP_DOLLAR = "$";

    // Caches parsed filter expressions
    private final CoreCache<String, List<SquigglyNode>> cache;

    public SquigglyParser(SquigglyConfig config, SquigglyMetrics metrics) {
        cache = CoreCacheBuilder.from(config.getParserNodeCacheSpec()).build();
        metrics.add(new CoreCacheSquigglyMetricsSource("squiggly.parser.nodeCache.", cache));
    }

    /**
     * Parse node filter expression.
     *
     * @param filter filter
     * @return list of squiggly nodes
     */
    public List<SquigglyNode> parseNodeFilter(String filter) {
        filter = CoreStrings.trim(filter);

        if (CoreStrings.isEmpty(filter)) {
            return Collections.emptyList();
        }

        List<SquigglyNode> cachedNodes = cache.get(filter);

        if (cachedNodes != null) {
            return cachedNodes;
        }

        SquigglyExpressionLexer lexer = ThrowingErrorListener.overwrite(new SquigglyExpressionLexer(CharStreams.fromString(filter)));
        SquigglyExpressionParser parser = ThrowingErrorListener.overwrite(new SquigglyExpressionParser(new CommonTokenStream(lexer)));
        NodeFilterVisitor visitor = new NodeFilterVisitor();
        List<SquigglyNode> nodes = visitor.visit(parser.nodeFilter());

        cache.put(filter, nodes);
        return nodes;
    }

    /**
     * Parse a filter expression.
     *
     * @param filter the filter expression
     * @return compiled nodes
     */
    public SquigglyNode parsePropertyFilter(String filter) {
        filter = CoreStrings.trim(filter);

        if (CoreStrings.isEmpty(filter)) {
            return SquigglyNode.EMPTY;
        }

        // get it from the cache if we can
        List<SquigglyNode> cachedNodes = cache.get(filter);

        if (cachedNodes != null) {
            return cachedNodes.isEmpty() ? SquigglyNode.EMPTY : cachedNodes.get(0);
        }

        SquigglyExpressionLexer lexer = ThrowingErrorListener.overwrite(new SquigglyExpressionLexer(CharStreams.fromString(filter)));
        SquigglyExpressionParser parser = ThrowingErrorListener.overwrite(new SquigglyExpressionParser(new CommonTokenStream(lexer)));

        PropertyFilterVisitor visitor = new PropertyFilterVisitor();
        SquigglyNode node = visitor.visit(parser.propertyFilter());

        if (node != null) {
            cache.put(filter, Collections.singletonList(node));
        }

        return node;
    }

    private class NodeFilterVisitor extends SquigglyExpressionBaseVisitor<List<SquigglyNode>> {

        private final PropertyFilterVisitor visitor = new PropertyFilterVisitor();

        @Override
        public List<SquigglyNode> visitNodeFilter(SquigglyExpressionParser.NodeFilterContext context) {
            return context.nodeExpressionList()
                    .stream()
                    .map(visitor::visitNodeExpressionList)
                    .filter(Objects::nonNull)
                    .collect(toList());
        }
    }

    private class PropertyFilterVisitor extends SquigglyExpressionBaseVisitor<SquigglyNode> {

        @Override
        public SquigglyNode visitNodeExpressionList(SquigglyExpressionParser.NodeExpressionListContext context) {
            MutableNode root = new MutableNode(parseContext(context), new ExactName(SquigglyNode.ROOT))
                    .depth(0)
                    .dotPathed(true);

            if (context.expressionList() != null) {
                handleExpressionList(context.expressionList(), root);
            } else if (context.topLevelExpression() != null) {
                handleTopLevelExpression(context.topLevelExpression(), root);
            }

            MutableNode analyzedRoot = analyze(root);
            return analyzedRoot.build();
        }

        @Override
        public SquigglyNode visitPropertyFilter(SquigglyExpressionParser.PropertyFilterContext context) {
            MutableNode root = new MutableNode(parseContext(context), new ExactName(SquigglyNode.ROOT))
                    .depth(0)
                    .dotPathed(true);
            handleExpressionList(context.expressionList(), root);
            MutableNode analyzedRoot = analyze(root);
            return analyzedRoot.build();
        }

        private ParseContext parseContext(ParserRuleContext context) {
            Token start = context.getStart();
            return new ParseContext(start.getLine(), start.getCharPositionInLine());
        }

        private void handleTopLevelExpression(SquigglyExpressionParser.TopLevelExpressionContext context, MutableNode root) {
            root.name(new AnyDeepName());
            if (context.topLevelArgChain() != null) {
                handleTopLevelArgChain(context.topLevelArgChain(), root);
            }
        }

        private void handleTopLevelArgChain(SquigglyExpressionParser.TopLevelArgChainContext context, MutableNode root) {
            if (context.assignment() != null) {
                root.valueFunctions(parseAssignment(context.assignment()));
                return;
            }

            if (!context.argChainLink().isEmpty()) {
                root.valueFunctions(context.argChainLink()
                        .stream()
                        .map(argChainLink -> buildFunction(argChainLink, true).build())
                        .collect(Collectors.toList())
                );
            }
        }

        private void handleExpressionList(SquigglyExpressionParser.ExpressionListContext context, MutableNode parent) {
            List<SquigglyExpressionParser.ExpressionContext> expressions = context.expression();

            for (SquigglyExpressionParser.ExpressionContext expressionContext : expressions) {
                handleExpression(expressionContext, parent);
            }
        }

        private void handleExpression(SquigglyExpressionParser.ExpressionContext context, MutableNode parent) {

            if (context.negatedExpression() != null) {
                handleNegatedExpression(context.negatedExpression(), parent);
                return;
            }

            if (context.dottedFieldExpression() != null) {
                handleDottedFieldExpression(context.dottedFieldExpression(), parent);
                return;
            }

            if (context.fieldGroupExpression() != null) {
                handleFieldGroupExpression(context.fieldGroupExpression(), parent);
                return;
            }

            if (context.recursiveExpression() != null) {
                handleRecursiveExpression(context.recursiveExpression(), parent);
                return;
            }

            throw new SquigglyParseException(parseContext(context), "Unrecognized expression");
        }

        private void handleDottedFieldExpression(SquigglyExpressionParser.DottedFieldExpressionContext context, MutableNode parent) {
            SquigglyName name;
            ParserRuleContext ruleContext;
            SquigglyExpressionParser.KeyValueFieldArgChainContext keyValueContext;

            if (context.dottedField() != null) {
                SquigglyExpressionParser.DottedFieldContext dottedField = context.dottedField();
                parent = handleDottedField(dottedField, parent);
                name = createName(dottedField.field().get(dottedField.field().size() - 1));
                ruleContext = dottedField;
                keyValueContext = context.keyValueFieldArgChain();
            } else if (context.MultiplyAssign() != null) {
                name = AnyShallowName.get();
                keyValueContext = createValueAssignmentKeyValueArgChain(context, FakeAssignmentContext.createEquals(context, context.arg()));
                ruleContext = context;
            } else if (context.WildcardDeep() != null) {
                name = AnyShallowName.get();
                keyValueContext = createValueAssignmentKeyValueArgChain(context, FakeAssignmentContext.createMultiplyAssign(context, context.arg()));
                ruleContext = context;
            } else {
                throw new SquigglyParseException(parseContext(context), "Unrecognized dotted field expression");
            }

            createNode(parent, name, ruleContext, keyValueContext, context.nestedExpression());
        }

        private void handleFieldGroupExpression(SquigglyExpressionParser.FieldGroupExpressionContext context, MutableNode parent) {
            SquigglyExpressionParser.FieldGroupContext fieldGroup = context.fieldGroup();
            SquigglyExpressionParser.KeyValueFieldArgChainContext keyValueContext = context.keyValueFieldArgChain();
            SquigglyExpressionParser.NestedExpressionContext nestedExpressionContext = context.nestedExpression();

            for (SquigglyExpressionParser.FieldContext fieldContext : fieldGroup.field()) {
                createNode(parent, createName(fieldContext), fieldContext, keyValueContext, nestedExpressionContext);
            }
        }

        private void handleRecursiveExpression(SquigglyExpressionParser.RecursiveExpressionContext context, MutableNode parent) {
            if (context.recursiveArg().isEmpty()) {
//                createNode(parent, AnyDeepName.get(), context, null, null).recursive(null);
                createNode(parent, AnyDeepName.get(), context, null, null);
                return;
            }

            IntRangeNode intRangeNode;

            if (context.intRange() == null) {
                intRangeNode = new IntRangeNode(null, null, false);
            } else {
                intRangeNode = (IntRangeNode) buildIntRange(context.intRange())
                        .index(0)
                        .build()
                        .getValue();
            }

            for (SquigglyExpressionParser.RecursiveArgContext argContext : context.recursiveArg()) {
                handleRecursiveArg(argContext, parent, intRangeNode);
            }
        }

        private void handleRecursiveArg(SquigglyExpressionParser.RecursiveArgContext context, MutableNode parent, IntRangeNode intRangeNode) {
            MutableNode node;

            if (context.Subtract() != null) {
                node = parent.addChild(new MutableNode(parseContext(context.field()), createName(context.field())).negated(true));
            } else if (context.MultiplyAssign() != null) {
                SquigglyName name = AnyShallowName.get();
                SquigglyExpressionParser.KeyValueFieldArgChainContext keyValueContext = createValueAssignmentKeyValueArgChain(context, FakeAssignmentContext.createEquals(context, context.arg()));
                node = createNode(parent, name, context, keyValueContext, null);
            } else if (context.WildcardDeep() != null) {
                SquigglyName name = AnyShallowName.get();
                SquigglyExpressionParser.KeyValueFieldArgChainContext keyValueContext = createValueAssignmentKeyValueArgChain(context, FakeAssignmentContext.createMultiplyAssign(context, context.arg()));
                node = createNode(parent, name, context, keyValueContext, null);
            } else if (context.field() != null) {
                node = createNode(parent, createName(context.field()), context.field(), context.keyValueFieldArgChain(), null);
            } else {
                throw new SquigglyParseException(parseContext(context), "Unrecognized recursive arg");
            }

            node.recursive(intRangeNode);
        }

        private MutableNode createNode(MutableNode parent, SquigglyName name, ParserRuleContext ruleContext, SquigglyExpressionParser.KeyValueFieldArgChainContext keyValueFieldArgChainContext, SquigglyExpressionParser.NestedExpressionContext nestedExpressionContext) {
            MutableNode node = parent.addChild(new MutableNode(parseContext(ruleContext), name));

            if (keyValueFieldArgChainContext != null) {
                node.keyFunctions(parseKeyFunctionChain(keyValueFieldArgChainContext));
                node.valueFunctions(parseValueFunctionChain(keyValueFieldArgChainContext));
            }

            if (nestedExpressionContext != null) {
                if (nestedExpressionContext.expressionList() == null) {
                    node.emptyNested(true);
                } else {
                    node.squiggly(true);
                    handleExpressionList(nestedExpressionContext.expressionList(), node);
                }
            }

            return node;
        }

        private SquigglyExpressionParser.KeyValueFieldArgChainContext createValueAssignmentKeyValueArgChain(ParserRuleContext parentContext, SquigglyExpressionParser.AssignmentContext assignmentContext) {
            return new SquigglyExpressionParser.KeyValueFieldArgChainContext(parentContext, ATNState.BASIC) {
                @Override
                public Token getStart() {
                    return parentContext.getStart();
                }

                @Override
                public List<SquigglyExpressionParser.AssignmentContext> assignment() {
                    return Collections.singletonList(assignmentContext);
                }

                @Override
                public SquigglyExpressionParser.AssignmentContext assignment(int i) {
                    return assignment().get(i);
                }
            };
        }


        private MutableNode handleDottedField(SquigglyExpressionParser.DottedFieldContext dottedField, MutableNode parent) {
            parent.squiggly(dottedField.field().size() > 1);
            for (int i = 0; i < dottedField.field().size() - 1; i++) {
                SquigglyExpressionParser.FieldContext field = dottedField.field(i);
                parent = parent.addChild(new MutableNode(parseContext(field), createName(field)).dotPathed(true));
                parent.squiggly(true);
            }
            return parent;
        }

        private List<FunctionNode> parseKeyFunctionChain(SquigglyExpressionParser.KeyValueFieldArgChainContext context) {
            if (context.Colon().isEmpty()) {
                return Collections.emptyList();
            }

            if (!context.fieldArgChain().isEmpty()) {
                return parseFieldArgChain(context.fieldArgChain().get(0));
            }

            if (!context.assignment().isEmpty()) {
                return parseAssignment(context.assignment().get(0));
            }

            return Collections.emptyList();
        }

        private List<FunctionNode> parseValueFunctionChain(SquigglyExpressionParser.KeyValueFieldArgChainContext context) {
            if (context.Colon().isEmpty() && context.fieldArgChain().size() == 1) {
                return parseFieldArgChain(context.fieldArgChain(0));
            }

            if (context.fieldArgChain().size() == 2) {
                return parseFieldArgChain(context.fieldArgChain(1));
            }

            if (context.Colon().isEmpty() && context.assignment().size() == 1) {
                return parseAssignment(context.assignment(0));
            }

            if (context.assignment().size() == 2) {
                return parseAssignment(context.assignment(1));
            }

            if (context.continuingFieldArgChain() != null) {
                return parseContinuingFieldArgChain(context.continuingFieldArgChain());
            }

            return Collections.emptyList();
        }

        private List<FunctionNode> parseAssignment(SquigglyExpressionParser.AssignmentContext context) {

            FunctionNodeType type = context.Equals() == null ? FunctionNodeType.SELF_ASSIGNMENT : FunctionNodeType.ASSIGNMENT;

            String name = SystemFunctionName.ASSIGN.getFunctionName();

            if (context.AddAssign() != null) {
                name = SystemFunctionName.ADD.getFunctionName();
            } else if (context.SubtractAssign() != null) {
                name = SystemFunctionName.SUBTRACT.getFunctionName();
            } else if (context.MultiplyAssign() != null) {
                name = SystemFunctionName.MULTIPLY.getFunctionName();
            } else if (context.DivideAssign() != null) {
                name = SystemFunctionName.DIVIDE.getFunctionName();
            } else if (context.ModulusAssign() != null) {
                name = SystemFunctionName.MODULUS.getFunctionName();
            }

            return Collections.singletonList(FunctionNode.builder()
                    .context(parseContext(context))
                    .name(name)
                    .type(type)
                    .argument(baseArg(context, ArgumentNodeType.INPUT).value(ArgumentNodeType.INPUT))
                    .argument(buildArg(context.arg()))
                    .build());

        }

        private List<FunctionNode> parseFieldArgChain(SquigglyExpressionParser.FieldArgChainContext context) {
            int size = 1;

            if (context.continuingFieldArgChain() != null) {
                size += context.continuingFieldArgChain().continuingFieldArgChainLink().size();
            }

            List<FunctionNode> functionNodes = new ArrayList<>(size);

            if (context.standaloneFieldArg() != null) {
                functionNodes.add(buildStandaloneFieldArg(context.standaloneFieldArg(), null));
            }

            if (context.function() != null) {
                functionNodes.add(buildFunction(context.function(), null, true).build());
            }

            if (context.continuingFieldArgChain() != null) {
                parseContinuingFieldArgChain(context.continuingFieldArgChain(), functionNodes);
            }

            return functionNodes;
        }

        private List<FunctionNode> parseContinuingFieldArgChain(SquigglyExpressionParser.ContinuingFieldArgChainContext context) {
            List<FunctionNode> functionNodes = new ArrayList<>(context.continuingFieldArgChainLink().size());
            parseContinuingFieldArgChain(context, functionNodes);
            return functionNodes;
        }

        private void parseContinuingFieldArgChain(SquigglyExpressionParser.ContinuingFieldArgChainContext context, List<FunctionNode> functionNodes) {
            for (SquigglyExpressionParser.ContinuingFieldArgChainLinkContext linkContext : context.continuingFieldArgChainLink()) {
                functionNodes.add(parseContinuingFieldArgChainLink(linkContext));

            }
        }

        private FunctionNode parseContinuingFieldArgChainLink(SquigglyExpressionParser.ContinuingFieldArgChainLinkContext context) {
            SquigglyExpressionParser.AccessOperatorContext opContext = context.accessOperator();

            if (context.function() != null) {
                return buildFunction(context.function(), opContext, true).build();
            }

            if (context.standaloneFieldArg() != null) {
                return buildStandaloneFieldArg(context.standaloneFieldArg(), opContext);
            }

            throw new SquigglyParseException(parseContext(context), "unknown field arg chain link [%s]", context.getText());
        }

        private FunctionNode buildStandaloneFieldArg(SquigglyExpressionParser.StandaloneFieldArgContext context, SquigglyExpressionParser.AccessOperatorContext opContext) {
            if (context.intRange() != null) {
                SquigglyExpressionParser.IntRangeContext intRange = context.intRange();
                return buildIntRangeFunction(intRange);
            }

            if (context.arrayAccessor() != null) {
                SquigglyExpressionParser.ArrayAccessorContext arrayAccessorContext = context.arrayAccessor();
                return buildArrayAccessorFunction(arrayAccessorContext);
            }

            throw new SquigglyParseException(parseContext(context), "unknown standalone field arg [%s]", context.getText());
        }

        private FunctionNode buildArrayAccessorFunction(SquigglyExpressionParser.ArrayAccessorContext arrayAccessor) {
            String integer = CoreStrings.substring(arrayAccessor.getText(), 1, -1);
            ArgumentNode.Builder arg = baseArg(arrayAccessor, ArgumentNodeType.INTEGER).value(Integer.parseInt(integer));

            return FunctionNode.builder()
                    .context(parseContext(arrayAccessor))
                    .name(SystemFunctionName.GET.getFunctionName())
                    .argument(baseArg(arrayAccessor, ArgumentNodeType.INPUT).value(ArgumentNodeType.INPUT))
                    .argument(arg)
                    .build();
        }

        private FunctionNode.Builder buildFunction(SquigglyExpressionParser.ArgChainLinkContext context, boolean input) {
            if (context.functionAccessor() != null) {
                return buildFunction(context.functionAccessor().function(), context.functionAccessor().accessOperator(), input);
            }

            if (context.propertyAccessor() != null) {
                return buildPropertyFunction(context.propertyAccessor(), input);
            }

            throw new SquigglyParseException(parseContext(context), "unknown arg chain link [%s]", context.getText());
        }

        @SuppressWarnings("SameParameterValue")
        private FunctionNode parseFunction(SquigglyExpressionParser.ArgChainLinkContext context, boolean input) {
            return buildFunction(context, input).build();
        }

        private FunctionNode.Builder buildPropertyFunction(SquigglyExpressionParser.PropertyAccessorContext context, boolean input) {
            Object value;
            ArgumentNodeType type;

            if (context.Identifier() != null) {
                value = context.Identifier().getText();
                type = ArgumentNodeType.STRING;
            } else if (context.StringLiteral() != null) {
                value = unescapeString(context.StringLiteral().getText());
                type = ArgumentNodeType.STRING;
            } else if (context.variable() != null) {
                value = buildVariableValue(context.variable());
                type = ArgumentNodeType.VARIABLE;
            } else if (context.intRange() != null) {
                value = Collections.singletonList(buildIntRangeFunction(context.intRange()));
                type = ArgumentNodeType.FUNCTION_CHAIN;
            } else if (context.arrayAccessor() != null) {
                value = Collections.singletonList(buildArrayAccessorFunction(context.arrayAccessor()));
                type = ArgumentNodeType.FUNCTION_CHAIN;
            } else {
                throw new SquigglyParseException(parseContext(context), "Cannot find property name [%s]", context.getText());
            }

            String op = null;

            if (context.accessOperator() != null) {
                op = context.accessOperator().getText();
            } else if (context.BracketLeftSafe() != null) {
                op = context.BracketLeft().getText();
            } else if (context.BracketLeft() != null) {
                op = context.BracketLeft().getText();
            }

            return buildBasePropertyFunction(context, op)
                    .argument(baseArg(context, type).value(value));
        }

        private FunctionNode.Builder buildPropertyFunction(SquigglyExpressionParser.InitialPropertyAccessorContext context) {
            Object value;
            ArgumentNodeType type;

            String op = null;

            if (context.Dollar() != null) {
                op = context.Dollar().getText();

                if (context.QuestionMark() != null) {
                    op += context.QuestionMark().getText();
                }

                if (context.Dot() != null) {
                    op += context.Dot().getText();
                }

                if (context.BracketLeft() != null) {
                    op += context.BracketLeft().getText();
                }

            }

            if (context.Identifier() != null) {
                value = context.Identifier().getText();
                type = ArgumentNodeType.STRING;
            } else if (context.StringLiteral() != null) {
                value = unescapeString(context.StringLiteral().getText());
                type = ArgumentNodeType.STRING;
            } else if (context.variable() != null) {
                value = buildVariableValue(context.variable());
                type = ArgumentNodeType.VARIABLE;
            } else {
                value = context.Dollar().getText();
                type = ArgumentNodeType.STRING;
            }

            return buildBasePropertyFunction(context, op)
                    .argument(baseArg(context, type).value(value));
        }

        private FunctionNode.Builder buildBasePropertyFunction(ParserRuleContext context, String operator) {
            FunctionNode.Builder function = FunctionNode.builder()
                    .context(parseContext(context))
                    .name(SystemFunctionName.PROPERTY.getFunctionName())
                    .type(FunctionNodeType.PROPERTY)
                    .argument(baseArg(context, ArgumentNodeType.INPUT).value(ArgumentNodeType.INPUT));

            if (OP_SAFE_NAVIGATION.equals(operator)
                    || OP_DOLLAR_BRACKET_LEFT_SAFE.equals(operator)
                    || OP_DOLLAR_DOT_SAFE.equals(operator)
                    || OP_BRACKET_LEFT_SAFE.equals(operator)) {
                function.ignoreNulls(true);
            }

            return function;
        }

        private FunctionNode.Builder buildFunction(SquigglyExpressionParser.FunctionContext functionContext, @Nullable SquigglyExpressionParser.AccessOperatorContext operatorContext, boolean input) {
            ParseContext context = parseContext(functionContext);
            FunctionNode.Builder builder = buildBaseFunction(functionContext, context);

            if (input) {
                builder.argument(baseArg(functionContext, ArgumentNodeType.INPUT).value(ArgumentNodeType.INPUT));
            }

            applyParameters(builder, functionContext);

            if (operatorContext != null && OP_SAFE_NAVIGATION.equals(operatorContext.getText())) {
                builder.ignoreNulls(true);
            }

            return builder;
        }


        private FunctionNode.Builder buildBaseFunction(SquigglyExpressionParser.FunctionContext functionContext, ParseContext context) {
            return FunctionNode.builder()
                    .context(context)
                    .name(functionContext.functionName().getText());
        }

        private void applyParameters(FunctionNode.Builder builder, SquigglyExpressionParser.FunctionContext functionContext) {
            functionContext.arg().forEach(parameter -> applyParameter(builder, parameter));
        }

        private void applyParameter(FunctionNode.Builder builder, SquigglyExpressionParser.ArgContext parameter) {
            ArgumentNode.Builder arg = buildArg(parameter);
            builder.argument(arg);
        }

        private ArgumentNode.Builder buildArg(SquigglyExpressionParser.ArgContext arg) {
            Object value;
            ArgumentNodeType type;

            if (arg.Null() != null) {
                return buildNull(arg);
            }

            if (arg.argChain() != null) {
                return buildArgChain(arg.argChain());
            }

            if (arg.lambda() != null) {
                return buildLambda(arg.lambda());
            }

            if (arg.arg() != null && !arg.arg().isEmpty()) {
                return buildSubArg(arg);
            }

            if (arg.ifArg() != null) {
                return buildIfArg(arg.ifArg());
            }


            throw new SquigglyParseException(parseContext(arg), "Unknown arg type [%s]", arg.getText());
        }

        private ArgumentNode.Builder buildNull(SquigglyExpressionParser.ArgContext arg) {
            return baseArg(arg, ArgumentNodeType.NULL).value(null);
        }

        private ArgumentNode.Builder buildIfArg(SquigglyExpressionParser.IfArgContext context) {
            Stream<IfNode.IfClause> ifClauseStream = Stream.of(context.ifClause())
                    .map(this::buildIfClause);

            Stream<IfNode.IfClause> elifClauseStream = context.elifClause()
                    .stream()
                    .map(this::buildIfClause);

            List<IfNode.IfClause> ifClauses = Stream.concat(ifClauseStream, elifClauseStream)
                    .collect(toList());

            ArgumentNode elseClause;

            if (context.elseClause() == null) {
                elseClause = baseArg(context, ArgumentNodeType.NULL).value(null).index(0).build();
            } else {
                elseClause = buildArg(context.elseClause().arg()).index(0).build();
            }

            FunctionNode functionNode = FunctionNode.builder()
                    .context(parseContext(context))
                    .name(SystemFunctionName.SELF.getFunctionName())
                    .argument(baseArg(context, ArgumentNodeType.IF).value(new IfNode(ifClauses, elseClause)))
                    .build();


            return baseArg(context, ArgumentNodeType.FUNCTION_CHAIN)
                    .value(Collections.singletonList(functionNode));
        }

        private IfNode.IfClause buildIfClause(SquigglyExpressionParser.IfClauseContext context) {
            ArgumentNode condition = buildArg(context.arg(0)).index(0).build();
            ArgumentNode value = buildArg(context.arg(1)).index(1).build();
            return new IfNode.IfClause(condition, value);
        }

        private IfNode.IfClause buildIfClause(SquigglyExpressionParser.ElifClauseContext context) {
            ArgumentNode condition = buildArg(context.arg(0)).index(0).build();
            ArgumentNode value = buildArg(context.arg(1)).index(1).build();
            return new IfNode.IfClause(condition, value);
        }

        private ArgumentNode.Builder buildLambda(SquigglyExpressionParser.LambdaContext lambda) {
            List<String> arguments = lambda.lambdaArg()
                    .stream()
                    .map(arg -> arg.variable() == null ? "_" : buildVariableValue(arg.variable()))
                    .collect(toList());

            ParseContext parseContext = parseContext(lambda);
            FunctionNode body = FunctionNode.builder()
                    .name(SystemFunctionName.SELF.getFunctionName())
                    .context(parseContext)
                    .argument(buildArg(lambda.lambdaBody().arg()))
                    .build();


            LambdaNode lambdaNode = new LambdaNode(parseContext, arguments, body);

            return baseArg(lambda, ArgumentNodeType.LAMBDA)
                    .value(lambdaNode);
        }

        private ArgumentNode.Builder buildSubArg(SquigglyExpressionParser.ArgContext arg) {
            if (arg.argGroupStart() != null) {
                ArgumentNode.Builder groupArg = buildArg(arg.arg(0));

                if (arg.argChainLink() != null) {
                    List<FunctionNode> functionNodes = new ArrayList<>(arg.argChainLink().size() + 1);

                    functionNodes.add(FunctionNode.builder()
                            .context(parseContext(arg))
                            .name(SystemFunctionName.SELF.getFunctionName())
                            .argument(groupArg)
                            .build()
                    );

                    for (SquigglyExpressionParser.ArgChainLinkContext linkContext : arg.argChainLink()) {
                        functionNodes.add(buildFunction(linkContext, true).build());
                    }


                    groupArg = baseArg(arg, ArgumentNodeType.FUNCTION_CHAIN).value(functionNodes);
                }

                return groupArg;

            }

            return buildArgExpression(arg);
        }

        private ArgumentNode.Builder buildArgExpression(SquigglyExpressionParser.ArgContext arg) {
            String op = getOp(arg);

            ParseContext parseContext = parseContext(arg);

            FunctionNode.Builder functionNode = FunctionNode.builder()
                    .context(parseContext)
                    .name(op);

            arg.arg().forEach(p -> functionNode.argument(buildArg(p)));

            return baseArg(arg, ArgumentNodeType.FUNCTION_CHAIN)
                    .value(Collections.singletonList(functionNode.build()));
        }

        private String getOp(SquigglyExpressionParser.ArgContext arg) {
            if (matchOp(arg.Not(), arg.NotName())) {
                return SystemFunctionName.NOT.getFunctionName();
            }

            if (matchOp(arg.Add(), arg.AddName())) {
                return SystemFunctionName.ADD.getFunctionName();
            }

            if (matchOp(arg.Subtract(), arg.SubtractName())) {
                return SystemFunctionName.SUBTRACT.getFunctionName();
            }

            if (matchOp(arg.WildcardShallow(), arg.MultiplyName())) {
                return SystemFunctionName.MULTIPLY.getFunctionName();
            }

            if (matchOp(arg.SlashForward(), arg.DivideName())) {
                return SystemFunctionName.DIVIDE.getFunctionName();
            }

            if (matchOp(arg.Modulus(), arg.ModulusName())) {
                return SystemFunctionName.MODULUS.getFunctionName();
            }

            if (arg.Elvis() != null) {
                return SystemFunctionName.DEFAULT.getFunctionName();
            }

            if (matchOp(arg.EqualsEquals(), arg.EqualsName())) {
                return SystemFunctionName.EQUALS.getFunctionName();
            }

            if (matchOp(arg.EqualsNot(), arg.EqualsNotName(), arg.EqualsNotSql())) {
                return SystemFunctionName.NOT_EQUALS.getFunctionName();
            }

            if (matchOp(arg.AngleLeft(), arg.LessThanName())) {
                return SystemFunctionName.LESS_THAN.getFunctionName();
            }

            if (matchOp(arg.LessThanEquals(), arg.LessThanEqualsName())) {
                return SystemFunctionName.LESS_THAN_EQUALS.getFunctionName();
            }

            if (matchOp(arg.AngleRight(), arg.GreaterThanName())) {
                return SystemFunctionName.GREATER_THAN.getFunctionName();
            }

            if (matchOp(arg.GreaterThanEquals(), arg.GreaterThanEqualsName())) {
                return SystemFunctionName.GREATER_THAN_EQUALS.getFunctionName();
            }

            if (matchOp(arg.Match(), arg.MatchName())) {
                return SystemFunctionName.MATCH.getFunctionName();
            }

            if (matchOp(arg.MatchNot(), arg.MatchNotName())) {
                return SystemFunctionName.NOT_MATCH.getFunctionName();
            }

            if (matchOp(arg.Or(), arg.OrName())) {
                return SystemFunctionName.OR.getFunctionName();
            }

            if (matchOp(arg.And(), arg.AndName())) {
                return SystemFunctionName.AND.getFunctionName();
            }

            throw new SquigglyParseException(parseContext(arg), "unknown op [%s]", arg.getText());
        }

        private boolean matchOp(TerminalNode token1, TerminalNode token2) {
            return token1 != null || token2 != null;
        }

        private boolean matchOp(TerminalNode token1, TerminalNode token2, TerminalNode token3) {
            return token1 != null || token2 != null || token3 != null;
        }

        private ArgumentNode.Builder buildLiteral(SquigglyExpressionParser.LiteralContext context) {
            if (context.BooleanLiteral() != null) {
                return buildBoolean(context);
            }


            if (context.FloatLiteral() != null) {
                return buildFloat(context);
            }

            if (context.IntegerLiteral() != null) {
                return buildInteger(context);
            }

            if (context.RegexLiteral() != null) {
                return buildRegex(context);
            }

            if (context.StringLiteral() != null) {
                return buildString(context);
            }

            throw new SquigglyParseException(parseContext(context), "Unknown literal type [%s]", context.getText());
        }

        private ArgumentNode.Builder buildIntRange(SquigglyExpressionParser.IntRangeContext context) {
            List<SquigglyExpressionParser.IntRangeArgContext> intRangeArgs;
            boolean exclusiveEnd;

            if (context.inclusiveExclusiveIntRange() != null) {
                intRangeArgs = context.inclusiveExclusiveIntRange().intRangeArg();
                exclusiveEnd = true;
            } else if (context.inclusiveInclusiveIntRange() != null) {
                intRangeArgs = context.inclusiveInclusiveIntRange().intRangeArg();
                exclusiveEnd = false;
            } else {
                throw new SquigglyParseException(parseContext(context), "Unknown int range type [%s]", context.getText());
            }

            ArgumentNode.Builder start = null;
            ArgumentNode.Builder end = null;

            if (intRangeArgs.isEmpty()) {
                start = baseArg(context, ArgumentNodeType.INTEGER).value(0);
            }

            if (intRangeArgs.size() > 0) {
                start = buildIntRangeArg(intRangeArgs.get(0));
            }

            if (intRangeArgs.size() > 1) {
                end = buildIntRangeArg(intRangeArgs.get(1));
            }

            return baseArg(context, ArgumentNodeType.INT_RANGE)
                    .value(new IntRangeNode(start, end, exclusiveEnd));
        }

        private FunctionNode buildIntRangeFunction(SquigglyExpressionParser.IntRangeContext intRange) {
            ArgumentNode.Builder arg = buildIntRange(intRange);
            return FunctionNode.builder()
                    .context(parseContext(intRange))
                    .name(SystemFunctionName.SLICE.getFunctionName())
                    .argument(baseArg(intRange, ArgumentNodeType.INPUT).value(ArgumentNodeType.INPUT))
                    .argument(arg)
                    .build();
        }

        private ArgumentNode.Builder buildIntRangeArg(SquigglyExpressionParser.IntRangeArgContext context) {
            if (context.variable() != null) {
                return buildVariable(context.variable());
            }

            if (context.IntegerLiteral() != null) {
                return buildInteger(context);
            }


            throw new SquigglyParseException(parseContext(context), "Unknown int range arg type [%s]", context.getText());
        }

        private ArgumentNode.Builder baseArg(ParserRuleContext context, ArgumentNodeType type) {
            ParseContext parseContext = parseContext(context);
            return ArgumentNode.builder().context(parseContext).type(type);
        }

        private ArgumentNode.Builder buildBoolean(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.BOOLEAN)
                    .value("true".equalsIgnoreCase(context.getText()));
        }

        private ArgumentNode.Builder buildFloat(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.FLOAT).value(Double.parseDouble(context.getText()));
        }

        private ArgumentNode.Builder buildArgChain(SquigglyExpressionParser.ArgChainContext context) {
            int functionLength = (context.argChainLink() == null) ? 0 : context.argChainLink().size();
            functionLength += 2;
            List<FunctionNode> functionNodes = new ArrayList<>(functionLength);

            if (context.arrayDeclaration() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.arrayDeclaration()))
                        .name(SystemFunctionName.SELF.getFunctionName())
                        .argument(buildArrayDeclaration(context.arrayDeclaration()))
                        .build()
                );
            }

            if (context.literal() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.literal()))
                        .name(SystemFunctionName.SELF.getFunctionName())
                        .argument(buildLiteral(context.literal()))
                        .build()
                );
            }

            if (context.intRange() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.intRange()))
                        .name(SystemFunctionName.SELF.getFunctionName())
                        .argument(buildIntRange(context.intRange()))
                        .build()
                );
            }

            if (context.objectDeclaration() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.objectDeclaration()))
                        .name(SystemFunctionName.SELF.getFunctionName())
                        .argument(buildObjectDeclaration(context.objectDeclaration()))
                        .build()
                );
            }


            if (context.variable() != null) {
                functionNodes.add(FunctionNode.builder()
                        .context(parseContext(context.variable()))
                        .name(SystemFunctionName.SELF.getFunctionName())
                        .argument(buildVariable(context.variable()))
                        .build()
                );
            }

            boolean ascending = true;

            if (context.propertySortDirection() != null) {
                ascending = !"-".equals(context.propertySortDirection().getText());
            }

            if (context.initialPropertyAccessor() != null) {
                functionNodes.add(buildPropertyFunction(context.initialPropertyAccessor()).ascending(ascending).build());
            }

            if (context.function() != null) {
                boolean input = !functionNodes.isEmpty();
                functionNodes.add(buildFunction(context.function(), null, input).ascending(ascending).build());
            }

            if (context.argChainLink() != null) {
                boolean input = !functionNodes.isEmpty();

                for (SquigglyExpressionParser.ArgChainLinkContext linkContext : context.argChainLink()) {
                    functionNodes.add(buildFunction(linkContext, input).ascending(ascending).build());
                }
            }

            return baseArg(context, ArgumentNodeType.FUNCTION_CHAIN)
                    .value(functionNodes);
        }

        private ArgumentNode.Builder buildArrayDeclaration(SquigglyExpressionParser.ArrayDeclarationContext context) {
            List<ArgumentNode> argumentNodes = new ArrayList<>(context.arg().size());

            for (int i = 0; i < context.arg().size(); i++) {
                SquigglyExpressionParser.ArgContext arg = context.arg().get(i);
                argumentNodes.add(buildArg(arg).index(i).build());
            }

            return baseArg(context, ArgumentNodeType.ARRAY_DECLARATION)
                    .value(argumentNodes);
        }

        private ArgumentNode.Builder buildInteger(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.INTEGER).value(Integer.parseInt(context.getText()));
        }

        private ArgumentNode.Builder buildObjectDeclaration(SquigglyExpressionParser.ObjectDeclarationContext context) {
            return baseArg(context, ArgumentNodeType.OBJECT_DECLARATION)
                    .value(
                            context.objectKeyValue()
                                    .stream()
                                    .map(this::buildObjectArgPair)
                                    .collect(toList())
                    );
        }

        private CorePair<ArgumentNode, ArgumentNode> buildObjectArgPair(SquigglyExpressionParser.ObjectKeyValueContext context) {
            ArgumentNode key = buildObjectArgKey(context.objectKey()).index(0).build();
            ArgumentNode value = buildArg(context.objectValue().arg()).index(0).build();

            return CorePair.of(key, value);
        }

        private ArgumentNode.Builder buildObjectArgKey(SquigglyExpressionParser.ObjectKeyContext context) {
            if (context.Identifier() != null) {
                return baseArg(context, ArgumentNodeType.STRING).value(context.Identifier().getText());
            } else if (context.literal() != null) {
                return buildLiteral(context.literal());
            } else if (context.variable() != null) {
                return buildVariable(context.variable());
            }

            throw new SquigglyParseException(parseContext(context), "unknown object arg key [%s]", context.getText());
        }

        private ArgumentNode.Builder buildRegex(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.REGEX).value(buildPattern(context.getText(), context));
        }

        private ArgumentNode.Builder buildString(ParserRuleContext context) {
            return baseArg(context, ArgumentNodeType.STRING).value(unescapeString(context.getText()));
        }

        private String unescapeString(String text) {
            if (text == null) {
                return null;
            }

            if (text.length() < 2) {
                return text;
            }

            if (text.startsWith("'") && text.endsWith("'")) {
                text = CoreStrings.unescapeEcmaScript(text.substring(1, text.length() - 1));
            } else if (text.startsWith("\"") && text.endsWith("\"")) {
                text = CoreStrings.unescapeEcmaScript(text.substring(1, text.length() - 1));
            } else if (text.startsWith("`") && text.endsWith("`")) {
                text = CoreStrings.unescapeEcmaScript(text.substring(1, text.length() - 1));
            }

            return text;
        }

        private ArgumentNode.Builder buildVariable(SquigglyExpressionParser.VariableContext context) {
            return baseArg(context, ArgumentNodeType.VARIABLE).value(buildVariableValue(context));
        }

        private String buildVariableValue(SquigglyExpressionParser.VariableContext context) {
            String text = context.getText();

            if (text.charAt(1) == '{') {
                text = text.substring(2, text.length() - 1);
            } else {
                text = text.substring(1);
            }

            return unescapeString(text);
        }

        private SquigglyName createName(SquigglyExpressionParser.FieldContext context) {
            SquigglyName name;

            if (context.StringLiteral() != null) {
                name = new ExactName(unescapeString(context.StringLiteral().getText()));
            } else if (context.namedSymbol() != null) {
                name = new ExactName(context.namedSymbol().getText());
            } else if (context.Identifier() != null) {
                name = new ExactName(context.Identifier().getText());
            } else if (context.wildcardField() != null) {
                name = new WildcardName(context.wildcardField().getText());
            } else if (context.RegexLiteral() != null) {
                Pattern pattern = buildPattern(context.RegexLiteral().getText(), context);
                name = new RegexName(pattern.pattern(), pattern);
            } else if (context.wildcard() != null) {
                if (AnyShallowName.ID.equals(context.wildcard().getText())) {
                    name = AnyShallowName.get();
                } else {
                    name = new WildcardName(context.wildcard().getText());
                }
            } else if (context.variable() != null) {
                name = new VariableName(buildVariableValue(context.variable()));
            } else {
                throw new SquigglyParseException(parseContext(context), "unhandled field [%s]", context.getText());
            }

            return name;
        }

        private Pattern buildPattern(String fullPattern, ParserRuleContext context) {
            String pattern = fullPattern.substring(1);
            int slashIdx = pattern.indexOf('/');

            if (slashIdx < 0) {
                slashIdx = pattern.indexOf('~');
            }

            Set<String> flags = new HashSet<>();

            if (slashIdx >= 0) {
                String flagPart = CoreStrings.trim(CoreStrings.substring(pattern, slashIdx + 1));
                pattern = CoreStrings.substring(pattern, 0, slashIdx);

                if (CoreStrings.isNotEmpty(flagPart)) {
                    for (char flag : flagPart.toCharArray()) {
                        flags.add(Character.toString(flag));
                    }
                }
            }

            int flagMask = 0;

            if (flags != null && !flags.isEmpty()) {
                for (String flag : flags) {
                    switch (flag) {
                        case "i":
                            flagMask |= Pattern.CASE_INSENSITIVE;
                            break;
                        case "m":
                            flagMask |= Pattern.MULTILINE;
                            break;
                        case "s":
                            flagMask |= Pattern.UNIX_LINES;
                            break;
                        case "x":
                            flagMask |= Pattern.COMMENTS;
                            break;
                        default:
                            throw new SquigglyParseException(parseContext(context), "Unrecognized flag %s for patterh %s", flag, pattern);
                    }
                }
            }

            return Pattern.compile(pattern, flagMask);
        }


        private void handleNegatedExpression(SquigglyExpressionParser.NegatedExpressionContext context, MutableNode parent) {
            if (context.field() != null) {
                parent.addChild(new MutableNode(parseContext(context.field()), createName(context.field())).negated(true));
            } else if (context.dottedField() != null) {
                for (SquigglyExpressionParser.FieldContext fieldContext : context.dottedField().field()) {
                    parent.squiggly(true);
                    parent = parent.addChild(new MutableNode(parseContext(context.dottedField()), createName(fieldContext)).dotPathed(true));
                }

                parent.negated(true);
            }
        }

    }

    private MutableNode analyze(MutableNode node) {
        Map<MutableNode, MutableNode> nodesToAdd = new IdentityHashMap<>();
        MutableNode analyze = analyze(node, nodesToAdd);

        for (Map.Entry<MutableNode, MutableNode> entry : nodesToAdd.entrySet()) {
            entry.getKey().addChild(entry.getValue());
        }

        return analyze;
    }

    private MutableNode analyze(MutableNode node, Map<MutableNode, MutableNode> nodesToAdd) {
        if (!node.getChildren().isEmpty()) {
            boolean allNegated = true;

            for (MutableNode child : node.getChildren()) {
                if (!child.isNegated()) {
                    allNegated = false;
                    break;
                }
            }

            if (allNegated) {
                nodesToAdd.put(node, new MutableNode(node.getContext(), newBaseViewName()).dotPathed(node.dotPathed));
                MutableNode parent = node.parent;

                while (parent != null) {
                    nodesToAdd.put(parent, new MutableNode(parent.getContext(), newBaseViewName()).dotPathed(parent.dotPathed));

                    if (!parent.dotPathed) {
                        break;
                    }

                    parent = parent.parent;
                }
            } else {
                for (MutableNode child : node.getChildren()) {
                    analyze(child, nodesToAdd);
                }
            }

        }

        return node;
    }

    private class MutableNode extends SquigglyNode.Builder<MutableNode> {
        private Map<String, MutableNode> mutableChildren;
        private boolean dotPathed;
        private MutableNode parent;

        MutableNode(ParseContext context, SquigglyName name) {
            context(context);
            name(name);
            depth(1);
        }

        public SquigglyNode build() {
            List<SquigglyNode> childNodes;

            if (mutableChildren == null || mutableChildren.isEmpty()) {
                childNodes = Collections.emptyList();
            } else {
                childNodes = new ArrayList<>(mutableChildren.size());

                for (MutableNode child : mutableChildren.values()) {
                    childNodes.add(child.build());
                }
            }

            children(childNodes);
            return super.build();
        }

        public Collection<MutableNode> getChildren() {
            if (mutableChildren == null) {
                return Collections.emptyList();
            }

            return mutableChildren.values();
        }

        public ParseContext getContext() {
            return context;
        }

        public SquigglyName getName() {
            return name;
        }


        public int getDepth() {
            return depth;
        }

        public boolean isNegated() {
            return negated;
        }

        public boolean isSquiggly() {
            return squiggly;
        }

        public MutableNode dotPathed(boolean dotPathed) {
            this.dotPathed = dotPathed;
            return this;
        }

        @Nullable
        public MutableNode recursive(IntRangeNode depthRange) {
            recursive(true);

            if (depthRange != null) {
                if (depthRange.getStart() == null) {
                    startDepth(null);
                } else if (depthRange.getStart().getType() == ArgumentNodeType.INTEGER)  {
                    startDepth((Integer) depthRange.getStart().getValue());
                } else {
                    throw new SquigglyParseException(context, "Only integers are currently support for start depth");
                }

                if (depthRange.getEnd() == null) {
                    endDepth(null);
                } else if (depthRange.getEnd().getType() == ArgumentNodeType.INTEGER)  {
                    int endDepth = (Integer) depthRange.getEnd().getValue();
                    if (!depthRange.isExclusiveEnd()) {
                        endDepth++;
                    }
                    endDepth(endDepth);
                } else {
                    throw new SquigglyParseException(context, "Only integers are currently support for end depth");
                }
            }


            return this;
        }

        public MutableNode addChild(MutableNode childToAdd) {
            if (mutableChildren == null) {
                mutableChildren = new LinkedHashMap<>();
            }

            String name = childToAdd.name.getName();
            MutableNode existingChild = mutableChildren.get(name);

            if (existingChild == null) {
                childToAdd.parent = this;
                mutableChildren.put(name, childToAdd);

                childToAdd.depth(getDepth() + 1);

                if (startDepth != null) {
                    startDepth += getDepth();
                }

                if (endDepth != null) {
                    endDepth += getDepth();
                }

            } else {
                if (childToAdd.mutableChildren != null) {

                    if (existingChild.mutableChildren == null) {
                        existingChild.mutableChildren = childToAdd.mutableChildren;
                    } else {
                        existingChild.mutableChildren.putAll(childToAdd.mutableChildren);
                    }
                }


                squiggly(existingChild.squiggly || childToAdd.squiggly);
                emptyNested(existingChild.emptyNested && childToAdd.emptyNested);
                dotPathed(existingChild.dotPathed && childToAdd.dotPathed);
                childToAdd = existingChild;
            }

            if (!childToAdd.dotPathed && dotPathed) {
                dotPathed = false;
            }

            return childToAdd;
        }

    }

    private ExactName newBaseViewName() {
        return new ExactName(PropertyView.BASE_VIEW);
    }


    private static TerminalNode createTerminalNode(String text) {
        return new TerminalNodeImpl(createToken(text));
    }

    private static Token createToken(String text) {
        return new CommonToken(Token.INVALID_TYPE, text);
    }


    private static class FakeAssignmentContext extends SquigglyExpressionParser.AssignmentContext {
        private final SquigglyExpressionParser.ArgContext argContext;
        private final ParserRuleContext parentContext;
        private final TerminalNode equals;
        private final TerminalNode assignSelf;
        private final TerminalNode addAssign;
        private final TerminalNode subtractAssign;
        private final TerminalNode multipleAssign;
        private final TerminalNode divideAssign;
        private final TerminalNode modulusAssign;


        public FakeAssignmentContext(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext, TerminalNode equals, TerminalNode assignSelf, TerminalNode addAssign, TerminalNode subtractAssign, TerminalNode multipleAssign, TerminalNode divideAssign, TerminalNode modulusAssign) {
            super(parentContext, ATNState.BASIC);
            this.parentContext = parentContext;
            this.argContext = argContext;
            this.equals = equals;
            this.assignSelf = assignSelf;
            this.addAssign = addAssign;
            this.subtractAssign = subtractAssign;
            this.multipleAssign = multipleAssign;
            this.divideAssign = divideAssign;
            this.modulusAssign = modulusAssign;
        }

        @Override
        public SquigglyExpressionParser.ArgContext arg() {
            return argContext;
        }

        @Override
        public TerminalNode Equals() {
            return equals;
        }

        @Override
        public TerminalNode AssignSelf() {
            return assignSelf;
        }

        @Override
        public TerminalNode AddAssign() {
            return super.AddAssign();
        }

        @Override
        public TerminalNode SubtractAssign() {
            return subtractAssign;
        }

        @Override
        public TerminalNode MultiplyAssign() {
            return multipleAssign;
        }

        @Override
        public TerminalNode DivideAssign() {
            return divideAssign;
        }

        @Override
        public TerminalNode ModulusAssign() {
            return modulusAssign;
        }

        @Override
        public Token getStart() {
            return parentContext.getStart();
        }

        public static FakeAssignmentContext createEquals(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, createTerminalNode("="), null, null, null, null, null, null);
        }

        public static FakeAssignmentContext createAssignSelf(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, null, createTerminalNode(".="), null, null, null, null, null);
        }

        public static FakeAssignmentContext createAddAssign(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, null, null, createTerminalNode("+="), null, null, null, null);
        }

        public static FakeAssignmentContext createSubtractAssign(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, null, null, null, createTerminalNode("-="), null, null, null);
        }

        public static FakeAssignmentContext createMultiplyAssign(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, null, null, null, null, createTerminalNode("*="), null, null);
        }

        public static FakeAssignmentContext createDivideAssign(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, null, null, null, null, null, createTerminalNode("/="), null);
        }

        public static FakeAssignmentContext createModulusAssign(ParserRuleContext parentContext, SquigglyExpressionParser.ArgContext argContext) {
            return new FakeAssignmentContext(parentContext, argContext, null, null, null, null, null, null, createTerminalNode("%="));
        }
    }
}