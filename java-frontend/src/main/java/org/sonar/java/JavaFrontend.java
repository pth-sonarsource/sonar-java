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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.java.annotations.VisibleForTesting;
import org.sonar.java.ast.JavaAstScanner;
import org.sonar.java.ast.visitors.FileLinesVisitor;
import org.sonar.java.ast.visitors.SyntaxHighlighterVisitor;
import org.sonar.java.caching.CacheContextImpl;
import org.sonar.java.collections.CollectionUtils;
import org.sonar.java.exceptions.ApiMismatchException;
import org.sonar.java.filters.SonarJavaIssueFilter;
import org.sonar.java.model.JParserConfig;
import org.sonar.java.model.VisitorsBridge;
import org.sonar.plugins.java.api.JavaCheck;
import org.sonar.plugins.java.api.JavaResourceLocator;
import org.sonar.plugins.java.api.JavaVersion;
import org.sonarsource.analyzer.commons.collections.ListUtils;
import org.sonarsource.performance.measure.PerformanceMeasure;
import org.sonarsource.performance.measure.PerformanceMeasure.Duration;

public class JavaFrontend {

  private static final Logger LOG = LoggerFactory.getLogger(JavaFrontend.class);
  private static final String BATCH_ERROR_MESSAGE = "Batch Mode failed, analysis of Java Files stopped.";

  private final JavaVersion javaVersion;
  private final SonarComponents sonarComponents;
  private final List<File> globalClasspath;
  private final JavaAstScanner astScanner;
  private final JavaAstScanner astScannerForTests;
  private final JavaAstScanner astScannerForGeneratedFiles;

  public JavaFrontend(JavaVersion javaVersion, @Nullable SonarComponents sonarComponents, Measurer measurer,
                      JavaResourceLocator javaResourceLocator, @Nullable SonarJavaIssueFilter postAnalysisIssueFilter, JavaCheck... visitors) {
    this.javaVersion = javaVersion;
    this.sonarComponents = sonarComponents;
    List<JavaCheck> commonVisitors = new ArrayList<>();
    commonVisitors.add(javaResourceLocator);
    if (postAnalysisIssueFilter != null) {
      commonVisitors.add(postAnalysisIssueFilter);
    }

    Iterable<JavaCheck> codeVisitors = ListUtils.concat(commonVisitors, Arrays.asList(visitors));
    Collection<JavaCheck> testCodeVisitors = new ArrayList<>(commonVisitors);
    Iterable<JavaCheck> measurers = Collections.singletonList(measurer);
    codeVisitors = ListUtils.concat(measurers, codeVisitors);
    testCodeVisitors.add(measurer.new TestFileMeasurer());
    List<File> classpath = new ArrayList<>();
    List<File> testClasspath = new ArrayList<>();
    List<JavaCheck> jspCodeVisitors = new ArrayList<>();
    List<File> jspClasspath = new ArrayList<>();
    boolean inAndroidContext = false;
    if (sonarComponents != null) {
      if (!sonarComponents.isSonarLintContext()) {
        codeVisitors = ListUtils.concat(codeVisitors, Arrays.asList(new FileLinesVisitor(sonarComponents), new SyntaxHighlighterVisitor(sonarComponents)));
        testCodeVisitors.add(new SyntaxHighlighterVisitor(sonarComponents));
      }
      classpath = sonarComponents.getJavaClasspath();
      testClasspath = sonarComponents.getJavaTestClasspath();
      jspClasspath = sonarComponents.getJspClasspath();
      testCodeVisitors.addAll(sonarComponents.testChecks());
      jspCodeVisitors = sonarComponents.jspChecks();
      inAndroidContext = sonarComponents.inAndroidContext();
    }
    globalClasspath = Stream.of(classpath, testClasspath, jspClasspath)
      .flatMap(Collection::stream).distinct().toList();

    //AstScanner for main files
    astScanner = new JavaAstScanner(sonarComponents);
    astScanner.setVisitorBridge(createVisitorBridge(codeVisitors, classpath, javaVersion, sonarComponents, inAndroidContext));

    //AstScanner for test files
    astScannerForTests = new JavaAstScanner(sonarComponents);
    astScannerForTests.setVisitorBridge(createVisitorBridge(testCodeVisitors, testClasspath, javaVersion, sonarComponents, inAndroidContext));

    //AstScanner for generated files
    astScannerForGeneratedFiles = new JavaAstScanner(sonarComponents);
    astScannerForGeneratedFiles.setVisitorBridge(createVisitorBridge(jspCodeVisitors, jspClasspath, javaVersion, sonarComponents, inAndroidContext));
  }

  private static VisitorsBridge createVisitorBridge(
    Iterable<JavaCheck> codeVisitors, List<File> classpath, JavaVersion javaVersion, @Nullable SonarComponents sonarComponents, boolean inAndroidContext) {
    VisitorsBridge visitorsBridge = new VisitorsBridge(codeVisitors, classpath, sonarComponents, javaVersion);
    visitorsBridge.setInAndroidContext(inAndroidContext);
    return visitorsBridge;
  }

