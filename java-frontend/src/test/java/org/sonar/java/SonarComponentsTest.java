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
package org.sonar.java;

import com.sonar.sslr.api.RecognitionException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import javax.annotation.CheckForNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;
import org.sonar.api.SonarEdition;
import org.sonar.api.SonarQubeSide;
import org.sonar.api.SonarRuntime;
import org.sonar.api.batch.bootstrap.ProjectDefinition;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.rule.Checks;
import org.sonar.api.batch.rule.internal.ActiveRulesBuilder;
import org.sonar.api.batch.rule.internal.NewActiveRule;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.highlighting.NewHighlighting;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.batch.sensor.issue.Issue;
import org.sonar.api.batch.sensor.symbol.NewSymbolTable;
import org.sonar.api.config.Configuration;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.internal.SonarRuntimeImpl;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.RuleScope;
import org.sonar.api.testfixtures.log.LogTesterJUnit5;
import org.sonar.api.utils.Version;
import org.sonar.check.Rule;
import org.sonar.java.classpath.ClasspathForMain;
import org.sonar.java.classpath.ClasspathForTest;
import org.sonar.java.exceptions.ApiMismatchException;
import org.sonar.java.model.GeneratedFile;
import org.sonar.java.model.JParserTestUtils;
import org.sonar.java.model.JavaTree;
import org.sonar.java.reporting.AnalyzerMessage;
import org.sonar.java.testing.ThreadLocalLogTester;
import org.sonar.plugins.java.api.CheckRegistrar;
import org.sonar.plugins.java.api.JavaCheck;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.JspCodeVisitor;
import org.sonar.plugins.java.api.caching.SonarLintCache;
import org.sonarsource.sonarlint.core.plugin.commons.sonarapi.SonarLintRuntimeImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonar.java.TestUtils.computeLineEndOffsets;

@ExtendWith(MockitoExtension.class)
class SonarComponentsTest {

  private static final Version V8_9 = Version.create(8, 9);

  private static final String REPOSITORY_NAME = "custom";

  private static final String LOG_MESSAGE_FILES_CAN_BE_SKIPPED =
    "The Java analyzer is running in a context where unchanged files can be skipped. " +
      "Full analysis is performed for changed files, optimized analysis for unchanged files.";
  private static final String LOG_MESSAGE_FILES_CANNOT_BE_SKIPPED =
    "The Java analyzer cannot skip unchanged files in this context. A full analysis is performed for all files.";
  private static final String LOG_MESSAGE_CANNOT_DETERMINE_IF_FILES_CAN_BE_SKIPPED =
    "Cannot determine whether the context allows skipping unchanged files: " +
      "canSkipUnchangedFiles not part of sonar-plugin-api. Not skipping. {}";

  private static final String DEFAULT_PATH = Path.of("src", "main", "java", "com", "acme", "Source.java").toString();

  @Mock
  private FileLinesContextFactory fileLinesContextFactory;

  @Mock
  private CheckFactory checkFactory;

  @Mock
  private Checks<JavaCheck> checks;

  @Mock
  private SensorContext context;

  @RegisterExtension
  public ThreadLocalLogTester logTester = new ThreadLocalLogTester().setLevel(Level.DEBUG);

  @BeforeEach
  void setUp() {
    // configure mocks that need verification
    Mockito.lenient().when(this.checkFactory.<JavaCheck>create(anyString())).thenReturn(this.checks);
    Mockito.lenient().when(this.checks.addAnnotatedChecks(any(Iterable.class))).thenReturn(this.checks);
  }

  public void postTestExecutionChecks() {
    // each time a SonarComponent is instantiated the following methods must be called twice
    // once for custom checks, once for custom java checks
    verify(this.checkFactory, times(2)).create(REPOSITORY_NAME);
    verify(this.checks, times(2)).addAnnotatedChecks(any(Iterable.class));
    verify(this.checks, times(2)).all();
  }

  @Test
  void base_and_work_directories() {
    File baseDir = new File("");
    File workDir = new File("target");
    SensorContextTester specificContext = SensorContextTester.create(baseDir);
    DefaultFileSystem fs = specificContext.fileSystem();
    fs.setWorkDir(workDir.toPath());

    SonarComponents sonarComponents = new SonarComponents(
      fileLinesContextFactory, fs, null, mock(ClasspathForTest.class), checkFactory, specificContext.activeRules());

    assertThat(sonarComponents.projectLevelWorkDir()).isEqualTo(workDir);
  }

