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
package org.sonar.java.checks.verifier.internal;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.cache.ReadCache;
import org.sonar.api.batch.sensor.cache.WriteCache;
import org.sonar.java.SonarComponents;
import org.sonar.java.annotations.VisibleForTesting;
import org.sonar.java.ast.JavaAstScanner;
import org.sonar.java.ast.visitors.CommentLinesVisitor;
import org.sonar.java.caching.DummyCache;
import org.sonar.java.caching.JavaReadCacheImpl;
import org.sonar.java.caching.JavaWriteCacheImpl;
import org.sonar.java.checks.verifier.CheckVerifier;
import org.sonar.java.model.JavaVersionImpl;
import org.sonar.java.reporting.AnalyzerMessage;
import org.sonar.java.reporting.JavaQuickFix;
import org.sonar.java.test.classpath.TestClasspathUtils;
import org.sonar.java.testing.JavaFileScannerContextForTests;
import org.sonar.java.testing.VisitorsBridgeForTests;
import org.sonar.plugins.java.api.JavaFileScanner;
import org.sonar.plugins.java.api.JavaVersion;
import org.sonar.plugins.java.api.caching.CacheContext;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonarsource.analyzer.commons.checks.verifier.MultiFileVerifier;
import org.sonarsource.analyzer.commons.checks.verifier.quickfix.QuickFix;
import org.sonarsource.analyzer.commons.checks.verifier.quickfix.TextEdit;
import org.sonarsource.analyzer.commons.checks.verifier.quickfix.TextSpan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.sonar.java.checks.verifier.internal.CheckVerifierUtils.CHECK_OR_CHECKS;
import static org.sonar.java.checks.verifier.internal.CheckVerifierUtils.FILE_OR_FILES;
import static org.sonar.java.checks.verifier.internal.CheckVerifierUtils.requiresNonEmpty;
import static org.sonar.java.checks.verifier.internal.CheckVerifierUtils.requiresNonNull;
import static org.sonar.java.checks.verifier.internal.CheckVerifierUtils.requiresNull;

public class JavaCheckVerifier implements CheckVerifier {

  private static final JavaVersion DEFAULT_JAVA_VERSION = new JavaVersionImpl();
  private static final int COMMENT_PREFIX_LENGTH = 2;
  private static final int COMMENT_SUFFIX_LENGTH = 0;

  private JavaCheckVerifier() {
  }

  public static JavaCheckVerifier newInstance() {
    return new JavaCheckVerifier();
  }

  private List<JavaFileScanner> checks = null;
  private List<File> classpath = null;
  private JavaVersion javaVersion = null;
  private boolean inAndroidContext = false;
  private List<InputFile> files = null;
  private boolean withoutSemantic = false;
  private boolean isCacheEnabled = false;
  private Consumer<CompilationUnitTree> compilationUnitModifier = unused -> {};

  @VisibleForTesting
  CacheContext cacheContext = null;
  private ReadCache readCache;
  private WriteCache writeCache;
  private File rootDirectory;

  private MultiFileVerifier createVerifier() {
    MultiFileVerifier verifier = MultiFileVerifier.create(Paths.get(files.get(0).uri()), UTF_8);

    JavaVersion actualVersion = javaVersion == null ? DEFAULT_JAVA_VERSION : javaVersion;
    List<File> actualClasspath = classpath == null ? TestClasspathUtils.DEFAULT_MODULE.getClassPath() : classpath;

    List<JavaFileScanner> visitors = new ArrayList<>(checks);
    CommentLinesVisitor commentLinesVisitor = new CommentLinesVisitor();
    visitors.add(commentLinesVisitor);
    SonarComponents sonarComponents = CheckVerifierUtils.sonarComponents(isCacheEnabled, readCache, writeCache, rootDirectory);
    VisitorsBridgeForTests visitorsBridge;
    if (withoutSemantic) {
      visitorsBridge = new VisitorsBridgeForTests(visitors, sonarComponents, actualVersion);
    } else {
      visitorsBridge = new VisitorsBridgeForTests(visitors, actualClasspath, sonarComponents, actualVersion);
    }

    JavaAstScanner astScanner = new JavaAstScanner(sonarComponents);
    visitorsBridge.setInAndroidContext(inAndroidContext);

    astScanner.setVisitorBridge(visitorsBridge);

    List<InputFile> filesToParse = files;
    if (isCacheEnabled) {
      visitorsBridge.setCacheContext(cacheContext);
      filesToParse = astScanner.scanWithoutParsing(files).get(false);
    }
    astScanner.scanForTesting(filesToParse, compilationUnitModifier);

    addComments(verifier, commentLinesVisitor);

    JavaFileScannerContextForTests testJavaFileScannerContext = visitorsBridge.lastCreatedTestContext();
    JavaFileScannerContextForTests testModuleScannerContext = visitorsBridge.lastCreatedModuleContext();
    if (testJavaFileScannerContext != null) {
      addIssues(testJavaFileScannerContext, verifier);
      addIssues(testModuleScannerContext, verifier);
    }

    return verifier;
  }