  @VisibleForTesting
  boolean analysisCancelled() {
    return sonarComponents != null && sonarComponents.analysisCancelled();
  }

  public void scan(Iterable<InputFile> sourceFiles, Iterable<InputFile> testFiles, Iterable<? extends InputFile> generatedFiles) {
    if (canOptimizeScanning()) {
      long successfullyScanned = 0L;
      long total = 0L;

      Map<Boolean, List<InputFile>> mainFilesScannedWithoutParsing = astScanner.scanWithoutParsing(sourceFiles);
      sourceFiles = mainFilesScannedWithoutParsing.get(false);
      successfullyScanned += mainFilesScannedWithoutParsing.get(true).size();
      total += mainFilesScannedWithoutParsing.get(true).size() + mainFilesScannedWithoutParsing.get(false).size();

      Map<Boolean, List<InputFile>> testFilesScannedWithoutParsing = astScannerForTests.scanWithoutParsing(testFiles);
      testFiles = testFilesScannedWithoutParsing.get(false);
      successfullyScanned += testFilesScannedWithoutParsing.get(true).size();
      total += testFilesScannedWithoutParsing.get(true).size() + testFilesScannedWithoutParsing.get(false).size();

      total += StreamSupport.stream(generatedFiles.spliterator(), false).count();

      LOG.info(
        "Server-side caching is enabled. The Java analyzer was able to leverage cached data from previous analyses for {} out of {} files. These files will not be parsed.",
        successfullyScanned,
        total
      );
    } else if (isCacheEnabled()) {
      LOG.info("Server-side caching is enabled. The Java analyzer will not try to leverage data from a previous analysis.");
    } else {
      LOG.info("Server-side caching is not enabled. The Java analyzer will not try to leverage data from a previous analysis.");
    }

    // SonarLint is not compatible with batch mode, it needs InputFile#contents() and batch mode use InputFile#absolutePath()
    boolean isSonarLint = sonarComponents != null && sonarComponents.isSonarLintContext();
    boolean fileByFileMode = isSonarLint || isFileByFileEnabled();
    if (fileByFileMode) {
      scanAndMeasureTask(sourceFiles, astScanner::scan, "Main");
      scanAndMeasureTask(testFiles, astScannerForTests::scan, "Test");
      scanAndMeasureTask(generatedFiles, astScannerForGeneratedFiles::scan, "Generated");
    } else if (isAutoScan()) {
      scanAsBatch(new AutoScanBatchContext(), sourceFiles, testFiles);
    } else {
      scanAsBatch(new DefaultBatchModeContext(astScanner, "Main"), sourceFiles);
      scanAsBatch(new DefaultBatchModeContext(astScannerForTests, "Test"), testFiles);
      scanAsBatch(new DefaultBatchModeContext(astScannerForGeneratedFiles, "Generated"), generatedFiles);
    }
  }

  /**
   * Scans the files given as input in batch mode.
   *
   * The batch size used is determined by configuration.
   * This batch size is then used as a threshold: files are added to a batch until the threshold is passed.
   * Once the threshold is passed, the batch is processed for analysis.
   *
   * If no batch size is configured, the input files are scanned as a single batch.
   *
   * @param inputFiles The collections of files to scan
   */
  private void scanAsBatch(BatchModeContext context, Iterable<? extends InputFile>... inputFiles) {
    List<InputFile> files = new ArrayList<>();
    for (Iterable<? extends InputFile> group : inputFiles) {
      files.addAll(astScanner.filterModuleInfo(group).toList());
    }
    try {
      try {
        if (!files.isEmpty()) {
          scanInBatches(context, files);
        } else if (LOG.isInfoEnabled()) {
          LOG.info("No \"{}\" source files to scan.", context.descriptor());
        }
      } finally {
        context.endOfAnalysis();
      }
    } catch (AnalysisException e) {
      throw e;
    } catch (Exception e) {
      astScanner.checkInterrupted(e);
      astScannerForTests.checkInterrupted(e);
      astScannerForGeneratedFiles.checkInterrupted(e);
      LOG.error(BATCH_ERROR_MESSAGE, e);
      if (astScanner.shouldFailAnalysis()) {
        throw new AnalysisException(BATCH_ERROR_MESSAGE, e);
      }
    }
  }

  private void scanInBatches(BatchModeContext context, List<InputFile> allInputFiles) {
    String logUsingBatch = String.format("Using ECJ batch to parse %d %s java source files", allInputFiles.size(), context.descriptor());
    AnalysisProgress analysisProgress = new AnalysisProgress(allInputFiles.size());
    long batchModeSizeInKB = getBatchModeSizeInKB();
    if (batchModeSizeInKB < 0L || batchModeSizeInKB >= Long.MAX_VALUE / 1_000L) {
      LOG.info("{} in a single batch.", logUsingBatch);
      scanBatch(context, allInputFiles, analysisProgress);
    } else {
      long batchSize = batchModeSizeInKB * 1_000L;
      LOG.info("{} with batch size {} KB.", logUsingBatch, batchModeSizeInKB);
      BatchGenerator generator = new BatchGenerator(allInputFiles.iterator(), batchSize);
      while (generator.hasNext()) {
        List<InputFile> batch = generator.next();
        scanBatch(context, batch, analysisProgress);
      }
    }
  }