  @Test
  void set_work_directory_using_project_definition() {
    File baseDir = new File("");
    File workDir = new File("target");
    SensorContextTester specificContext = SensorContextTester.create(baseDir);
    DefaultFileSystem fs = specificContext.fileSystem();
    fs.setWorkDir(workDir.toPath());
    ProjectDefinition parentProjectDefinition = ProjectDefinition.create();
    parentProjectDefinition.setWorkDir(workDir);
    ProjectDefinition childProjectDefinition = ProjectDefinition.create();
    parentProjectDefinition.addSubProject(childProjectDefinition);
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, fs, null, mock(ClasspathForTest.class),
      checkFactory, specificContext.activeRules(), childProjectDefinition);
    assertThat(sonarComponents.projectLevelWorkDir()).isEqualTo(workDir);
  }

  @Test
  void test_sonar_components() {
    SensorContextTester sensorContextTester = spy(SensorContextTester.create(new File("")));
    DefaultFileSystem fs = sensorContextTester.fileSystem();
    ClasspathForTest javaTestClasspath = mock(ClasspathForTest.class);
    List<File> javaTestClasspathList = Collections.emptyList();
    when(javaTestClasspath.getElements()).thenReturn(javaTestClasspathList);
    InputFile inputFile = TestUtils.emptyInputFile("foo.java");
    fs.add(inputFile);
    FileLinesContext fileLinesContext = mock(FileLinesContext.class);
    when(fileLinesContextFactory.createFor(any(InputFile.class))).thenReturn(fileLinesContext);

    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, fs, null, javaTestClasspath,
      checkFactory, context.activeRules());
    sonarComponents.setSensorContext(sensorContextTester);

    List<JavaCheck> visitors = sonarComponents.mainChecks();
    assertThat(visitors).isEmpty();
    Collection<JavaCheck> testChecks = sonarComponents.testChecks();
    assertThat(testChecks).isEmpty();
    assertThat(sonarComponents.getJavaClasspath()).isEmpty();
    assertThat(sonarComponents.getJavaTestClasspath()).isEqualTo(javaTestClasspathList);
    NewHighlighting newHighlighting = sonarComponents.highlightableFor(inputFile);
    assertThat(newHighlighting).isNotNull();
    verify(sensorContextTester, times(1)).newHighlighting();
    NewSymbolTable newSymbolTable = sonarComponents.symbolizableFor(inputFile);
    assertThat(newSymbolTable).isNotNull();
    verify(sensorContextTester, times(1)).newSymbolTable();
    assertThat(sonarComponents.fileLinesContextFor(inputFile)).isEqualTo(fileLinesContext);
    assertThat(sonarComponents.context()).isSameAs(sensorContextTester);

    ClasspathForMain javaClasspath = mock(ClasspathForMain.class);
    List<File> list = mock(List.class);
    when(javaClasspath.getElements()).thenReturn(list);
    sonarComponents = new SonarComponents(fileLinesContextFactory, fs, javaClasspath, javaTestClasspath,
      checkFactory, context.activeRules());
    assertThat(sonarComponents.getJavaClasspath()).isEqualTo(list);
  }

  @Test
  void verify_registration_logging_doesnt_trigger_on_info_level() {
    logTester.setLevel(Level.INFO);

    JavaCheck expectedCheck = new CustomCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);
    SonarComponents sonarComponents = new SonarComponents(this.fileLinesContextFactory, null, null,
      null, this.checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    assertThat(logTester.logs()).isEmpty();
  }

  @Test
  void verify_registration_logging() {
    logTester.setLevel(Level.DEBUG);

    JavaCheck expectedCheck = new CustomCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);
    SonarComponents sonarComponents = new SonarComponents(this.fileLinesContextFactory, null, null,
      null, this.checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    List<String> logs = logTester.rawMessages(Level.DEBUG);
    assertThat(logs).hasSize(2);
    assertThat(logs.get(0)).isEqualTo("Registered check: [{}]");
  }

  @Test
  void creation_of_custom_checks() {
    JavaCheck expectedCheck = new CustomCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);

    when(this.checks.all()).thenReturn(Collections.singletonList(expectedCheck)).thenReturn(new ArrayList<>());
    SonarComponents sonarComponents = new SonarComponents(this.fileLinesContextFactory, null, null,
      null, this.checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    List<JavaCheck> visitors = sonarComponents.mainChecks();
    assertThat(visitors).hasSize(1);
    assertThat(visitors.get(0)).isEqualTo(expectedCheck);
    Collection<JavaCheck> testChecks = sonarComponents.testChecks();
    assertThat(testChecks).isEmpty();

    postTestExecutionChecks();
  }

  @Test
  void creation_of_custom_test_checks() {
    JavaCheck expectedCheck = new CustomTestCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);

    when(checks.all()).thenReturn(new ArrayList<>()).thenReturn(Collections.singletonList(expectedCheck));
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    List<JavaCheck> visitors = sonarComponents.mainChecks();
    assertThat(visitors).isEmpty();
    List<JavaCheck> testChecks = sonarComponents.testChecks();
    assertThat(testChecks).hasSize(1);
    assertThat(testChecks.get(0)).isEqualTo(expectedCheck);

    postTestExecutionChecks();
  }

  @Test
  void order_of_checks_is_kept() {
    class CheckA implements JavaCheck {
    }
    class CheckB implements JavaCheck {
    }
    class CheckC implements JavaCheck {
    }
    CheckRegistrar expectedRegistrar = registrarContext -> registrarContext.registerClassesForRepository(
      REPOSITORY_NAME,
      Arrays.asList(CheckB.class, CheckC.class, CheckA.class),
      Arrays.asList(CheckC.class, CheckA.class, CheckB.class));
    when(this.checks.all())
      .thenReturn(Arrays.asList(new CheckA(), new CheckB(), new CheckC()))
      .thenReturn(Arrays.asList(new CheckA(), new CheckB(), new CheckC()));
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    List<JavaCheck> mainChecks = sonarComponents.mainChecks();
    assertThat(mainChecks).extracting(JavaCheck::getClass).extracting(Class::getSimpleName)
      .containsExactly("CheckB", "CheckC", "CheckA");
    List<JavaCheck> testChecks = sonarComponents.testChecks();
    assertThat(testChecks).extracting(JavaCheck::getClass).extracting(Class::getSimpleName)
      .containsExactly("CheckC", "CheckA", "CheckB");
  }

  @Test
  void filter_checks() {
    class CheckA implements JavaCheck {
    }
    class CheckB implements JavaCheck {
    }
    class CheckC implements JavaCheck {
    }
    CheckRegistrar expectedRegistrar = registrarContext -> registrarContext.registerClassesForRepository(
      REPOSITORY_NAME,
      Arrays.asList(CheckA.class, CheckB.class, CheckC.class),
      Arrays.asList(CheckC.class, CheckB.class, CheckA.class));
    when(this.checks.all())
      .thenReturn(Arrays.asList(new CheckA(), new CheckB(), new CheckC()))
      .thenReturn(Arrays.asList(new CheckC(), new CheckB(), new CheckA()));
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);
    sonarComponents.setCheckFilter(checkList -> checkList.stream()
      .filter(c -> !c.getClass().getSimpleName().equals("CheckB")).toList());

    List<JavaCheck> mainChecks = sonarComponents.mainChecks();
    assertThat(mainChecks).extracting(JavaCheck::getClass).extracting(Class::getSimpleName)
      .containsExactly("CheckA", "CheckC");
    List<JavaCheck> testChecks = sonarComponents.testChecks();
    assertThat(testChecks).extracting(JavaCheck::getClass).extracting(Class::getSimpleName)
      .containsExactly("CheckC", "CheckA");
  }

  @Test
  void creation_of_both_types_test_checks() {
    JavaCheck expectedCheck = new CustomCheck();
    JavaCheck expectedTestCheck = new CustomTestCheck();
    CheckRegistrar expectedRegistrar = registrarContext -> registrarContext.registerClassesForRepository(
      REPOSITORY_NAME,
      Collections.singletonList(CustomCheck.class),
      Collections.singletonList(CustomTestCheck.class));

    when(this.checks.all()).thenReturn(Collections.singletonList(expectedCheck)).thenReturn(Collections.singletonList(expectedTestCheck));
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    List<JavaCheck> visitors = sonarComponents.mainChecks();
    assertThat(visitors).hasSize(1);
    assertThat(visitors.get(0)).isEqualTo(expectedCheck);
    List<JavaCheck> testChecks = sonarComponents.testChecks();
    assertThat(testChecks).hasSize(1);
    assertThat(testChecks.get(0)).isEqualTo(expectedTestCheck);

    postTestExecutionChecks();
  }

  @Test
  void register_shared_check_when_rules_are_active_or_not() {
    ActiveRules activeRules = activeRules("java:S101", "java:S102");
    CheckFactory specificCheckFactory = new CheckFactory(activeRules);
    SensorContextTester specificContext = SensorContextTester.create(new File(".")).setActiveRules(activeRules);

    class RuleA implements JavaCheck {
    }
    class RuleB implements JavaCheck {
    }
    class RuleC implements JavaCheck {
    }
    class RuleD implements JavaCheck {
    }
    class RuleE implements JavaCheck {
    }
    class RuleF implements JavaCheck {
    }

    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, specificCheckFactory, activeRules, new CheckRegistrar[]{
      ctx -> ctx.registerMainSharedCheck(new RuleA(), ruleKeys("java:S404", "java:S102")),
      ctx -> ctx.registerMainSharedCheck(new RuleB(), ruleKeys("java:S404", "java:S500")),
      ctx -> ctx.registerMainSharedCheck(new RuleC(), ruleKeys("java:S101", "java:S102")),
      ctx -> ctx.registerTestSharedCheck(new RuleD(), ruleKeys("java:S404", "java:S405", "java:S406")),
      ctx -> ctx.registerTestSharedCheck(new RuleE(), ruleKeys("java:S102")),
      ctx -> ctx.registerTestSharedCheck(new RuleF(), List.of())
    });
    sonarComponents.setSensorContext(specificContext);

    assertThat(sonarComponents.mainChecks())
      .extracting(c -> c.getClass().getSimpleName())
      .containsExactly("RuleA", "RuleC");
    assertThat(sonarComponents.testChecks())
      .extracting(c -> c.getClass().getSimpleName())
      .containsExactly("RuleE");
  }

  @Test
  void register_custom_file_scanners_with_no_active_rules() {
    var noActiveRules = (new ActiveRulesBuilder()).build();
    CheckFactory specificCheckFactory = new CheckFactory(noActiveRules);
    SensorContextTester specificContext = SensorContextTester.create(new File(".")).setActiveRules(noActiveRules);

    class DummyScanner implements JavaFileScanner {
      @Override
      public void scanFile(JavaFileScannerContext context) {
        // Dummy implementation. We just need the class instance
      }
    }

    class MainScanner extends DummyScanner {
    }

    class TestScanner extends DummyScanner {
    }

    class AllScanner extends DummyScanner {
    }

    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, specificCheckFactory, noActiveRules, new CheckRegistrar[]{
      ctx -> ctx.registerCustomFileScanner(RuleScope.MAIN, new MainScanner()),
      ctx -> ctx.registerCustomFileScanner(RuleScope.TEST, new TestScanner()),
      ctx -> ctx.registerCustomFileScanner(RuleScope.ALL, new AllScanner())
    });

    sonarComponents.setSensorContext(specificContext);
    assertThat(sonarComponents.mainChecks())
      .extracting(c -> c.getClass().getSimpleName())
      .containsExactly("MainScanner", "AllScanner");
    assertThat(sonarComponents.testChecks())
      .extracting(c -> c.getClass().getSimpleName())
      .containsExactly("TestScanner", "AllScanner");
  }

  @Test
  void register_custom_rule_by_instances_instead_of_classes() {
    ActiveRules activeRules = activeRules("java:S101", "java:S102");
    CheckFactory specificCheckFactory = new CheckFactory(activeRules);
    SensorContextTester specificContext = SensorContextTester.create(new File(".")).setActiveRules(activeRules);
    @Rule(key = "S101")
    class RuleA implements JavaCheck {
    }
    @Rule(key = "S102")
    class RuleB implements JavaCheck {
    }
    @Rule(key = "S103")
    class RuleC implements JavaCheck {
    }
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, specificCheckFactory, activeRules, new CheckRegistrar[]{
      ctx -> ctx.registerMainChecks("java", List.of(
        new RuleA(),
        new RuleC())),
      ctx -> ctx.registerTestChecks("java", List.of(
        new RuleB()))
    });
    sonarComponents.setSensorContext(specificContext);

    assertThat(sonarComponents.mainChecks())
      .extracting(c -> c.getClass().getSimpleName())
      .containsExactly("RuleA");
    assertThat(sonarComponents.testChecks())
      .extracting(c -> c.getClass().getSimpleName())
      .containsExactly("RuleB");
  }

  @Test
  void auto_scan_compatible_rules() {
    ActiveRules activeRules = activeRules();
    CheckFactory specificCheckFactory = new CheckFactory(activeRules);
    SensorContextTester specificContext = SensorContextTester.create(new File(".")).setActiveRules(activeRules);

    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, specificCheckFactory, activeRules, new CheckRegistrar[]{
      ctx -> ctx.registerAutoScanCompatibleRules(ruleKeys("java:S101", "java:S102")),
      ctx -> ctx.registerAutoScanCompatibleRules(ruleKeys("javabugs:S200"))
    });
    sonarComponents.setSensorContext(specificContext);

    assertThat(sonarComponents.getAdditionalAutoScanCompatibleRuleKeys())
      .extracting(RuleKey::toString)
      .containsExactlyInAnyOrder("java:S101", "java:S102", "javabugs:S200");
  }

  @Test
  void no_issue_when_check_not_found() {
    JavaCheck expectedCheck = new CustomCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);

    when(this.checks.ruleKey(any(JavaCheck.class))).thenReturn(null);
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, checkFactory, context.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(context);

    sonarComponents.addIssue(TestUtils.emptyInputFile("file.java"), expectedCheck, 0, "message", null);
    verify(context, never()).newIssue();
  }

  @Test
  void no_issue_when_reporting_from_custom_file_scanner() {
    JavaFileScanner customScanner = scannerContext -> {
      // empty
    };
    CheckRegistrar registrar = registrarContext ->
      registrarContext.registerCustomFileScanner(RuleScope.ALL, customScanner);
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, null, null,
      null, checkFactory, context.activeRules(), new CheckRegistrar[]{registrar});
    sonarComponents.setSensorContext(context);

    sonarComponents.addIssue(TestUtils.emptyInputFile("file.java"), customScanner, 0, "message", null);
    verify(context, never()).newIssue();
  }

  @Test
  void add_issue_or_parse_error() {
    JavaCheck expectedCheck = new CustomCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);
    SensorContextTester specificContext = SensorContextTester.create(new File("."));

    DefaultFileSystem fileSystem = specificContext.fileSystem();
    TestInputFileBuilder inputFileBuilder = new TestInputFileBuilder("", "file.java");
    inputFileBuilder.setLines(45);
    int[] lineStartOffsets = new int[45];
    lineStartOffsets[35] = 12;
    lineStartOffsets[42] = 1;
    int lastValidOffset = 420;
    inputFileBuilder.setOriginalLineStartOffsets(lineStartOffsets);
    inputFileBuilder.setOriginalLineEndOffsets(computeLineEndOffsets(lineStartOffsets, lastValidOffset));
    inputFileBuilder.setLastValidOffset(lastValidOffset);
    InputFile inputFile = inputFileBuilder.build();
    fileSystem.add(inputFile);

    when(this.checks.ruleKey(any(JavaCheck.class))).thenReturn(mock(RuleKey.class));

    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, fileSystem, null,
      null, checkFactory, specificContext.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(specificContext);

    sonarComponents.addIssue(inputFile, expectedCheck, -5, "message on wrong line", null);
    sonarComponents.addIssue(inputFile, expectedCheck, 42, "message on line 42", 1);
    sonarComponents.reportIssue(new AnalyzerMessage(expectedCheck, inputFile, 35, "other message", 0));

    List<Issue> issues = new ArrayList<>(specificContext.allIssues());
    assertThat(issues).hasSize(3);
    assertThat(issues.get(0).primaryLocation().message()).isEqualTo("message on wrong line");
    assertThat(issues.get(1).primaryLocation().message()).isEqualTo("message on line 42");
    assertThat(issues.get(2).primaryLocation().message()).isEqualTo("other message");

    RecognitionException parseError = new RecognitionException(-1, "invalid code", new Exception("parse error"));

    specificContext.setRuntime(SonarRuntimeImpl.forSonarLint(V8_9));
    assertThat(sonarComponents.reportAnalysisError(parseError, inputFile)).isTrue();

    specificContext.setRuntime(SonarRuntimeImpl.forSonarQube(V8_9, SonarQubeSide.SCANNER, SonarEdition.COMMUNITY));
    assertThat(sonarComponents.reportAnalysisError(parseError, inputFile)).isFalse();

  }

  @Test
  void fail_on_empty_location() {
    JavaCheck expectedCheck = new CustomCheck();
    CheckRegistrar expectedRegistrar = getRegistrar(expectedCheck);
    RuleKey ruleKey = RuleKey.of("MyRepo", "CustomCheck");

    InputFile inputFile = new TestInputFileBuilder("", "file.java")
      .initMetadata("""
        class A {
          void foo() {
            System.out.println();
          }
        }
        """).build();

    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, specificContext.fileSystem(), null, null,
      checkFactory, specificContext.activeRules(), new CheckRegistrar[]{expectedRegistrar});
    sonarComponents.setSensorContext(specificContext);

    AnalyzerMessage.TextSpan emptyTextSpan = new AnalyzerMessage.TextSpan(3, 10, 3, 10);
    AnalyzerMessage analyzerMessageEmptyLocation = new AnalyzerMessage(expectedCheck, inputFile, emptyTextSpan, "message", 0);

    assertThatThrownBy(() -> sonarComponents.reportIssue(analyzerMessageEmptyLocation, ruleKey, inputFile, 0.0))
      .isInstanceOf(IllegalStateException.class).hasMessageContaining("Issue location should not be empty");
    assertThat(specificContext.allIssues()).isEmpty();

    AnalyzerMessage.TextSpan nonEmptyTextSpan = new AnalyzerMessage.TextSpan(3, 10, 3, 15);
    AnalyzerMessage analyzerMessageValidLocation = new AnalyzerMessage(expectedCheck, inputFile, nonEmptyTextSpan, "message", 0);
    sonarComponents.reportIssue(analyzerMessageValidLocation, ruleKey, inputFile, 0.0);
    assertThat(specificContext.allIssues()).isNotEmpty();
  }

  @Test
  void cancellation() {
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    sonarComponents.setSensorContext(specificContext);

    specificContext.setRuntime(SonarRuntimeImpl.forSonarLint(V8_9));
    assertThat(sonarComponents.analysisCancelled()).isFalse();

    // cancellation only handled from SQ 6.0
    specificContext.setCancelled(true);

    assertThat(sonarComponents.analysisCancelled()).isTrue();
  }

  @Test
  void knows_if_quickfixes_are_supported() {
    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(specificContext);

    SonarRuntime sonarQube = SonarRuntimeImpl.forSonarQube(V8_9, SonarQubeSide.SCANNER, SonarEdition.COMMUNITY);
    specificContext.setRuntime(sonarQube);
    assertThat(sonarComponents.isQuickFixCompatible()).isFalse();

    SonarRuntime sonarLintWithoutQuickFix = new SonarLintRuntimeImpl(V8_9, Version.create(5, 3), -1L);
    specificContext.setRuntime(sonarLintWithoutQuickFix);
    assertThat(sonarComponents.isQuickFixCompatible()).isFalse();

    // support of quickfixes introduced in 6.3
    SonarRuntime sonarLintWithQuickFix = new SonarLintRuntimeImpl(V8_9, Version.create(6, 4), -1L);
    specificContext.setRuntime(sonarLintWithQuickFix);
    assertThat(sonarComponents.isQuickFixCompatible()).isTrue();
  }

  @Test
  void knows_if_quickfixes_can_be_advertised() {
    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(specificContext);

    assertTrue(sonarComponents.isSetQuickFixAvailableCompatible());
  }

  @Test
  void knows_if_quickfixes_can_not_be_advertised() {
    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    specificContext.setRuntime(SonarRuntimeImpl.forSonarQube(Version.create(9, 0), SonarQubeSide.SERVER, SonarEdition.COMMUNITY));
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(specificContext);

    assertFalse(sonarComponents.isSetQuickFixAvailableCompatible());
  }

  @Test
  void readFileContentFromInputFile() throws Exception {
    // read a file containing kanji set with correct encoding and expecting proper length of read input.
    InputFile inputFile = spy(TestUtils.inputFile("src/test/files/Kanji.java"));

    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    DefaultFileSystem fileSystem = specificContext.fileSystem();
    fileSystem.add(inputFile);
    fileSystem.setEncoding(StandardCharsets.ISO_8859_1);
    SonarComponents sonarComponents = new SonarComponents(null, fileSystem, null, null, null, null);

    specificContext.setRuntime(SonarRuntimeImpl.forSonarLint(V8_9));
    sonarComponents.setSensorContext(specificContext);

    String fileContent = sonarComponents.inputFileContents(inputFile);
    assertThat(fileContent).hasSize(59);

    List<String> fileLines = sonarComponents.fileLines(inputFile);
    assertThat(fileLines)
      .hasSize(5)
      .noneMatch(line -> line.endsWith("\n"));
    assertThat(fileLines.get(0)).hasSize(11);

    verify(inputFile, times(2)).contents();
    reset(inputFile);
  }

  @Test
  void io_error_when_reading_file_should_fail_analysis() {
    SensorContextTester specificContext = SensorContextTester.create(new File(""));
    DefaultFileSystem fileSystem = specificContext.fileSystem();
    InputFile unknownInputFile = TestUtils.emptyInputFile("unknown_file.java");
    fileSystem.add(unknownInputFile);
    specificContext.setRuntime(SonarRuntimeImpl.forSonarLint(V8_9));
    SonarComponents sonarComponents = new SonarComponents(null, fileSystem, null, null, null, null);
    sonarComponents.setSensorContext(specificContext);

    try {
      sonarComponents.inputFileContents(unknownInputFile);
      fail("reading file content should have failed");
    } catch (AnalysisException e) {
      assertThat(e).hasMessage("Unable to read file 'unknown_file.java'").hasCauseInstanceOf(NoSuchFileException.class);
    } catch (Exception e) {
      fail("reading file content should have failed", e);
    }
    try {
      sonarComponents.fileLines(unknownInputFile);
      fail("reading file lines should have failed");
    } catch (AnalysisException e) {
      assertThat(e).hasMessage("Unable to read file 'unknown_file.java'").hasCauseInstanceOf(NoSuchFileException.class);
    } catch (Exception e) {
      fail("reading file content should have failed");
    }
  }

  @Test
  void jsp_classpath_should_include_plugin() {
    SensorContextTester sensorContextTester = SensorContextTester.create(new File(""));
    DefaultFileSystem fs = sensorContextTester.fileSystem();

    ClasspathForMain javaClasspath = mock(ClasspathForMain.class);
    File someJar = new File("some.jar");
    when(javaClasspath.getElements()).thenReturn(Collections.singletonList(someJar));

    File plugin = new File("target/classes");
    SonarComponents sonarComponents = new SonarComponents(fileLinesContextFactory, fs, javaClasspath, mock(ClasspathForTest.class),
      checkFactory, context.activeRules());
    List<String> jspClassPath = sonarComponents.getJspClasspath().stream().map(File::getAbsolutePath).toList();
    assertThat(jspClassPath).containsExactly(plugin.getAbsolutePath(), someJar.getAbsolutePath());
  }

  @Test
  void autoscan_getters() {
    MapSettings settings = new MapSettings();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));

    // default value
    settings.clear();
    assertThat(sonarComponents.isAutoScan()).isFalse();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.isAutoScanCheckFiltering()).isFalse();

    // autoscan
    settings.clear();
    settings.setProperty("sonar.internal.analysis.autoscan", "true");
    assertThat(sonarComponents.isAutoScan()).isTrue();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.isAutoScanCheckFiltering()).isTrue();

    // autoscan, without check filter
    settings.clear();
    settings.setProperty("sonar.internal.analysis.autoscan", "true");
    settings.setProperty("sonar.internal.analysis.autoscan.filtering", "false");
    assertThat(sonarComponents.isAutoScan()).isTrue();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.isAutoScanCheckFiltering()).isFalse();

    // deprecated autoscan key
    settings.clear();
    settings.setProperty("sonar.java.internal.batchMode", "true");
    assertThat(sonarComponents.isAutoScan()).isTrue();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.isAutoScanCheckFiltering()).isTrue();
  }

  @Test
  void batch_getters() {
    MapSettings settings = new MapSettings();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));

    // default value
    assertThat(sonarComponents.isAutoScan()).isFalse();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.isAutoScanCheckFiltering()).isFalse();
    assertThat(sonarComponents.getBatchModeSizeInKB()).isPositive();

    // batch mode: when a batch mode size is explicitly set, we use this value
    settings.setProperty("sonar.java.experimental.batchModeSizeInKB", "1000");
    assertThat(sonarComponents.isAutoScan()).isFalse();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.getBatchModeSizeInKB()).isEqualTo(1000L);

    // autoscan is not compatible with batch mode size
    settings.setProperty("sonar.internal.analysis.autoscan", "true");
    assertThat(sonarComponents.isAutoScan()).isFalse();
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();
    assertThat(sonarComponents.getBatchModeSizeInKB()).isEqualTo(1000L);

    // batchModeSizeInKB has the priority over deprecated autoscan key
    settings.setProperty("sonar.java.internal.batchMode", "true");
    assertThat(sonarComponents.getBatchModeSizeInKB()).isEqualTo(1000L);

    // Deprecated autoscan key returns default value
    // Note: it means that if someone used this key outside an autoscan context, the project will be analyzed in a single batch
    // (unless batch size is specified)
    settings.clear();
    settings.setProperty("sonar.java.internal.batchMode", "true");
    assertThat(sonarComponents.getBatchModeSizeInKB()).isEqualTo(-1L);
  }

  @ParameterizedTest
  @CsvSource({
    "50, 2",
    "100, 5",
    "200, 10",
    "1000, 50",
    "10000, 500",
    "20000, 500",
  })
  void batch_size_dynamic_computation(long maxMemoryMB, long expectedBatchSizeKB) {
    long maxMemoryBytes = maxMemoryMB * 1_000_000;
    MapSettings settings = new MapSettings();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));

    LongSupplier oldValue = SonarComponents.maxMemoryInBytesProvider;
    SonarComponents.maxMemoryInBytesProvider = () -> maxMemoryBytes;
    long batchModeSizeInKB = sonarComponents.getBatchModeSizeInKB();
    SonarComponents.maxMemoryInBytesProvider = oldValue;
    assertThat(batchModeSizeInKB).isEqualTo(expectedBatchSizeKB);
  }

  @Test
  void file_by_file_getters() {
    MapSettings settings = new MapSettings();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));

    // default value
    assertThat(sonarComponents.isFileByFileEnabled()).isFalse();

    // file by file
    settings.setProperty("sonar.java.fileByFile", "true");
    assertThat(sonarComponents.isFileByFileEnabled()).isTrue();

    // file by file + batch mode can be both set, the priority will be defined when using the values.
    settings.setProperty("sonar.java.experimental.batchModeSizeInKB", "1000");
    assertThat(sonarComponents.isFileByFileEnabled()).isTrue();
    assertThat(sonarComponents.getBatchModeSizeInKB()).isEqualTo(1000);
  }

  @Test
  void skipUnchangedFiles_returns_result_from_context() throws ApiMismatchException {
    SensorContextTester sensorContextTester = SensorContextTester.create(new File(""));

    SonarComponents sonarComponents = new SonarComponents(
      fileLinesContextFactory,
      sensorContextTester.fileSystem(),
      mock(ClasspathForMain.class),
      mock(ClasspathForTest.class),
      checkFactory,
      context.activeRules());

    IncrementalAnalysisSensorContext specificContext = mock(IncrementalAnalysisSensorContext.class);
    when(specificContext.canSkipUnchangedFiles()).thenReturn(true);
    sonarComponents.setSensorContext(specificContext);
    assertThat(sonarComponents.canSkipUnchangedFiles()).isTrue();

    when(specificContext.canSkipUnchangedFiles()).thenReturn(false);
    sonarComponents.setSensorContext(specificContext);
    assertThat(sonarComponents.canSkipUnchangedFiles()).isFalse();
  }

  @Test
  void skipUnchangedFiles_returns_false_by_default() throws ApiMismatchException {
    SensorContextTester sensorContextTester = SensorContextTester.create(new File(""));

    SonarComponents sonarComponents = new SonarComponents(
      fileLinesContextFactory,
      sensorContextTester.fileSystem(),
      mock(ClasspathForMain.class),
      mock(ClasspathForTest.class),
      checkFactory,
      context.activeRules());

    assertThat(sonarComponents.canSkipUnchangedFiles()).isFalse();
  }

  @Test
  void skipUnchangedFiles_throws_a_NoSuchMethodError_when_canSkipUnchangedFiles_not_in_API() {
    SensorContextTester sensorContextTester = SensorContextTester.create(new File(""));
    SonarComponents sonarComponents = new SonarComponents(
      fileLinesContextFactory,
      sensorContextTester.fileSystem(),
      mock(ClasspathForMain.class),
      mock(ClasspathForTest.class),
      checkFactory,
      context.activeRules());

    IncrementalAnalysisSensorContext specificContext = mock(IncrementalAnalysisSensorContext.class);
    when(specificContext.canSkipUnchangedFiles()).thenThrow(new NoSuchMethodError("API version mismatch :-("));
    sonarComponents.setSensorContext(specificContext);

    ApiMismatchException error = assertThrows(
      ApiMismatchException.class,
      sonarComponents::canSkipUnchangedFiles
    );
    assertThat(error).hasCause(new NoSuchMethodError("API version mismatch :-("));
  }

  @Test
  void fileCanBeSkipped_returns_false_when_the_file_is_a_generated_file() {
    SensorContextTester sensorContextTester = SensorContextTester.create(new File(""));
    SonarComponents sonarComponents = spy(
      new SonarComponents(
        fileLinesContextFactory,
        sensorContextTester.fileSystem(),
        mock(ClasspathForMain.class),
        mock(ClasspathForTest.class),
        checkFactory,
        context.activeRules()));
    SensorContext contextMock = mock(SensorContext.class);
    sonarComponents.setSensorContext(contextMock);

    InputFile inputFile = new GeneratedFile(Path.of("non-existing-generated-file.java"));

    assertThat(sonarComponents.fileCanBeSkipped(inputFile)).isFalse();
  }

  @Test
  void fileCanBeSkipped_always_returns_false_when_skipUnchangedFiles_is_false() throws ApiMismatchException {

    SonarComponents sonarComponents = mock(SonarComponents.class, CALLS_REAL_METHODS);
    SensorContext contextMock = mock(SensorContext.class);
    sonarComponents.setSensorContext(contextMock);

    when(sonarComponents.canSkipUnchangedFiles()).thenReturn(false);
    InputFile inputFile = mock(InputFile.class);

    assertThat(sonarComponents.fileCanBeSkipped(inputFile)).isFalse();
  }

  @Test
  void fileCanBeSkipped_returns_false_when_inputFileStatusIsDifferentFromSame() throws ApiMismatchException {
    SonarComponents sonarComponents = mock(SonarComponents.class, CALLS_REAL_METHODS);
    SensorContext contextMock = mock(SensorContext.class);
    sonarComponents.setSensorContext(contextMock);

    when(sonarComponents.canSkipUnchangedFiles()).thenReturn(true);
    InputFile inputFile = mock(InputFile.class);
    when(inputFile.status()).thenReturn(InputFile.Status.CHANGED);
    assertThat(sonarComponents.fileCanBeSkipped(inputFile)).isFalse();
  }

  @Test
  void fileCanBeSkipped_returns_false_when_canSkipUnchangedFile_isFalse() throws ApiMismatchException {
    SonarComponents sonarComponents = mock(SonarComponents.class, CALLS_REAL_METHODS);
    SensorContext contextMock = mock(SensorContext.class);
    sonarComponents.setSensorContext(contextMock);

    ApiMismatchException apiMismatchException = new ApiMismatchException(new NoSuchMethodError("API version mismatch :-("));
    doThrow(apiMismatchException).when(sonarComponents).canSkipUnchangedFiles();

    assertThat(sonarComponents.fileCanBeSkipped(mock(InputFile.class))).isFalse();
  }

  private static Stream<Arguments> fileCanBeSkipped_only_logs_on_first_call_input() throws ApiMismatchException {
    ApiMismatchException apiMismatchException = new ApiMismatchException(new NoSuchMethodError("API version mismatch :-("));

    SonarComponents sonarComponentsThatCanSkipFiles = mock(SonarComponents.class, CALLS_REAL_METHODS);
    doReturn(true).when(sonarComponentsThatCanSkipFiles).canSkipUnchangedFiles();
    SonarComponents sonarComponentsThatCannotSkipFiles = mock(SonarComponents.class, CALLS_REAL_METHODS);
    doReturn(false).when(sonarComponentsThatCannotSkipFiles).canSkipUnchangedFiles();
    SonarComponents sonarComponentsWithApiMismatch = mock(SonarComponents.class, CALLS_REAL_METHODS);
    doThrow(apiMismatchException).when(sonarComponentsWithApiMismatch).canSkipUnchangedFiles();

    InputFile inputFile = mock(InputFile.class);
    doReturn(InputFile.Status.SAME).when(inputFile).status();

    return Stream.of(
      Arguments.of(sonarComponentsThatCanSkipFiles, inputFile, LOG_MESSAGE_FILES_CAN_BE_SKIPPED),
      Arguments.of(sonarComponentsThatCannotSkipFiles, mock(InputFile.class), LOG_MESSAGE_FILES_CANNOT_BE_SKIPPED),
      Arguments.of(sonarComponentsWithApiMismatch, mock(InputFile.class), LOG_MESSAGE_CANNOT_DETERMINE_IF_FILES_CAN_BE_SKIPPED)
    );
  }

  @ParameterizedTest
  @MethodSource("fileCanBeSkipped_only_logs_on_first_call_input")
  void fileCanBeSkipped_only_logs_on_the_first_call(SonarComponents sonarComponents, InputFile inputFile, String logMessage) throws IOException {
    assertThat(logTester.logs(Level.INFO)).isEmpty();

    SensorContext contextMock = mock(SensorContext.class);
    sonarComponents.setSensorContext(contextMock);
    when(inputFile.contents()).thenReturn("");
    sonarComponents.fileCanBeSkipped(inputFile);
    List<String> logs = logTester.rawMessages(Level.INFO);
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0)).isEqualTo(logMessage);

    sonarComponents.fileCanBeSkipped(inputFile);
    logs = logTester.rawMessages(Level.INFO);
    assertThat(logs).hasSize(1);
    assertThat(logs.get(0)).isEqualTo(logMessage);
  }

  private static Stream<Arguments> provideInputsFor_canSkipUnchangedFiles() {
    return Stream.of(
      Arguments.of(null, null, null),
      Arguments.of(null, true, true),
      Arguments.of(true, null, true),
      Arguments.of(null, false, false),
      Arguments.of(false, null, false),
      Arguments.of(false, false, false),
      Arguments.of(false, true, false),
      Arguments.of(true, false, true),
      Arguments.of(true, true, true));
  }

  @ParameterizedTest
  @MethodSource("provideInputsFor_canSkipUnchangedFiles")
  void canSkipUnchangedFiles(@CheckForNull Boolean overrideFlagVal, @CheckForNull Boolean apiResponseVal,
                             @CheckForNull Boolean expectedResult) throws ApiMismatchException {
    SensorContextTester sensorContextTester = SensorContextTester.create(new File(""));
    SonarComponents sonarComponents = new SonarComponents(
      fileLinesContextFactory,
      sensorContextTester.fileSystem(),
      mock(ClasspathForMain.class),
      mock(ClasspathForTest.class),
      checkFactory,
      context.activeRules());

    IncrementalAnalysisSensorContext specificContext = mock(IncrementalAnalysisSensorContext.class);
    Configuration config = mock(Configuration.class);
    when(specificContext.config()).thenReturn(config);

    when(config.getBoolean(any())).thenReturn(Optional.ofNullable(overrideFlagVal));

    if (apiResponseVal == null) {
      lenient().when(specificContext.canSkipUnchangedFiles()).thenThrow(new NoSuchMethodError("API version mismatch :-("));
    } else {
      lenient().when(specificContext.canSkipUnchangedFiles()).thenReturn(apiResponseVal);
    }

    sonarComponents.setSensorContext(specificContext);

    if (expectedResult == null) {
      ApiMismatchException noSuchMethodError = assertThrows(ApiMismatchException.class, sonarComponents::canSkipUnchangedFiles);
      assertThat(noSuchMethodError).hasCause(new NoSuchMethodError("API version mismatch :-("));
    } else {
      assertThat(sonarComponents.canSkipUnchangedFiles()).isEqualTo(expectedResult);
    }
  }

  @Test
  void shouldIgnoreUnnamedModuleForSplitPackage_returns_false_by_default() {
    MapSettings settings = new MapSettings();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));
    assertThat(sonarComponents.shouldIgnoreUnnamedModuleForSplitPackage()).isFalse();
  }

  @Test
  void shouldIgnoreUnnamedModuleForSplitPackage_returns_true_when_analysis_parameter_is_set() {
    MapSettings settings = new MapSettings();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));

    assertThat(sonarComponents.shouldIgnoreUnnamedModuleForSplitPackage()).isFalse();
    settings.setProperty("sonar.java.ignoreUnnamedModuleForSplitPackage", "true");
    assertThat(sonarComponents.shouldIgnoreUnnamedModuleForSplitPackage()).isTrue();
  }

  @Nested
  class Logging {
    private final DecimalFormat formatter = new DecimalFormat("00");

    private final SensorContextTester context = SensorContextTester.create(new File(""));
    private final DefaultFileSystem fs = context.fileSystem();
    private final Configuration settings = context.config();

    private final ClasspathForMain javaClasspath = new ClasspathForMain(settings, fs);
    private final ClasspathForTest javaTestClasspath = new ClasspathForTest(settings, fs);

    private SonarComponents sonarComponents;

    @RegisterExtension
    public LogTesterJUnit5 logTester = new LogTesterJUnit5().setLevel(Level.DEBUG);

    @BeforeEach
    void beforeEach() {
      sonarComponents = new SonarComponents(null, fs, javaClasspath, javaTestClasspath, null, null);
      sonarComponents.setSensorContext(context);
    }

    @Test
    void log_only_50_undefined_types() {
      String source = generateSource(26);

      // artificially populated the semantic errors with 26 unknown types and 52 errors
      sonarComponents.collectUndefinedTypes(DEFAULT_PATH,
        ((JavaTree.CompilationUnitTreeImpl) JParserTestUtils.parse(source)).sema.undefinedTypes());

      // triggers log
      sonarComponents.logUndefinedTypes();

      assertThat(logTester.logs(Level.WARN)).containsExactly(
        "Unresolved imports/types have been detected during analysis. Enable DEBUG mode to see them.");

      List<String> debugLogs = logTester.logs(Level.DEBUG);
      assertThat(debugLogs).hasSize(1);

      String list = debugLogs.get(0);
      assertThat(list)
        .startsWith("Unresolved imports/types: (Limited to 50)")
        .endsWith("- ...")
        .doesNotContain("- Y cannot be resolved to a type")
        .doesNotContain("- Z cannot be resolved to a type");
      for (int i = 0; i < 26; i++) {
        char typeName = (char) ('A' + i);
        assertThat(list).contains(String.format("- The import org.package%s cannot be resolved", formatter.format(i + 1)));
        if (typeName < 'Y') {
          assertThat(list).contains(String.format("- %c cannot be resolved to a type", typeName));
        }
      }
    }

    @Test
    void remove_info_and_warning_from_log_related_to_undefined_types() {
      logTester.setLevel(Level.ERROR);
      String source = generateSource(26);
      sonarComponents.collectUndefinedTypes(DEFAULT_PATH,
        ((JavaTree.CompilationUnitTreeImpl) JParserTestUtils.parse(source)).sema.undefinedTypes());
      sonarComponents.logUndefinedTypes();

      assertThat(logTester.logs(Level.WARN)).isEmpty();
      assertThat(logTester.logs(Level.DEBUG)).isEmpty();
    }

    @Test
    void log_all_undefined_types_if_less_than_threshold() {
      String source = generateSource(1);

      // artificially populated the semantic errors with 1 unknown types and 2 errors
      sonarComponents.collectUndefinedTypes(DEFAULT_PATH,
        ((JavaTree.CompilationUnitTreeImpl) JParserTestUtils.parse(source)).sema.undefinedTypes());

      // triggers log
      sonarComponents.logUndefinedTypes();

      assertThat(logTester.logs(Level.WARN)).containsExactly(
        "Unresolved imports/types have been detected during analysis. Enable DEBUG mode to see them.");

      List<String> debugLogs = logTester.logs(Level.DEBUG);
      assertThat(debugLogs).hasSize(1);

      assertThat(debugLogs.get(0))
        .startsWith("Unresolved imports/types:")
        .doesNotContain("- ...")
        .contains("- A cannot be resolved to a type")
        .contains("- The import org.package01 cannot be resolved");
    }

    @Test
    void suspicious_empty_libraries_should_be_logged() {
      logUndefinedTypesWithOneMainAndOneTest();

      assertThat(logTester.logs(Level.WARN))
        .contains("Dependencies/libraries were not provided for analysis of SOURCE files. The 'sonar.java.libraries' property is empty. " +
          "Verify your configuration, as you might end up with less precise results.")
        .contains("Dependencies/libraries were not provided for analysis of TEST files. " +
          "The 'sonar.java.test.libraries' property is empty. Verify your configuration, as you might end up with less precise results.");
    }

    @Test
    void suspicious_empty_libraries_should_not_be_logged_in_autoscan() {
      // Enable autoscan with a property
      context.setSettings(new MapSettings().setProperty(SonarComponents.SONAR_AUTOSCAN, true));

      logUndefinedTypesWithOneMainAndOneTest();

      assertThat(logTester.logs(Level.WARN))
        .contains("Dependencies/libraries were not provided for analysis of SOURCE files. The 'sonar.java.libraries' property is empty. " +
          "Verify your configuration, as you might end up with less precise results.")
        .doesNotContain("Dependencies/libraries were not provided for analysis of TEST files. " +
          "The 'sonar.java.test.libraries' property is empty. Verify your configuration, as you might end up with less precise results.");
    }

    @Test
    void log_problems_with_list_of_paths_of_files_affected() {
      String source = generateSource(1);

      // Add one test and one main file
      InputFile mainFile = TestUtils.emptyInputFile("fooMain.java", InputFile.Type.MAIN);
      fs.add(mainFile);
      InputFile testFile = TestUtils.emptyInputFile("fooTest.java", InputFile.Type.TEST);
      fs.add(testFile);

      // artificially populated the semantic errors with 1 unknown types and 2 errors
      sonarComponents.collectUndefinedTypes(mainFile.toString(),
        ((JavaTree.CompilationUnitTreeImpl) JParserTestUtils.parse(source)).sema.undefinedTypes());
      sonarComponents.collectUndefinedTypes(testFile.toString(),
        ((JavaTree.CompilationUnitTreeImpl) JParserTestUtils.parse(source)).sema.undefinedTypes());
      sonarComponents.logUndefinedTypes();

      List<String> debugMessage = logTester.logs(Level.DEBUG);
      assertThat(debugMessage).hasSize(1);

      List<String> linesInDebugMessage = debugMessage.stream()
        .map(line -> Arrays.asList(line.split(System.lineSeparator())))
        .flatMap(List::stream)
        .toList();

      assertThat(linesInDebugMessage)
        .containsExactly(
          "Unresolved imports/types:",
          "- A cannot be resolved to a type",
          "  * fooMain.java",
          "  * fooTest.java",
          "",
          "- The import org.package01 cannot be resolved",
          "  * fooMain.java",
          "  * fooTest.java"
        );
    }

    private void logUndefinedTypesWithOneMainAndOneTest() {
      String source = generateSource(1);

      // Add one test and one main file
      fs.add(TestUtils.emptyInputFile("fooMain.java", InputFile.Type.MAIN));
      fs.add(TestUtils.emptyInputFile("fooTest.java", InputFile.Type.TEST));

      // artificially populated the semantic errors with 1 unknown types and 2 errors
      sonarComponents.collectUndefinedTypes(DEFAULT_PATH,
        ((JavaTree.CompilationUnitTreeImpl) JParserTestUtils.parse(source)).sema.undefinedTypes());

      // Call these methods to initiate Main and Test ClassPath
      sonarComponents.getJavaClasspath();
      sonarComponents.getJavaTestClasspath();

      sonarComponents.logUndefinedTypes();
    }

    private String generateSource(int numberUnknownTypes) {
      StringBuilder sourceBuilder = new StringBuilder("package org.foo;\n");
      for (int i = 0; i < numberUnknownTypes; i++) {
        char typeName = (char) ('A' + i);
        sourceBuilder.append(String.format("import org.package%s.%c;\n", formatter.format(i + 1), typeName));
      }
      sourceBuilder.append("class Test {\n");
      for (int i = 0; i < numberUnknownTypes; i++) {
        char typeName = (char) ('A' + i);
        sourceBuilder.append(String.format("  %c variable%d;\n", typeName, i + 1));
      }
      sourceBuilder.append("}");
      return sourceBuilder.toString();
    }
  }

  private static CheckRegistrar getRegistrar(final JavaCheck expectedCheck) {
    return registrarContext -> registrarContext.registerClassesForRepository(REPOSITORY_NAME,
      Collections.singletonList(expectedCheck.getClass()), null);
  }

  private static class CustomCheck implements JavaCheck {
  }

  private static class CustomTestCheck implements JavaCheck {
  }

  @Test
  void should_return_generated_code_visitors() {
    ActiveRules activeRules = new ActiveRulesBuilder()
      .addRule(new NewActiveRule.Builder().setRuleKey(RuleKey.of("custom", "jsp")).build())
      .build();
    CheckFactory specificCheckFactory = new CheckFactory(activeRules);

    JspCodeCheck check = new JspCodeCheck();
    SonarComponents sonarComponents = new SonarComponents(null, null, null, null,
      specificCheckFactory, context.activeRules(), new CheckRegistrar[]{getRegistrar(check)});
    List<JavaCheck> checkList = sonarComponents.jspChecks();
    assertThat(checkList)
      .isNotEmpty()
      .allMatch(JspCodeCheck.class::isInstance);

    sonarComponents = new SonarComponents(null, null, null, null, specificCheckFactory, context.activeRules());
    assertThat(sonarComponents.jspChecks()).isEmpty();
  }

  @Test
  void moduleKey_empty() {
    var sonarComponents = new SonarComponents(null, null, null, null, null, null);
    assertThat(sonarComponents.getModuleKey()).isEmpty();
  }

  @Test
  void moduleKey_non_empty() {
    var rootProj = mock(ProjectDefinition.class);
    doReturn(new File("/foo/bar/proj")).when(rootProj).getBaseDir();
    var parentModule = mock(ProjectDefinition.class);
    doReturn(rootProj).when(parentModule).getParent();
    var childModule = mock(ProjectDefinition.class);
    doReturn(new File("/foo/bar/proj/pmodule/cmodule")).when(childModule).getBaseDir();
    doReturn(parentModule).when(childModule).getParent();

    var sonarComponents = new SonarComponents(null, null, null, null, null, null, childModule);
    assertThat(sonarComponents.getModuleKey()).isEqualTo("pmodule/cmodule");
  }

  @Rule(key = "jsp")
  public static class JspCodeCheck implements JspCodeVisitor {

  }

  //TODO Remove this extended API after the upgrade of sonar-plugin-api to 9.4
  interface IncrementalAnalysisSensorContext extends SensorContext {
    boolean canSkipUnchangedFiles();
  }

  private List<RuleKey> ruleKeys(String... repositoryAndKeys) {
    return Arrays.stream(repositoryAndKeys)
      .map(RuleKey::parse)
      .toList();
  }

  private static ActiveRules activeRules(String... repositoryAndKeys) {
    ActiveRulesBuilder activeRules = new ActiveRulesBuilder();
    for (String repositoryAndKey : repositoryAndKeys) {
      activeRules.addRule(new NewActiveRule.Builder()
        .setRuleKey(RuleKey.parse(repositoryAndKey))
        .setLanguage("java")
        .build());
    }
    return activeRules.build();
  }

  @Test
  void should_return_sonarlint_cache_if_initialized_with_a_sonarlint_cache() {
    var sonarLintCache = mock(SonarLintCache.class);

    var sonarComponentsWithoutSonarLintCache = new SonarComponents(
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null
    );

    assertThat(sonarComponentsWithoutSonarLintCache.sonarLintCache()).isNull();

    var sonarComponentsWithSonarLintCache01 = new SonarComponents(
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      sonarLintCache
    );

    assertThat(sonarComponentsWithSonarLintCache01.sonarLintCache()).isSameAs(sonarLintCache);

    var sonarComponentsWithSonarLintCache02 = new SonarComponents(
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      sonarLintCache
    );

    assertThat(sonarComponentsWithSonarLintCache02.sonarLintCache()).isSameAs(sonarLintCache);
  }

  @Test
  void test_getConfiguration() {
    var settings = new MapSettings();
    var sonarComponents = new SonarComponents(null, null, null, null, null, null);
    sonarComponents.setSensorContext(SensorContextTester.create(new File("")).setSettings(settings));

    var configuration = sonarComponents.getConfiguration();
    assertThat(configuration)
      .isNotNull()
      .isInstanceOf(Configuration.class);
  }

}