  private static void addIssues(JavaFileScannerContextForTests scannerContext, MultiFileVerifier verifier) {
    scannerContext.getIssues().forEach(issue -> {
      if (!issue.getInputComponent().isFile()) {
        return;
      }
      Path path = ((InternalInputFile) issue.getInputComponent()).path();
      String issueMessage = issue.getMessage();
      AnalyzerMessage.TextSpan textSpan = issue.primaryLocation();
      MultiFileVerifier.Issue verifierIssue;
      if (textSpan != null) {
        verifierIssue = getIssueForTextSpan(verifier, textSpan, path, issueMessage);
      } else if (issue.getLine() != null) {
        verifierIssue = verifier.reportIssue(path, issueMessage).onLine(issue.getLine());
      } else {
        verifierIssue = verifier.reportIssue(path, issueMessage).onFile();
      }

      var quickfixes = scannerContext.getQuickFixes().get(textSpan);
      if (quickfixes != null) {
        for (var qf : quickfixes) {
          verifierIssue = verifierIssue.addQuickFix(convertQuickFix(qf));
        }
      }

      List<AnalyzerMessage> secondaries = issue.flows.stream().map(l -> l.isEmpty() ? null : l.get(0)).filter(Objects::nonNull).toList();
      MultiFileVerifier.Issue finalVerifierIssue = verifierIssue;
      secondaries.forEach(secondary -> addSecondary(path, finalVerifierIssue, secondary));
    });
  }

  private static MultiFileVerifier.Issue getIssueForTextSpan(MultiFileVerifier verifier, AnalyzerMessage.TextSpan textSpan, Path path, String issueMessage) {
    MultiFileVerifier.Issue verifierIssue;
    if (textSpan.endCharacter < 0) {
      // Sometimes we create a textspan with endCharacter < 0 to raise an issue on the line.
      verifierIssue = verifier.reportIssue(path, issueMessage).onLine(textSpan.startLine);
    } else {
      verifierIssue = verifier.reportIssue(path, issueMessage)
        .onRange(textSpan.startLine, textSpan.startCharacter + 1, textSpan.endLine, textSpan.endCharacter);
    }
    return verifierIssue;
  }

  private static QuickFix convertQuickFix(JavaQuickFix javaQuickFix) {
    return QuickFix.newQuickFix(javaQuickFix.getDescription())
      .addTextEdits(javaQuickFix.getTextEdits().stream()
        .map(te -> TextEdit.replaceTextSpan(convertTextSpan(te.getTextSpan()), convertReplacement(te.getReplacement()))).toList())
      .build();
  }

  private static TextSpan convertTextSpan(AnalyzerMessage.TextSpan textSpan) {
    return new TextSpan(textSpan.startLine, textSpan.startCharacter + 1, textSpan.endLine, textSpan.endCharacter + 1);
  }

  private static String convertReplacement(String replacement) {
    return replacement.replace("\n", "\\n");
  }

  private static void addSecondary(Path path, MultiFileVerifier.Issue issue, AnalyzerMessage secondary) {
    AnalyzerMessage.TextSpan textSpan = secondary.primaryLocation();
    issue.addSecondary(path, textSpan.startLine, textSpan.startCharacter + 1, textSpan.endLine, textSpan.endCharacter, secondary.getMessage());
  }

  private static void addComments(MultiFileVerifier singleFileVerifier, CommentLinesVisitor commentLinesVisitor) {
    commentLinesVisitor.getSyntaxTrivia().keySet()
      .forEach(path -> commentLinesVisitor.getSyntaxTrivia().get(path).stream()
        .sorted(Comparator.comparingInt(t -> t.range().start().line()))
        .forEach(trivia -> singleFileVerifier.addComment(
          path,
          trivia.range().start().line(),
          trivia.range().start().column(),
          trivia.comment(),
          COMMENT_PREFIX_LENGTH,
          COMMENT_SUFFIX_LENGTH)));
  }

  @Override
  public CheckVerifier withCheck(JavaFileScanner check) {
    requiresNull(checks, CHECK_OR_CHECKS);
    this.checks = Collections.singletonList(check);
    return this;
  }

  @Override
  public CheckVerifier withChecks(JavaFileScanner... checks) {
    requiresNull(this.checks, CHECK_OR_CHECKS);
    requiresNonEmpty(Arrays.asList(checks), "check");
    this.checks = Arrays.asList(checks);
    return this;
  }

