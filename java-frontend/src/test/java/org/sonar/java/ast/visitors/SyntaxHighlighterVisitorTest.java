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
package org.sonar.java.ast.visitors;

import com.google.common.io.Files;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.sensor.highlighting.TypeOfText;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.java.JavaFrontend;
import org.sonar.java.Measurer;
import org.sonar.java.SonarComponents;
import org.sonar.java.TestUtils;
import org.sonar.java.classpath.ClasspathForMain;
import org.sonar.java.classpath.ClasspathForTest;
import org.sonar.java.model.JParserConfig;
import org.sonar.plugins.java.api.JavaVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@EnableRuleMigrationSupport
class SyntaxHighlighterVisitorTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private SensorContextTester context;
  private SonarComponents sonarComponents;

  private SyntaxHighlighterVisitor syntaxHighlighterVisitor;

  @BeforeEach
  void setUp() {
    context = SensorContextTester.create(temp.getRoot());
    sonarComponents = new SonarComponents(mock(FileLinesContextFactory.class), context.fileSystem(),
      mock(ClasspathForMain.class), mock(ClasspathForTest.class), mock(CheckFactory.class), mock(ActiveRules.class));
    sonarComponents.setSensorContext(context);
    syntaxHighlighterVisitor = new SyntaxHighlighterVisitor(sonarComponents);
  }

  @Test
  void parse_error() throws Exception {
    SensorContextTester spy = spy(context);
    File file = temp.newFile().getAbsoluteFile();
    Files.asCharSink(file, StandardCharsets.UTF_8).write("ParseError");
    scan(TestUtils.inputFile(file));
    verify(spy, never()).newHighlighting();
  }

  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r\n", "\r"})
  void test_different_end_of_line(String eol) {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/Example.java");
    scan(inputFile);
    verifyHighlighting(inputFile);
  }

  /**
   * Java 9 modules introduces restricted keywords only used in their context
   */
  @Test
  void test_restricted_keywords_within_module() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/module-info.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 1, 1, 3, 4, TypeOfText.COMMENT);
    assertThatHasBeenHighlighted(componentKey, 4, 1, 4, 7, TypeOfText.KEYWORD); // import
    assertThatHasBeenHighlighted(componentKey, 6, 1, 8, 4, TypeOfText.STRUCTURED_COMMENT);
    assertThatHasBeenHighlighted(componentKey, 9, 1, 9, 6, TypeOfText.ANNOTATION); // @Beta
    assertThatHasBeenHighlighted(componentKey, 10, 1, 10, 5, TypeOfText.KEYWORD); // open
    assertThatHasBeenHighlighted(componentKey, 10, 6, 10, 12, TypeOfText.KEYWORD); // module
    assertThatHasBeenHighlighted(componentKey, 11, 3, 11, 11, TypeOfText.KEYWORD); // requires
    assertThatHasBeenHighlighted(componentKey, 11, 12, 11, 22, /* due to bug in ECJ */ TypeOfText.KEYWORD); // transitive as identifier
    assertThatHasBeenHighlighted(componentKey, 12, 3, 12, 11, TypeOfText.KEYWORD); // requires
    assertThatHasBeenHighlighted(componentKey, 12, 12, 12, 18, TypeOfText.KEYWORD); // static
    assertThatHasBeenHighlighted(componentKey, 12, 19, 12, 29, /* due to bug in ECJ */ TypeOfText.KEYWORD); // transitive as identifier
    assertThatHasBeenHighlighted(componentKey, 13, 3, 13, 11, TypeOfText.KEYWORD); // requires
    assertThatHasBeenHighlighted(componentKey, 13, 12, 13, 18, TypeOfText.KEYWORD); // static
    assertThatHasBeenHighlighted(componentKey, 13, 19, 13, 29, TypeOfText.KEYWORD); // transitive as keyword
    assertThatHasBeenHighlighted(componentKey, 14, 3, 14, 10, TypeOfText.KEYWORD); // exports
    assertThatHasBeenHighlighted(componentKey, 14, 19, 14, 21, TypeOfText.KEYWORD); // to
    assertThatHasBeenHighlighted(componentKey, 15, 3, 15, 8, TypeOfText.KEYWORD); // opens
    assertThatHasBeenHighlighted(componentKey, 15, 17, 15, 19, TypeOfText.KEYWORD); // to
    assertThatHasBeenHighlighted(componentKey, 16, 3, 16, 7, TypeOfText.KEYWORD); // uses
    assertThatHasBeenHighlighted(componentKey, 17, 3, 17, 11, TypeOfText.KEYWORD); // provides
    assertThatHasBeenHighlighted(componentKey, 17, 26, 17, 30, TypeOfText.KEYWORD); // with

    // usages of restricted keywords in module name and package names
    assertThatHasBeenHighlighted(componentKey, 18, 3, 18, 60, TypeOfText.COMMENT);
    assertThatHasBeenHighlighted(componentKey, 19, 3, 19, 10, TypeOfText.KEYWORD); // exports
    assertThatHasNotBeenHighlighted(componentKey, 19, 28, 19, 34); // 'module' used in package name
    assertThatHasBeenHighlighted(componentKey, 20, 3, 20, 11, TypeOfText.KEYWORD); // provides
    assertThatHasBeenHighlighted(componentKey, 20, 34, 20, 38, TypeOfText.KEYWORD); // with
    assertThatHasNotBeenHighlighted(componentKey, 20, 45, 20, 49); // 'with' used in package name
    assertThatHasNotBeenHighlighted(componentKey, 20, 50, 20, 52); // 'to' used in package name
    assertThatHasNotBeenHighlighted(componentKey, 20, 53, 20, 60); // 'exports' used in package name
    assertThatHasNotBeenHighlighted(componentKey, 20, 61, 20, 67); // 'module' used in package name
  }

  @Test
  void test_restricted_keywords_outside_module() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/ExampleWithModuleKeywords.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 1, 1, 3, 4, TypeOfText.COMMENT);
    assertThatHasBeenHighlighted(componentKey, 4, 1, 4, 7, TypeOfText.KEYWORD); // import
    assertThatHasBeenHighlighted(componentKey, 6, 1, 8, 4, TypeOfText.STRUCTURED_COMMENT);
    assertThatHasBeenHighlighted(componentKey, 9, 1, 9, 6, TypeOfText.ANNOTATION); // @Beta
    assertThatHasBeenHighlighted(componentKey, 10, 1, 10, 9, TypeOfText.KEYWORD); // abstract
    assertThatHasBeenHighlighted(componentKey, 10, 10, 10, 15, TypeOfText.KEYWORD); // class
    assertThatHasNotBeenHighlighted(componentKey, 10, 16, 10, 22); // module
    assertThatHasNotBeenHighlighted(componentKey, 11, 10, 11, 14); // open
    assertThatHasNotBeenHighlighted(componentKey, 11, 16, 11, 26); // transitive
    assertThatHasBeenHighlighted(componentKey, 13, 3, 13, 7, TypeOfText.KEYWORD); // void
    assertThatHasNotBeenHighlighted(componentKey, 13, 8, 13, 16); // requires
    assertThatHasNotBeenHighlighted(componentKey, 13, 24, 13, 31); // exports
    assertThatHasNotBeenHighlighted(componentKey, 13, 40, 13, 45); // opens
    assertThatHasBeenHighlighted(componentKey, 14, 5, 14, 8, TypeOfText.KEYWORD); // int
    assertThatHasNotBeenHighlighted(componentKey, 14, 9, 14, 11); // to
    assertThatHasBeenHighlighted(componentKey, 15, 5, 15, 11, TypeOfText.KEYWORD); // double
    assertThatHasNotBeenHighlighted(componentKey, 15, 12, 15, 16); // with
    assertThatHasNotBeenHighlighted(componentKey, 16, 12, 16, 16); // uses
    assertThatHasNotBeenHighlighted(componentKey, 17, 5, 17, 13); // provides
    assertThatHasBeenHighlighted(componentKey, 20, 3, 21, 28, TypeOfText.STRUCTURED_COMMENT); // markdown javadoc
    assertThatHasBeenHighlighted(componentKey, 22, 3, 22, 11, TypeOfText.KEYWORD); // abstract
    assertThatHasBeenHighlighted(componentKey, 22, 12, 22, 16, TypeOfText.KEYWORD); // void
    assertThatHasNotBeenHighlighted(componentKey, 22, 17, 22, 25); // provides
  }

  @Test
  void test_java10_var() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/Java10Var.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 10, 5, 10, 8, TypeOfText.KEYWORD); // var a = ...
    assertThatHasBeenHighlighted(componentKey, 12, 5, 12, 8, TypeOfText.KEYWORD); // var list = ...
    assertThatHasBeenHighlighted(componentKey, 17, 10, 17, 13, TypeOfText.KEYWORD); // for (var counter = ...
    assertThatHasBeenHighlighted(componentKey, 21, 10, 21, 13, TypeOfText.KEYWORD); // for (var value : ...
    assertThatHasBeenHighlighted(componentKey, 27, 10, 27, 13, TypeOfText.KEYWORD); // try (var reader = ...
    assertThatHasBeenHighlighted(componentKey, 32, 5, 32, 8, TypeOfText.KEYWORD); // var myA = new A() { ...
    assertThatHasNotBeenHighlighted(componentKey, 51, 12, 51, 15); // Object var;
  }

  @Test
  void text_block() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/TextBlock.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 3, 27, 5, 6, TypeOfText.STRING);
    assertThatHasBeenHighlighted(componentKey, 8, 12, 10, 8, TypeOfText.STRING);
  }

  /**
   * Java 15
   */
  @Test
  void switch_expression() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/SwitchExpression.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 9, 9, 9, 14, TypeOfText.KEYWORD); // yield true
    assertThatHasBeenHighlighted(componentKey, 13, 9, 13, 14, TypeOfText.KEYWORD); // yield false
    assertThatHasBeenHighlighted(componentKey, 19, 7, 19, 14, TypeOfText.KEYWORD); // default
    assertThatHasNotBeenHighlighted(componentKey, 19, 18, 19, 23); // yield as identifier
  }

  /**
   * Java 19 (third preview of pattern matching)
   */
  @Test
  void switch_pattern_expression_with_when_keyword() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/SwitchPatternExpression.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 7, 7, 7, 11, TypeOfText.KEYWORD); // case
    assertThatHasNotBeenHighlighted(componentKey, 7, 12, 7, 17); // Point
    assertThatHasNotBeenHighlighted(componentKey, 7, 18, 7, 19); // p
    assertThatHasBeenHighlighted(componentKey, 7, 20, 7, 24, TypeOfText.KEYWORD); // "when" as keyword
    assertThatHasNotBeenHighlighted(componentKey, 7, 27, 7, 31); // "when" as method name

    assertThatHasBeenHighlighted(componentKey, 8, 18, 8, 21, TypeOfText.KEYWORD); // "int" as type inside record pattern
  }

  /**
   * Java 16
   */
  @Test
  void records() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/Records.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 3, 1, 3, 7, TypeOfText.KEYWORD); // record
    assertThatHasNotBeenHighlighted(componentKey, 4, 14, 4, 20); // record as identifier
  }

  /**
   * Java 16 (second preview)
   */
  @Test
  void sealed_classes() {
    InputFile inputFile = generateTestFile("src/test/files/highlighter/SealedClass.java");
    scan(inputFile);

    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 4, 19, 4, 25, TypeOfText.KEYWORD); // sealed
    assertThatHasNotBeenHighlighted(componentKey, 5, 35, 5, 41); // sealed as variable name

    assertThatHasBeenHighlighted(componentKey, 4, 33, 4, 40, TypeOfText.KEYWORD); // permits
    assertThatHasNotBeenHighlighted(componentKey, 7, 11, 7, 18); // permits as variable name

    assertThatHasBeenHighlighted(componentKey, 14, 10, 14, 20, TypeOfText.KEYWORD); // non-sealed
    // TODO fixme ECJ bug? should not require spaces
    assertThatHasNotBeenHighlighted(componentKey, 7, 21, 7, 23); // non-sealed as expression

    assertThatHasBeenHighlighted(componentKey, 16, 10, 16, 16, TypeOfText.KEYWORD); // record
  }

  private void scan(InputFile inputFile) {
    JavaVersion javaVersion = JParserConfig.MAXIMUM_SUPPORTED_JAVA_VERSION;
    JavaFrontend frontend = new JavaFrontend(javaVersion, null, mock(Measurer.class), null, null, syntaxHighlighterVisitor);
    frontend.scan(Collections.singletonList(inputFile), Collections.emptyList(), Collections.emptyList());
  }

  private InputFile generateTestFile(String sourceFile) {
    return TestUtils.inputFile(new File(sourceFile));
  }

  private void verifyHighlighting(InputFile inputFile) {
    String componentKey = inputFile.key();
    assertThatHasBeenHighlighted(componentKey, 1, 1, 3, 4, TypeOfText.COMMENT);
    assertThatHasBeenHighlighted(componentKey, 5, 1, 7, 4, TypeOfText.STRUCTURED_COMMENT);
    assertThatHasBeenHighlighted(componentKey, 8, 1, 8, 18, TypeOfText.ANNOTATION);
    assertThatHasBeenHighlighted(componentKey, 8, 19, 8, 27, TypeOfText.STRING);
    assertThatHasBeenHighlighted(componentKey, 9, 1, 9, 6, TypeOfText.KEYWORD);
    assertThatHasBeenHighlighted(componentKey, 11, 3, 11, 24, TypeOfText.ANNOTATION);
    assertThatHasBeenHighlighted(componentKey, 12, 3, 12, 6, TypeOfText.KEYWORD);
    assertThatHasBeenHighlighted(componentKey, 13, 5, 13, 11, TypeOfText.KEYWORD);
    assertThatHasBeenHighlighted(componentKey, 13, 12, 13, 14, TypeOfText.CONSTANT);
    assertThatHasBeenHighlighted(componentKey, 18, 1, 18, 18, TypeOfText.COMMENT);
    assertThatHasBeenHighlighted(componentKey, 19, 1, 19, 11, TypeOfText.KEYWORD);
    assertThatHasBeenHighlighted(componentKey, 20, 21, 20, 28, TypeOfText.KEYWORD);
    assertThatHasBeenHighlighted(componentKey, 20, 29, 20, 30, TypeOfText.CONSTANT);
    assertThatHasBeenHighlighted(componentKey, 23, 1, 23, 10, TypeOfText.KEYWORD);
    assertThatHasBeenHighlighted(componentKey, 24, 3, 24, 7, TypeOfText.KEYWORD);
  }

  private void assertThatHasBeenHighlighted(String componentKey, int startLine, int startColumn, int endLine, int endColumn, TypeOfText expected) {
    assertThat(context.highlightingTypeAt(componentKey, startLine, startColumn - 1)).hasSize(1).contains(expected);
    // -1 because of offset (column start at 0) and -1 to be within the range.
    assertThat(context.highlightingTypeAt(componentKey, endLine, endColumn - 1 - 1)).hasSize(1).contains(expected);
  }

  private void assertThatHasNotBeenHighlighted(String componentKey, int startLine, int startColumn, int endLine, int endColumn) {
    assertThat(context.highlightingTypeAt(componentKey, startLine, startColumn - 1)).isEmpty();
    // -1 because of offset (column start at 0) and -1 to be within the range.
    assertThat(context.highlightingTypeAt(componentKey, endLine, endColumn - 1 - 1)).isEmpty();
  }

}
