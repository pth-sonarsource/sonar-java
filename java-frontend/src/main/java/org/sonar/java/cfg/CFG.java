/*
 * SonarQube Java
 * Copyright (C) 2012-2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.java.cfg;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.java.Preconditions;
import org.sonar.java.model.ExpressionUtils;
import org.sonar.java.model.JavaTree;
import org.sonar.plugins.java.api.cfg.ControlFlowGraph;
import org.sonar.plugins.java.api.location.Position;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.ArrayDimensionTree;
import org.sonar.plugins.java.api.tree.AssertStatementTree;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.BreakStatementTree;
import org.sonar.plugins.java.api.tree.CaseGroupTree;
import org.sonar.plugins.java.api.tree.CaseLabelTree;
import org.sonar.plugins.java.api.tree.CatchTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.ContinueStatementTree;
import org.sonar.plugins.java.api.tree.DoWhileStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForEachStatement;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.GuardedPatternTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.InstanceOfTree;
import org.sonar.plugins.java.api.tree.LabeledStatementTree;
import org.sonar.plugins.java.api.tree.ListTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.NewArrayTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.NullPatternTree;
import org.sonar.plugins.java.api.tree.ParameterizedTypeTree;
import org.sonar.plugins.java.api.tree.ParenthesizedTree;
import org.sonar.plugins.java.api.tree.PatternInstanceOfTree;
import org.sonar.plugins.java.api.tree.PatternTree;
import org.sonar.plugins.java.api.tree.RecordPatternTree;
import org.sonar.plugins.java.api.tree.ReturnStatementTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.SwitchExpressionTree;
import org.sonar.plugins.java.api.tree.SwitchStatementTree;
import org.sonar.plugins.java.api.tree.SwitchTree;
import org.sonar.plugins.java.api.tree.SynchronizedStatementTree;
import org.sonar.plugins.java.api.tree.SyntaxToken;
import org.sonar.plugins.java.api.tree.ThrowStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TryStatementTree;
import org.sonar.plugins.java.api.tree.TypeCastTree;
import org.sonar.plugins.java.api.tree.TypePatternTree;
import org.sonar.plugins.java.api.tree.TypeTree;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.plugins.java.api.tree.WhileStatementTree;
import org.sonar.plugins.java.api.tree.WildcardTree;
import org.sonar.plugins.java.api.tree.YieldStatementTree;
import org.sonarsource.analyzer.commons.collections.ListUtils;

public class CFG implements ControlFlowGraph {

  private static final Logger LOG = LoggerFactory.getLogger(CFG.class);

  @Nullable
  private Symbol.MethodSymbol methodSymbol;
  private Block currentBlock;
  private boolean hasCompleteSemantic = true;

  /**
   * List of all blocks in order they were created.
   */
  private final List<Block> blocks = new ArrayList<>();

  private final Deque<Block> breakTargets = new LinkedList<>();
  private final Deque<Block> continueTargets = new LinkedList<>();
  private final Deque<Block> exitBlocks = new LinkedList<>();
  private final Deque<TryStatement> enclosingTry = new LinkedList<>();
  private final Deque<Boolean> enclosedByCatch = new LinkedList<>();
  private final TryStatement outerTry;

  private static class TryStatement {
    Map<Type, Block> catches = new LinkedHashMap<>();
    List<Block> runtimeCatches = new ArrayList<>();

    public void addCatch(Type type, Block catchBlock) {
      if (type.is("java.lang.Exception")
        || type.is("java.lang.Throwable")
        || type.isSubtypeOf("java.lang.Error")
        || type.isSubtypeOf("java.lang.RuntimeException")
        || !type.isSubtypeOf("java.lang.Exception")) {
        runtimeCatches.add(catchBlock);
      }
      catches.put(type, catchBlock);
    }
  }

  private String pendingLabel = null;
  private Map<String, Block> labelsBreakTarget = new HashMap<>();
  private Map<String, Block> labelsContinueTarget = new HashMap<>();

  private CFG(List<? extends Tree> trees, @Nullable MethodTree tree) {
    if (tree != null) {
      methodSymbol = tree.symbol();
      checkSymbolSemantic(methodSymbol, "method definition", tree.simpleName().identifierToken());
    }
    exitBlocks.add(createBlock());
    currentBlock = createBlock(exitBlock());
    outerTry = new TryStatement();
    enclosingTry.add(outerTry);
    enclosedByCatch.push(false);
    build(trees);
    prune();
    computePredecessors(blocks);
  }

  @Override
  public boolean hasCompleteSemantic() {
    return hasCompleteSemantic;
  }

  private void checkIdentifierSemantic(IdentifierTree tree) {
    checkSymbolSemantic(tree.symbol(), "unknown identifier", tree.identifierToken());
  }

  private void checkSymbolSemantic(@Nullable Symbol symbol, String reason, @Nullable SyntaxToken location) {
    if (symbol != null) {
      if (symbol.isUnknown()) {
        markSemanticAsIncomplete(reason, location);
      } else if (symbol instanceof Symbol.MethodSymbol method) {
        if (method.returnType().isUnknown()) {
          markSemanticAsIncomplete("unknown return type", location);
        } else if (method.parameterTypes().stream().anyMatch(Type::isUnknown)) {
          markSemanticAsIncomplete("unknown parameter type", location);
        }
      }
    }
  }

  private Type checkTypeSemantic(Type type, String reason, @Nullable SyntaxToken location) {
    if (type.isUnknown()) {
      markSemanticAsIncomplete(reason, location);
    }
    return type;
  }

  private void markSemanticAsIncomplete(String reason, @Nullable SyntaxToken location) {
    if (hasCompleteSemantic) {
      hasCompleteSemantic = false;
      if (LOG.isDebugEnabled()) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("Incomplete Semantic, ").append(reason);
        if (location != null) {
          logMessage.append(" '").append(location.text()).append("'");
          Position position = location.range().start();
          logMessage.append(" line ").append(position.line()).append(" col ").append(position.column());
        }
        LOG.debug(logMessage.toString());
      }
    }
  }

  @Override
  public Block exitBlock() {
    return exitBlocks.peek();
  }

  @Nullable
  public Symbol.MethodSymbol methodSymbol() {
    return methodSymbol;
  }

  @Override
  public Block entryBlock() {
    return currentBlock;
  }

  @Override
  public List<Block> blocks() {
    return ListUtils.reverse(blocks);
  }

  public List<Block> reversedBlocks() {
    return blocks;
  }

  public interface IBlock<T> {
    int id();
    List<T> elements();

    T terminator();

    Set<? extends IBlock<T>> successors();
  }

  public static class Block implements IBlock<Tree>, ControlFlowGraph.Block {
    public static final Predicate<Block> IS_CATCH_BLOCK = Block::isCatchBlock;
    private int id;
    private final List<Tree> elements = new ArrayList<>();
    private final Set<Block> successors = new LinkedHashSet<>();
    private final Set<Block> predecessors = new LinkedHashSet<>();
    private final Set<Block> exceptions = new LinkedHashSet<>();
    private Block trueBlock;
    private Block falseBlock;
    private Block exitBlock;
    private Block successorWithoutJump;

    private Tree terminator;
    private CaseGroupTree caseGroup;

    private boolean isFinallyBlock;
    private boolean isCatchBlock = false;
    private boolean isDefaultBlock = false;

    public Block(int id) {
      this.id = id;
    }

    @Override
    public int id() {
      return id;
    }

    @Override
    public List<Tree> elements() {
      return ListUtils.reverse(elements);
    }

    @Override
    public Block trueBlock() {
      return trueBlock;
    }

    @Override
    public Block falseBlock() {
      return falseBlock;
    }

    @Override
    public Block exitBlock() {
      return exitBlock;
    }

    @Override
    public boolean isFinallyBlock() {
      return isFinallyBlock;
    }

    @Override
    public boolean isCatchBlock() {
      return isCatchBlock;
    }

    @Override
    public boolean isDefaultBlock() {
      return isDefaultBlock;
    }

    void addSuccessor(Block successor) {
      successors.add(successor);
    }

    public void addTrueSuccessor(Block successor) {
      if (trueBlock != null) {
        throw new IllegalStateException("Attempt to re-assign a true successor");
      }
      successors.add(successor);
      trueBlock = successor;
    }

    public void addFalseSuccessor(Block successor) {
      if (falseBlock != null) {
        throw new IllegalStateException("Attempt to re-assign a false successor");
      }
      successors.add(successor);
      falseBlock = successor;
    }

    public void addExitSuccessor(Block block) {
      successors.add(block);
      exitBlock = block;
    }

    public Set<Block> predecessors() {
      return predecessors;
    }

    @Override
    public Set<Block> successors() {
      return successors;
    }

    @Override
    public Set<Block> exceptions() {
      return exceptions;
    }

    @Override
    @CheckForNull
    public Tree terminator() {
      return terminator;
    }

    public boolean isInactive() {
      return terminator == null && elements.isEmpty() && successors.size() == 1;
    }

    private void prune(Block inactiveBlock) {
      boolean hasUniqueSuccessor = inactiveBlock.successors.size() == 1;
      if (inactiveBlock.equals(trueBlock)) {
        Preconditions.checkArgument(hasUniqueSuccessor, "True successor must be replaced by a unique successor!");
        trueBlock = inactiveBlock.successors.iterator().next();
      }
      if (inactiveBlock.equals(falseBlock)) {
        Preconditions.checkArgument(hasUniqueSuccessor, "False successor must be replaced by a unique successor!");
        falseBlock = inactiveBlock.successors.iterator().next();
      }
      if (inactiveBlock.equals(successorWithoutJump)) {
        Preconditions.checkArgument(hasUniqueSuccessor, "SuccessorWithoutJump successor must be replaced by a unique successor!");
        successorWithoutJump = inactiveBlock.successors.iterator().next();
      }
      if (successors.remove(inactiveBlock)) {
        successors.addAll(inactiveBlock.successors);
      }
      if (exceptions.remove(inactiveBlock)) {
        exceptions.addAll(inactiveBlock.exceptions);
        exceptions.addAll(inactiveBlock.successors);
      }
      if (inactiveBlock.equals(exitBlock)) {
        exitBlock = inactiveBlock.successors.iterator().next();
      }
    }

    public boolean isMethodExitBlock() {
      return successors().isEmpty();
    }

    /**
     * This method makes the implementation of RSPEC-3626 almost trivial.
     * @return the block which would be the successor of this one if this one didn't terminate with a jump
     */
    @CheckForNull
    public Block successorWithoutJump() {
      return successorWithoutJump;
    }

    /**
     * Label is used to contain additional information about a block which is not directly related to CFG structure.
     * Used for simplifying implementation of RSPEC-128.
     */
    @CheckForNull
    @Override
    public CaseGroupTree caseGroup() {
      return caseGroup;
    }

    public void setCaseGroup(CaseGroupTree caseGroup) {
      this.caseGroup = caseGroup;
    }
  }

  private static void computePredecessors(List<Block> blocks) {
    for (Block b : blocks) {
      for (Block successor : b.successors) {
        successor.predecessors.add(b);
      }
      for (Block successor : b.exceptions) {
        successor.predecessors.add(b);
      }
    }
    cleanupUnfeasibleBreakPaths(blocks);
  }

  private static void cleanupUnfeasibleBreakPaths(List<Block> blocks) {
    for (Block block : blocks) {
      Set<Block> happyPathPredecessor = block.predecessors.stream().filter(p -> !p.exceptions.contains(block)).collect(Collectors.toSet());
      if(block.isFinallyBlock && happyPathPredecessor.size() == 1) {
        Block pred = happyPathPredecessor.iterator().next();
        if (pred.terminator != null && pred.terminator.is(Tree.Kind.BREAK_STATEMENT)) {
          Set<Block> succs = block.successors.stream()
            .map(suc -> isLoop(suc) ? getAfterLoopBlock(suc) : suc)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
          block.successors.clear();
          block.successors.addAll(succs);
        }
      }
    }
  }

  private static boolean isLoop(Block successor) {
    return successor.terminator != null
      && successor.terminator.is(Tree.Kind.WHILE_STATEMENT, Tree.Kind.DO_STATEMENT, Tree.Kind.FOR_STATEMENT, Tree.Kind.FOR_EACH_STATEMENT);
  }

  @CheckForNull
  private static Block getAfterLoopBlock(Block loop) {
    if (loop.falseBlock != null) {
      return loop.falseBlock;
    }
    // Because 'for' statements without condition are unconditional jumps block
    return loop.successorWithoutJump;
  }

  private void prune() {
    List<Block> inactiveBlocks = new ArrayList<>();
    boolean first = true;
    for (Block block : blocks) {
      if (!first && isInactive(block)) {
        inactiveBlocks.add(block);
      }
      first = false;
    }
    if (!inactiveBlocks.isEmpty()) {
      removeInactiveBlocks(inactiveBlocks);
      if (inactiveBlocks.contains(currentBlock)) {
        currentBlock = currentBlock.successors.iterator().next();
      }
      int id = 0;
      for (Block block : blocks) {
        block.id = id;
        id += 1;
      }
      inactiveBlocks.removeAll(blocks);
      if (!inactiveBlocks.isEmpty()) {
        prune();
      }
    }
  }

  private boolean isInactive(Block block) {
    if (block.equals(currentBlock) && block.successors.size() > 1) {
      return false;
    }
    return block.isInactive();
  }

  private void removeInactiveBlocks(List<Block> inactiveBlocks) {
    for (Block inactiveBlock : inactiveBlocks) {
      for (Block block : blocks) {
        block.prune(inactiveBlock);
      }
    }
    blocks.removeAll(inactiveBlocks);
  }

  private Block createBlock(Block successor) {
    Block result = createBlock();
    result.addSuccessor(successor);
    return result;
  }

  private Block createBlock() {
    Block result = new Block(blocks.size());
    blocks.add(result);
    return result;
  }

  public static CFG buildCFG(List<? extends Tree> trees) {
    return new CFG(trees, null);
  }

  public static CFG build(MethodTree tree) {
    BlockTree block = tree.block();
    Preconditions.checkArgument(block != null, "Cannot build CFG for method with no body.");
    return new CFG(block.body(), tree);
  }

  private void build(ListTree<? extends Tree> trees) {
    build((List<? extends Tree>) trees);
  }

  private void build(List<? extends Tree> trees) {
    ListUtils.reverse(trees).forEach(this::build);
  }

  private void build(Tree tree) {
    switch (tree.kind()) {
      case BLOCK:
        build(((BlockTree) tree).body());
        break;
      case RETURN_STATEMENT:
        buildReturnStatement((ReturnStatementTree) tree);
        break;
      case EXPRESSION_STATEMENT:
        build(((ExpressionStatementTree) tree).expression());
        break;
      case METHOD_INVOCATION:
        buildMethodInvocation((MethodInvocationTree) tree);
        break;
      case IF_STATEMENT:
        buildIfStatement((IfStatementTree) tree);
        break;
      case CONDITIONAL_EXPRESSION:
        buildConditionalExpression((ConditionalExpressionTree) tree);
        break;
      case VARIABLE:
        buildVariable((VariableTree) tree);
        break;
      case MULTIPLY,
        DIVIDE,
        REMAINDER,
        PLUS,
        MINUS,
        LEFT_SHIFT,
        RIGHT_SHIFT,
        UNSIGNED_RIGHT_SHIFT,
        AND,
        XOR,
        OR,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL_TO,
        LESS_THAN,
        LESS_THAN_OR_EQUAL_TO,
        EQUAL_TO,
        NOT_EQUAL_TO:
        buildBinaryExpression(tree);
        break;
      case ASSIGNMENT,
        LEFT_SHIFT_ASSIGNMENT,
        RIGHT_SHIFT_ASSIGNMENT,
        AND_ASSIGNMENT,
        REMAINDER_ASSIGNMENT,
        UNSIGNED_RIGHT_SHIFT_ASSIGNMENT,
        OR_ASSIGNMENT,
        XOR_ASSIGNMENT,
        DIVIDE_ASSIGNMENT,
        MULTIPLY_ASSIGNMENT,
        PLUS_ASSIGNMENT,
        MINUS_ASSIGNMENT:
        buildAssignment((AssignmentExpressionTree) tree);
        break;
      case MEMBER_SELECT:
        buildMemberSelect((MemberSelectExpressionTree) tree);
        break;
      case CONDITIONAL_AND:
        buildConditionalAnd((BinaryExpressionTree) tree);
        break;
      case CONDITIONAL_OR:
        buildConditionalOr((BinaryExpressionTree) tree);
        break;
      case LABELED_STATEMENT:
        buildLabeledStatement((LabeledStatementTree) tree);
        break;
      case SWITCH_STATEMENT:
        buildSwitchStatement((SwitchStatementTree) tree);
        break;
      case SWITCH_EXPRESSION:
        buildSwitchExpression((SwitchExpressionTree) tree);
        break;
      case BREAK_STATEMENT:
        buildBreakStatement((BreakStatementTree) tree);
        break;
      case YIELD_STATEMENT:
        buildYieldStatement((YieldStatementTree) tree);
        break;
      case CONTINUE_STATEMENT:
        buildContinueStatement((ContinueStatementTree) tree);
        break;
      case WHILE_STATEMENT:
        buildWhileStatement((WhileStatementTree) tree);
        break;
      case DO_STATEMENT:
        buildDoWhileStatement((DoWhileStatementTree) tree);
        break;
      case FOR_EACH_STATEMENT:
        buildForEachStatement((ForEachStatement) tree);
        break;
      case FOR_STATEMENT:
        buildForStatement((ForStatementTree) tree);
        break;
      case TRY_STATEMENT:
        buildTryStatement((TryStatementTree) tree);
        break;
      case THROW_STATEMENT:
        buildThrowStatement((ThrowStatementTree) tree);
        break;
      case SYNCHRONIZED_STATEMENT:
        buildSynchronizedStatement((SynchronizedStatementTree) tree);
        break;
      case POSTFIX_INCREMENT,
        POSTFIX_DECREMENT,
        PREFIX_INCREMENT,
        PREFIX_DECREMENT,
        UNARY_MINUS,
        UNARY_PLUS,
        BITWISE_COMPLEMENT,
        LOGICAL_COMPLEMENT:
        buildUnaryExpression((UnaryExpressionTree) tree);
        break;
      case PARENTHESIZED_EXPRESSION:
        build(((ParenthesizedTree) tree).expression());
        break;
      case ARRAY_ACCESS_EXPRESSION:
        buildArrayAccessExpression((ArrayAccessExpressionTree) tree);
        break;
      case ARRAY_DIMENSION:
        buildArrayDimension((ArrayDimensionTree) tree);
        break;
      case NEW_CLASS:
        buildNewClass((NewClassTree) tree);
        break;
      case TYPE_CAST:
        buildTypeCast(tree);
        break;
      case INSTANCE_OF:
        buildInstanceOf((InstanceOfTree) tree);
        break;
      case PATTERN_INSTANCE_OF:
        buildInstanceOf((PatternInstanceOfTree) tree);
        break;
      case NEW_ARRAY:
        buildNewArray((NewArrayTree) tree);
        break;
      // assert can be ignored by VM so skip them for now.
      case ASSERT_STATEMENT:
        buildAssertStatement((AssertStatementTree) tree);
        break;
      // store declarations as complete blocks.
      case EMPTY_STATEMENT,
        CLASS,
        RECORD,
        ENUM,
        ANNOTATION_TYPE,
        INTERFACE,
        METHOD_REFERENCE,
        LAMBDA_EXPRESSION,
        // simple instructions
        INT_LITERAL,
        LONG_LITERAL,
        DOUBLE_LITERAL,
        CHAR_LITERAL,
        FLOAT_LITERAL,
        STRING_LITERAL,
        TEXT_BLOCK,
        BOOLEAN_LITERAL,
        NULL_LITERAL:
        currentBlock.elements.add(tree);
        break;
      case IDENTIFIER:
        checkIdentifierSemantic((IdentifierTree) tree);
        currentBlock.elements.add(tree);
        break;
      case NULL_PATTERN,
        TYPE_PATTERN,
        GUARDED_PATTERN,
        RECORD_PATTERN,
        DEFAULT_PATTERN:
        buildPattern((PatternTree) tree);
        break;
      case PARAMETERIZED_TYPE:
        var typeTree = (ParameterizedTypeTree) tree;
        build(typeTree.type());
        build(typeTree.typeArguments());
        break;
      case UNBOUNDED_WILDCARD,
           EXTENDS_WILDCARD,
           SUPER_WILDCARD:
        TypeTree bound = ((WildcardTree) tree).bound();
        if (bound != null) {
          build(bound);
        }
        currentBlock.elements.add(tree);
        break;
      default:
        throw new UnsupportedOperationException(tree.kind().name() + " " + ((JavaTree) tree).getLine());
    }
  }

  private void buildReturnStatement(ReturnStatementTree returnStatement) {
    currentBlock = createUnconditionalJump(returnStatement, exitBlock(), currentBlock);
    ExpressionTree expression = returnStatement.expression();
    if (expression != null) {
      build(expression);
    }
  }

  private void buildMethodInvocation(MethodInvocationTree mit) {
    SyntaxToken location = mit.methodSelect().lastToken();
    checkSymbolSemantic(mit.methodSymbol(), "method invocation", location);
    handleExceptionalPaths(mit.methodSymbol(), location);
    currentBlock.elements.add(mit);
    build(mit.arguments());
    if (mit.methodSelect().is(Tree.Kind.MEMBER_SELECT)) {
      MemberSelectExpressionTree memberSelect = (MemberSelectExpressionTree) mit.methodSelect();
      build(memberSelect.expression());
    } else {
      build(mit.methodSelect());
    }
  }

  private void buildIfStatement(IfStatementTree ifStatementTree) {
    Block next = currentBlock;
    // process else-branch
    Block elseBlock = next;
    StatementTree elseStatement = ifStatementTree.elseStatement();
    if (elseStatement != null) {
      // if statement will create the required block.
      if (!elseStatement.is(Tree.Kind.IF_STATEMENT)) {
        currentBlock = createBlock(next);
      }
      build(elseStatement);
      elseBlock = currentBlock;
    }
    // process then-branch
    currentBlock = createBlock(next);
    build(ifStatementTree.thenStatement());
    Block thenBlock = currentBlock;
    // process condition
    currentBlock = createBranch(ifStatementTree, thenBlock, elseBlock);
    buildCondition(ifStatementTree.condition(), thenBlock, elseBlock);
  }

  private void buildConditionalExpression(ConditionalExpressionTree cond) {
    Block next = currentBlock;
    // process else-branch
    ExpressionTree elseStatement = cond.falseExpression();
    currentBlock = createBlock(next);
    build(elseStatement);
    Block elseBlock = currentBlock;
    // process then-branch
    currentBlock = createBlock(next);
    build(cond.trueExpression());
    Block thenBlock = currentBlock;
    // process condition
    currentBlock = createBranch(cond, thenBlock, elseBlock);
    buildCondition(cond.condition(), thenBlock, elseBlock);
  }

  private void buildVariable(VariableTree tree) {
    checkTypeSemantic(tree.type().symbolType(), "unknown variable type", tree.type().firstToken());
    currentBlock.elements.add(tree);
    ExpressionTree initializer = tree.initializer();
    if (initializer != null) {
      build(initializer);
    }
  }

  private void buildBinaryExpression(Tree tree) {
    BinaryExpressionTree binaryExpressionTree = (BinaryExpressionTree) tree;
    currentBlock.elements.add(tree);
    build(binaryExpressionTree.rightOperand());
    build(binaryExpressionTree.leftOperand());
  }

  private void buildAssignment(AssignmentExpressionTree tree) {
    currentBlock.elements.add(tree);
    build(tree.expression());
    // The variable is not evaluated for simple assignment as it's only used to know where to store the value: JLS8-15.26
    if (!ExpressionUtils.isSimpleAssignment(tree)) {
      build(tree.variable());
    }
  }

  private void buildMemberSelect(MemberSelectExpressionTree mse) {
    currentBlock.elements.add(mse);
    // int.class or String[].class are memberSelectExpression which expression part is not an expression.
    if (!"class".equals(mse.identifier().name())) {
      build(mse.expression());
    }
  }

  private void buildConditionalAnd(BinaryExpressionTree tree) {
    Block falseBlock = currentBlock;
    currentBlock = createBlock(falseBlock);
    // process RHS
    build(tree.rightOperand());
    // process LHS
    buildConditionalBinaryLHS(tree, currentBlock, falseBlock);
  }

  private void buildConditionalOr(BinaryExpressionTree tree) {
    Block trueBlock = currentBlock;
    currentBlock = createBlock(trueBlock);
    // process RHS
    build(tree.rightOperand());
    // process LHS
    buildConditionalBinaryLHS(tree, trueBlock, currentBlock);
  }

  private void buildConditionalBinaryLHS(BinaryExpressionTree tree, Block trueBlock, Block falseBlock) {
    currentBlock = createBlock();
    Block toComplete = currentBlock;
    build(tree.leftOperand());
    toComplete.terminator = tree;
    toComplete.addFalseSuccessor(falseBlock);
    toComplete.addTrueSuccessor(trueBlock);
  }

  private void buildLabeledStatement(LabeledStatementTree labeledStatement) {
    String name = labeledStatement.label().name();
    labelsBreakTarget.put(name, currentBlock);
    pendingLabel = name;
    currentBlock = createBlock(currentBlock);
    build(labeledStatement.statement());
    currentBlock = createBlock(currentBlock);
  }

  private void buildSwitchStatement(SwitchStatementTree switchStatementTree) {
    buildSwitch(switchStatementTree, switchStatementTree);
  }

  private void buildSwitchExpression(SwitchExpressionTree switchExpressionTree) {
    buildSwitch(switchExpressionTree, switchExpressionTree);
  }

  private void buildSwitch(SwitchTree switchTree, Tree terminator) {
    Block switchSuccessor = currentBlock;
    // process condition
    currentBlock = createBlock();
    currentBlock.terminator = terminator;
    Block switchBlock = currentBlock;
    List<ExpressionTree> switchCasesExpressions = switchTree.cases()
      .stream()
      .map(CaseGroupTree::labels)
      .flatMap(List::stream)
      .map(CaseLabelTree::expressions)
      .flatMap(List::stream).toList();
    ListUtils.reverse(switchCasesExpressions).forEach(this::build);

    build(switchTree.expression());
    Block conditionBlock = currentBlock;
    // process body
    currentBlock = createBlock(switchSuccessor);
    breakTargets.addLast(switchSuccessor);
    boolean hasDefaultCase = false;
    if (!switchTree.cases().isEmpty()) {
      boolean withoutFallTrough = switchWithoutFallThrough(switchTree);
      CaseGroupTree firstCase = switchTree.cases().get(0);
      for (CaseGroupTree caseGroupTree : ListUtils.reverse(switchTree.cases())) {
        if (withoutFallTrough) {
          currentBlock.successors().clear();
          currentBlock.addSuccessor(switchSuccessor);
        }
        build(caseGroupTree.body());
        currentBlock.elements.add(caseGroupTree);
        if (!hasDefaultCase) {
          hasDefaultCase = containsDefaultCase(caseGroupTree.labels());
          currentBlock.isDefaultBlock = hasDefaultCase;
        }
        currentBlock.setCaseGroup(caseGroupTree);
        switchBlock.addSuccessor(currentBlock);
        if (!caseGroupTree.equals(firstCase)) {
          // No block preceding the first case group.
          currentBlock = createBlock(currentBlock);
        }
      }
    }
    breakTargets.removeLast();
    // process condition
    currentBlock = switchBlock;
    if (!hasDefaultCase && !terminator.is(Tree.Kind.SWITCH_EXPRESSION)) {
      currentBlock.addSuccessor(switchSuccessor);
    }
    currentBlock = conditionBlock;
  }

  /**
   * A switch expression can use the traditional cases with 'colon' (with fall-through) or,
   * starting with java 12, the 'arrow' cases (without fall-through). Cases can not be mixed.
   *
   * @param switchTree the switch to evaluate
   * @return true if the switch uses fall-through
   */
  private static boolean switchWithoutFallThrough(SwitchTree switchTree) {
    return switchTree.cases().stream()
      .map(CaseGroupTree::labels)
      .flatMap(List::stream)
      .noneMatch(CaseLabelTree::isFallThrough);
  }

  private static boolean containsDefaultCase(List<CaseLabelTree> labels) {
    return labels.stream().anyMatch(caseLabel -> "default".equals(caseLabel.caseOrDefaultKeyword().text())
      // JDK 17 preview feature
      || caseLabel.expressions().stream().anyMatch(expr -> expr.is(Tree.Kind.DEFAULT_PATTERN)));
  }

  private void buildBreakStatement(BreakStatementTree tree) {
    IdentifierTree label = tree.label();
    boolean isLabel = false;
    Block targetBlock = null;
    if (label == null) {
      if (breakTargets.isEmpty()) {
        markSemanticAsIncomplete("'break' statement not in loop or switch statement", tree.firstToken());
      } else {
        targetBlock = breakTargets.getLast();
      }
    } else {
      isLabel = label.symbol() instanceof Symbol.LabelSymbol;
      if (isLabel) {
        targetBlock = labelsBreakTarget.get(label.name());
      } else {
        targetBlock = breakTargets.getLast();
      }
    }
    currentBlock = createUnconditionalJump(tree, targetBlock, currentBlock);
    if(currentBlock.exitBlock != null) {
      currentBlock.exitBlock = null;
    }
  }

  private void buildYieldStatement(YieldStatementTree tree) {
    currentBlock = createUnconditionalJump(tree, breakTargets.isEmpty() ? null : breakTargets.getLast(), currentBlock);
    build(tree.expression());
    currentBlock.exitBlock = null;
  }

  private void buildContinueStatement(ContinueStatementTree tree) {
    IdentifierTree label = tree.label();
    Block targetBlock = null;
    if (label == null) {
      if (continueTargets.isEmpty()) {
        markSemanticAsIncomplete("'continue' statement not in loop or switch statement", tree.firstToken());
      } else {
        targetBlock = continueTargets.getLast();
      }
    } else {
      targetBlock = labelsContinueTarget.get(label.name());
    }
    currentBlock = createUnconditionalJump(tree, targetBlock, currentBlock);
    // cleanup for continue statement to a finally: continue block can't have an exit block.
    currentBlock.exitBlock = null;
  }

  private void buildWhileStatement(WhileStatementTree whileStatement) {
    Block falseBranch = currentBlock;
    Block loopback = createBlock();
    // process body
    currentBlock = createBlock(loopback);
    addContinueTarget(loopback);
    breakTargets.addLast(falseBranch);
    build(whileStatement.statement());
    breakTargets.removeLast();
    continueTargets.removeLast();
    Block bodyBlock = currentBlock;
    // process condition
    currentBlock = createBranch(whileStatement, bodyBlock, falseBranch);
    buildCondition(whileStatement.condition(), bodyBlock, falseBranch);
    loopback.addSuccessor(currentBlock);
    currentBlock = createBlock(currentBlock);
  }

  private void buildDoWhileStatement(DoWhileStatementTree doWhileStatementTree) {
    Block falseBranch = currentBlock;
    Block loopback = createBlock();
    // process condition
    currentBlock = createBranch(doWhileStatementTree, loopback, falseBranch);
    buildCondition(doWhileStatementTree.condition(), loopback, falseBranch);
    // process body
    addContinueTarget(currentBlock);
    currentBlock = createBlock(currentBlock);
    breakTargets.addLast(falseBranch);
    build(doWhileStatementTree.statement());
    breakTargets.removeLast();
    continueTargets.removeLast();
    loopback.addSuccessor(currentBlock);
    currentBlock = createBlock(currentBlock);
  }

  private void buildForEachStatement(ForEachStatement tree) {
    // TODO(npe) One solution is to create a forstatement node depending on type of expression (iterable or array) and build CFG from it.
    Block afterLoop = currentBlock;
    Block statementBlock = createBlock();
    Block loopback = createBranch(tree, statementBlock, afterLoop);
    currentBlock = createBlock(loopback);
    addContinueTarget(loopback);
    breakTargets.addLast(afterLoop);
    build(tree.statement());
    breakTargets.removeLast();
    continueTargets.removeLast();
    statementBlock.addSuccessor(currentBlock);
    currentBlock = loopback;
    build(tree.variable());
    currentBlock = createBlock(currentBlock);
    build(tree.expression());
    currentBlock = createBlock(currentBlock);
  }

  private void addContinueTarget(Block target) {
    continueTargets.addLast(target);
    if (pendingLabel != null) {
      labelsContinueTarget.put(pendingLabel, target);
      pendingLabel = null;
    }
  }

  private void buildForStatement(ForStatementTree tree) {
    Block falseBranch = currentBlock;
    // process step
    currentBlock = createBlock();
    Block updateBlock = currentBlock;
    build(tree.update());
    addContinueTarget(currentBlock);
    // process body
    currentBlock = createBlock(currentBlock);
    breakTargets.addLast(falseBranch);
    build(tree.statement());
    breakTargets.removeLast();
    continueTargets.removeLast();
    Block body = currentBlock;
    // process condition
    ExpressionTree condition = tree.condition();
    if (condition != null) {
      currentBlock = createBranch(tree, body, falseBranch);
      buildCondition(condition, body, falseBranch);
    } else {
      currentBlock = createUnconditionalJump(tree, body, falseBranch);
    }
    updateBlock.addSuccessor(currentBlock);
    // process init
    currentBlock = createBlock(currentBlock);
    build(tree.initializer());
  }

  private void buildTryStatement(TryStatementTree tryStatementTree) {
    // FIXME only path with no failure constructed for now, (not taking try with resources into consideration).
    currentBlock = createBlock(currentBlock);
    BlockTree finallyBlockTree = tryStatementTree.finallyBlock();
    if (finallyBlockTree != null) {
      currentBlock.isFinallyBlock = true;
      Block finallyBlock = currentBlock;
      build(finallyBlockTree);
      finallyBlock.addExitSuccessor(exitBlock());
      exitBlocks.push(currentBlock);
      addContinueTarget(currentBlock);
      currentBlock.isFinallyBlock = true;
      breakTargets.addLast(currentBlock);
    }
    Block finallyOrEndBlock = currentBlock;
    Block beforeFinally = createBlock(currentBlock);
    TryStatement tryStatement = new TryStatement();
    enclosingTry.push(tryStatement);
    enclosedByCatch.push(false);
    for (CatchTree catchTree : ListUtils.reverse(tryStatementTree.catches())) {
      currentBlock = createBlock(finallyOrEndBlock);
      enclosedByCatch.push(true);
      build(catchTree.block());
      buildVariable(catchTree.parameter());
      currentBlock.isCatchBlock = true;
      enclosedByCatch.pop();
      TypeTree type = catchTree.parameter().type();
      tryStatement.addCatch(checkTypeSemantic(type.symbolType(), "catch parameter type", type.firstToken()), currentBlock);
    }
    currentBlock = beforeFinally;
    build(tryStatementTree.block());
    build((List<? extends Tree>) tryStatementTree.resourceList());
    enclosingTry.pop();
    enclosedByCatch.pop();
    currentBlock = createBlock(currentBlock);
    currentBlock.elements.add(tryStatementTree);
    if (finallyBlockTree != null) {
      exitBlocks.pop();
      continueTargets.removeLast();
      breakTargets.removeLast();
    }
  }

  private void buildThrowStatement(ThrowStatementTree throwStatementTree) {
    Block jumpTo = exitBlock();
    TryStatement enclosingTryCatch = enclosingTry.peek();
    if(enclosingTryCatch != null){
      jumpTo = enclosingTryCatch.catches.keySet().stream()
        .filter(t -> checkTypeSemantic(throwStatementTree.expression().symbolType(), "thrown expression", throwStatementTree.expression().firstToken()).isSubtypeOf(t))
        .findFirst()
        .map(t -> enclosingTryCatch.catches.get(t))
        .orElse(exitBlock());
    }
    currentBlock = createUnconditionalJump(throwStatementTree, jumpTo, currentBlock);
    build(throwStatementTree.expression());
  }

  private void buildSynchronizedStatement(SynchronizedStatementTree sst) {
    // First create the block of the statement,
    build(sst.block());
    // Then create a single block with the SYNCHRONIZED tree as terminator
    currentBlock = createUnconditionalJump(sst, currentBlock, null);
    build(sst.expression());
  }

  private void buildUnaryExpression(UnaryExpressionTree tree) {
    currentBlock.elements.add(tree);
    build(tree.expression());
  }

  private void buildArrayAccessExpression(ArrayAccessExpressionTree tree) {
    currentBlock.elements.add(tree);
    build(tree.dimension());
    build(tree.expression());
  }

  private void buildArrayDimension(ArrayDimensionTree tree) {
    ExpressionTree expression = tree.expression();
    if (expression != null) {
      build(expression);
    }
  }

  private void buildNewClass(NewClassTree tree) {
    SyntaxToken location = tree.identifier().lastToken();
    checkSymbolSemantic(tree.methodSymbol(), "new class", location);
    handleExceptionalPaths(tree.methodSymbol(), location);
    currentBlock.elements.add(tree);
    build(tree.arguments());
    ExpressionTree enclosingExpression = tree.enclosingExpression();
    if (enclosingExpression != null) {
      build(enclosingExpression);
    }
  }

  private void handleExceptionalPaths(Symbol.MethodSymbol symbol, @Nullable SyntaxToken location) {
    TryStatement pop = enclosingTry.pop();
    TryStatement tryStatement;
    Block exceptionPredecessor = currentBlock;
    if (Boolean.TRUE.equals(enclosedByCatch.peek())) {
      tryStatement = enclosingTry.peek();
    } else {
      tryStatement = pop;
    }
    enclosingTry.push(pop);
    if(pop != outerTry) {
      currentBlock = createBlock(currentBlock);
      currentBlock.exceptions.add(exitBlocks.peek());
      if (!Boolean.TRUE.equals(enclosedByCatch.peek())) {
        exceptionPredecessor = currentBlock;
      }
    }
    if (!symbol.isUnknown()) {
      List<Type> thrownTypes = symbol.thrownTypes();
      thrownTypes.forEach(thrownType -> {
        checkTypeSemantic(thrownType, "thrown types", location);
        for (Type caughtType : tryStatement.catches.keySet()) {
          if (thrownType.isSubtypeOf(caughtType) ||
            caughtType.isSubtypeOf(thrownType) ||
            thrownType.isUnknown() ||
            // note that this condition is not necessary, because unknown type will be added to runtimeCatches due to condition in
            // org.sonar.java.cfg.CFG.TryStatement#addCatch ,however, it is here for clarity
            caughtType.isUnknown()) {
            currentBlock.exceptions.add(tryStatement.catches.get(caughtType));
          }
        }
      });
    }
    exceptionPredecessor.exceptions.addAll(tryStatement.runtimeCatches);
  }

  private void buildTypeCast(Tree tree) {
    enclosingTry.peek().catches.entrySet().stream()
      .filter(e -> e.getKey().isSubtypeOf("java.lang.ClassCastException"))
      .findFirst()
      .ifPresent(e -> {
        currentBlock = createBlock(currentBlock);
        currentBlock.successors.add(e.getValue());
    });
    currentBlock.elements.add(tree);
    TypeCastTree typeCastTree = (TypeCastTree) tree;
    build(typeCastTree.expression());
  }

  private void buildInstanceOf(InstanceOfTree instanceOfTree) {
    currentBlock.elements.add(instanceOfTree);
    build(instanceOfTree.expression());
  }

  private void buildInstanceOf(PatternInstanceOfTree instanceOfTree) {
    currentBlock.elements.add(instanceOfTree);
    build(instanceOfTree.pattern());
    build(instanceOfTree.expression());
  }

  private void buildNewArray(NewArrayTree tree) {
    currentBlock.elements.add(tree);
    build(tree.dimensions());
    build(tree.initializers());
  }

  private void buildAssertStatement(AssertStatementTree assertStatementTree) {
    currentBlock.elements.add(assertStatementTree);
    // Ignore detail expression as it is only evaluated when assertion is false.
    build(assertStatementTree.condition());
  }

  private Block createUnconditionalJump(Tree terminator, @Nullable Block target, @Nullable Block successorWithoutJump) {
    Block result = createBlock();
    result.terminator = terminator;
    if (target != null) {
      if (target == exitBlock()) {
        result.addExitSuccessor(target);
      } else {
        result.addSuccessor(target);
      }
    }
    result.successorWithoutJump = successorWithoutJump;
    return result;
  }

  private void buildCondition(Tree syntaxNode, Block trueBlock, Block falseBlock) {
    switch (syntaxNode.kind()) {
      case CONDITIONAL_OR:
        buildConditionalOr((BinaryExpressionTree) syntaxNode, trueBlock, falseBlock);
        break;
      case CONDITIONAL_AND:
        // process RHS
        buildConditionalAnd((BinaryExpressionTree) syntaxNode, trueBlock, falseBlock);
        break;
      // Skip syntactic sugar:
      case PARENTHESIZED_EXPRESSION:
        buildCondition(((ParenthesizedTree) syntaxNode).expression(), trueBlock, falseBlock);
        break;
      default:
        build(syntaxNode);
        break;
    }
  }

  private void buildConditionalOr(BinaryExpressionTree conditionalOr, Block trueBlock, Block falseBlock) {
    // process RHS
    buildCondition(conditionalOr.rightOperand(), trueBlock, falseBlock);
    Block newFalseBlock = currentBlock;
    // process LHS
    currentBlock = createBranch(conditionalOr, trueBlock, newFalseBlock);
    buildCondition(conditionalOr.leftOperand(), trueBlock, newFalseBlock);
  }

  private void buildConditionalAnd(BinaryExpressionTree conditionalAnd, Block trueBlock, Block falseBlock) {
    buildCondition(conditionalAnd.rightOperand(), trueBlock, falseBlock);
    Block newTrueBlock = currentBlock;
    // process LHS
    currentBlock = createBranch(conditionalAnd, newTrueBlock, falseBlock);
    buildCondition(conditionalAnd.leftOperand(), newTrueBlock, falseBlock);
  }

  private Block createBranch(Tree terminator, Block trueBranch, Block falseBranch) {
    Block result = createBlock();
    result.terminator = terminator;
    result.addFalseSuccessor(falseBranch);
    result.addTrueSuccessor(trueBranch);
    return result;
  }

  public void setMethodSymbol(Symbol.MethodSymbol methodSymbol) {
    this.methodSymbol = methodSymbol;
  }

  private void buildPattern(PatternTree tree) {
    switch (tree.kind()) {
      case NULL_PATTERN:
        buildNullPattern((NullPatternTree) tree);
        break;
      case TYPE_PATTERN:
        buildTypePattern((TypePatternTree) tree);
        break;
      case GUARDED_PATTERN:
        buildGuardedPattern((GuardedPatternTree) tree);
        break;
      case RECORD_PATTERN:
        buildRecordPattern((RecordPatternTree) tree);
        break;
      case DEFAULT_PATTERN:
        // do nothing - handled when building the switch
        currentBlock.elements.add(tree);
        break;
      default:
        throw new UnsupportedOperationException(tree.kind().name() + " " + ((JavaTree) tree).getLine());
    }
  }

  private void buildNullPattern(NullPatternTree tree) {
    currentBlock.elements.add(tree.nullLiteral());
    currentBlock.elements.add(tree);
  }

  private void buildTypePattern(TypePatternTree tree) {
    buildVariable(tree.patternVariable());
    currentBlock.elements.add(tree);
  }

  private void buildGuardedPattern(GuardedPatternTree tree) {
    // reverted order
    build(tree.expression());
    build(tree.pattern());
    currentBlock.elements.add(tree);
  }

  private void buildRecordPattern(RecordPatternTree tree) {
    build(tree.patterns());
    build(tree.type());
    currentBlock.elements.add(tree);
  }

}