  @Override
  public CheckVerifier withClassPath(Collection<File> classpath) {
    requiresNull(this.classpath, "classpath");
    this.classpath = new ArrayList<>(classpath);
    return this;
  }

  @Override
  public CheckVerifier withJavaVersion(int javaVersionAsInt) {
    requiresNull(javaVersion, "java version");
    return withJavaVersion(javaVersionAsInt, false);
  }

  @Override
  public CheckVerifier withJavaVersion(int javaVersionAsInt, boolean enablePreviewFeatures) {
    requiresNull(javaVersion, "java version");
    if (enablePreviewFeatures && javaVersionAsInt != JavaVersionImpl.MAX_SUPPORTED) {
      var message = String.format(
        "Preview features can only be enabled when the version == latest supported Java version (%d != %d)",
        javaVersionAsInt,
        JavaVersionImpl.MAX_SUPPORTED);
      throw new IllegalArgumentException(message);
    }
    this.javaVersion = new JavaVersionImpl(javaVersionAsInt, enablePreviewFeatures);
    return this;
  }

  @Override
  public CheckVerifier withinAndroidContext(boolean inAndroidContext) {
    this.inAndroidContext = inAndroidContext;
    return this;
  }

  @Override
  public CheckVerifier onFile(String filename) {
    requiresNull(files, FILE_OR_FILES);
    return onFiles(Collections.singletonList(filename));
  }

  @Override
  public CheckVerifier onFiles(String... filenames) {
    List<String> asList = Arrays.asList(filenames);
    requiresNonEmpty(asList, "file");
    return onFiles(asList);
  }

  @Override
  public CheckVerifier onFiles(Collection<String> filenames) {
    requiresNull(files, FILE_OR_FILES);
    requiresNonEmpty(filenames, "file");
    this.files = new ArrayList<>();
    return addFiles(InputFile.Status.SAME, filenames);
  }

  @Override
  public CheckVerifier addFiles(InputFile.Status status, String... filenames) {
    return addFiles(status, Arrays.asList(filenames));
  }

  @Override
  public CheckVerifier addFiles(InputFile.Status status, Collection<String> filenames) {
    requiresNonEmpty(filenames, "file");
    if (this.files == null) {
      this.files = new ArrayList<>(filenames.size());
    }

    var filesToAdd = filenames.stream()
      .map(name -> InternalInputFile.inputFile("", new File(name), status))
      .toList();

    var filesToAddStrings = filesToAdd.stream().map(Object::toString).toList();

    this.files.forEach(inputFile -> {
      if (filesToAddStrings.contains(inputFile.toString())) {
        throw new IllegalArgumentException(String.format("File %s was already added.", inputFile));
      }
    });

    this.files.addAll(filesToAdd);

    return this;
  }

  @Override
  public CheckVerifier withoutSemantic() {
    this.withoutSemantic = true;
    return this;
  }

  @Override
  public CheckVerifier withCache(@Nullable ReadCache readCache, @Nullable WriteCache writeCache) {
    this.isCacheEnabled = true;
    this.readCache = readCache;
    this.writeCache = writeCache;
    this.cacheContext = new InternalCacheContext(
      true,
      readCache == null ? new DummyCache() : new JavaReadCacheImpl(readCache),
      writeCache == null ? new DummyCache() : new JavaWriteCacheImpl(writeCache));
    return this;
  }

  @Override
  public CheckVerifier withProjectLevelWorkDir(String rootDirectory) {
    this.rootDirectory = new File(rootDirectory);
    return this;
  }

  @Override
  public CheckVerifier withCompilationUnitModifier(Consumer<CompilationUnitTree> compilationUnitModifier) {
    this.compilationUnitModifier = compilationUnitModifier;
    return this;
  }

  @Override
  public void verifyIssues() {
    requiresNonNull(checks, CHECK_OR_CHECKS);
    requiresNonNull(files, FILE_OR_FILES);
    createVerifier().assertOneOrMoreIssues();
  }

  @Override
  public void verifyIssueOnFile(String expectedIssueMessage) {
    requiresNonNull(checks, CHECK_OR_CHECKS);
    requiresNonNull(files, FILE_OR_FILES);
    createVerifier().assertOneOrMoreIssues();
  }

  @Override
  public void verifyIssueOnProject(String expectedIssueMessage) {
    throw new UnsupportedOperationException("Not implemented!");
  }

  @Override
  public void verifyNoIssues() {
    requiresNonNull(checks, CHECK_OR_CHECKS);
    requiresNonNull(files, FILE_OR_FILES);
    createVerifier().assertNoIssuesRaised();
  }

}