  private <T extends InputFile> void scanBatch(BatchModeContext context, List<T> batchFiles, AnalysisProgress analysisProgress) {
    analysisProgress.startBatch(batchFiles.size());
    Set<Runnable> environmentsCleaners = new HashSet<>();
    boolean shouldIgnoreUnnamedModuleForSplitPackage = sonarComponents!= null && sonarComponents.shouldIgnoreUnnamedModuleForSplitPackage();
    JParserConfig.Mode.BATCH
      .create(javaVersion, context.getClasspath(), shouldIgnoreUnnamedModuleForSplitPackage)
      .parse(batchFiles, this::analysisCancelled, analysisProgress, (input, result) -> scanAsBatchCallback(input, result, context, environmentsCleaners));
    // Due to a bug in ECJ, JAR files remain locked after the analysis on Windows, we unlock them manually, at the end of each batches. See SONARJAVA-3609.
    environmentsCleaners.forEach(Runnable::run);
    analysisProgress.endBatch();
  }

  private static void scanAsBatchCallback(InputFile inputFile, JParserConfig.Result result, BatchModeContext context, Set<Runnable> environmentsCleaners) {
    JavaAstScanner scanner = context.selectScanner(inputFile);
    Duration duration = PerformanceMeasure.start(context.descriptor(inputFile));
    scanner.simpleScan(inputFile, result, ast ->
      // In batch mode, we delay the cleaning of the environment as it will be used in later processing.
      environmentsCleaners.add(ast.sema.getEnvironmentCleaner())
    );
    duration.stop();
  }

  interface BatchModeContext {
    String descriptor();

    String descriptor(InputFile input);

    List<File> getClasspath();

    JavaAstScanner selectScanner(InputFile input);

    void endOfAnalysis();
  }

  class AutoScanBatchContext implements BatchModeContext {

    @Override
    public String descriptor() {
      return "Main and Test";
    }

    @Override
    public String descriptor(InputFile input) {
      return input.type() == InputFile.Type.TEST ? "Test" : "Main";
    }

    @Override
    public List<File> getClasspath() {
      return globalClasspath;
    }

    @Override
    public JavaAstScanner selectScanner(InputFile input) {
      return input.type() == InputFile.Type.TEST ? astScannerForTests : astScanner;
    }

    @Override
    public void endOfAnalysis() {
      astScanner.endOfAnalysis();
      astScannerForTests.endOfAnalysis();
      astScannerForGeneratedFiles.endOfAnalysis();
    }

  }

  static class DefaultBatchModeContext implements BatchModeContext {
    private final JavaAstScanner scanner;
    private final String descriptor;

    public DefaultBatchModeContext(JavaAstScanner scanner, String descriptor) {
      this.scanner = scanner;
      this.descriptor = descriptor;
    }

    @Override
    public String descriptor() {
      return descriptor;
    }

    @Override
    public String descriptor(InputFile input) {
      return descriptor;
    }

    @Override
    public List<File> getClasspath() {
      return scanner.getClasspath();
    }

    @Override
    public JavaAstScanner selectScanner(InputFile input) {
      return scanner;
    }

    @Override
    public void endOfAnalysis() {
      scanner.endOfAnalysis();
    }

  }

  @VisibleForTesting
  boolean isFileByFileEnabled() {
    return sonarComponents != null && sonarComponents.isFileByFileEnabled();
  }

  @VisibleForTesting
  boolean isAutoScan() {
    return sonarComponents != null && sonarComponents.isAutoScan();
  }

  @VisibleForTesting
  long getBatchModeSizeInKB() {
    return sonarComponents == null ? -1L : sonarComponents.getBatchModeSizeInKB();
  }

  private boolean isCacheEnabled() {
    return sonarComponents != null && CacheContextImpl.of(sonarComponents).isCacheEnabled();
  }

  private boolean canOptimizeScanning() {
    try {
      return sonarComponents != null && sonarComponents.canSkipUnchangedFiles() && isCacheEnabled();
    } catch (ApiMismatchException e) {
      return false;
    }
  }

  private static <T> void scanAndMeasureTask(Iterable<T> files, Consumer<Iterable<T>> action, String descriptor) {
    if (CollectionUtils.size(files) > 0) {
      Duration mainDuration = PerformanceMeasure.start(descriptor);

      action.accept(files);

      mainDuration.stop();
    } else {
      LOG.info("No \"{}\" source files to scan.", descriptor);
    }
  }
}
