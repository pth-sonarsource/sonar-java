/*
 * SonarQube Java
 * Copyright (C) 2013-2025 SonarSource SA
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
package com.sonar.it.java.suite;

import com.sonar.orchestrator.build.BuildResult;
import com.sonar.orchestrator.build.MavenBuild;
import com.sonar.orchestrator.build.SonarScanner;
import com.sonar.orchestrator.junit4.OrchestratorRule;
import com.sonar.orchestrator.locator.MavenLocation;
import java.io.File;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonarqube.ws.Components;
import org.sonarqube.ws.Issues.Issue;

import static com.sonar.it.java.suite.JavaTestSuite.getComponent;
import static com.sonar.it.java.suite.JavaTestSuite.getMeasureAsInteger;
import static org.assertj.core.api.Assertions.assertThat;

public class JavaTest {

  @ClassRule
  public static OrchestratorRule orchestrator = JavaTestSuite.ORCHESTRATOR;

  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();

  /**
   * See SONAR-1865
   */
  @Test
  public void shouldAcceptFilenamesWithDollar() {
    MavenBuild build = TestUtils.createMavenBuild().setPom(TestUtils.projectPom("dollar-in-names"))
      .setCleanPackageSonarGoals()
      .setProperty("sonar.dynamicAnalysis", "false");
    orchestrator.executeBuild(build);

    Components.Component file = getComponent(JavaTestSuite.keyFor("org.sonarsource.it.projects:dollar-in-names", "dollars/", "FilenameWith$Dollar.java"));
    assertThat(file).isNotNull();
    assertThat(file.getName()).contains("FilenameWith$Dollar");
  }

  /**
   * SONARJAVA-444
   */
  @Test
  public void shouldFailIfInvalidJavaPackage() {
    MavenBuild build = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("invalid-java-package"))
      .setCleanSonarGoals();

    BuildResult buildResult = orchestrator.executeBuildQuietly(build);
    assertThat(buildResult.getLastStatus()).isZero();
  }

  @Test
  public void measures_on_directory() {
    MavenBuild build = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("measures-on-directory"))
      .setCleanPackageSonarGoals();
    BuildResult result = orchestrator.executeBuildQuietly(build);
    // since sonar-java 2.1 does not fail if multiple package in same directory.
    assertThat(result.getLastStatus()).isZero();
  }

  @Test
  public void multiple_package_in_directory_should_not_fail() {
    MavenBuild inspection = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("multiple-packages-in-directory"))
      .setCleanPackageSonarGoals();
    BuildResult result = orchestrator.executeBuildQuietly(inspection);
    assertThat(result.getLastStatus()).isZero();
    inspection = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("multiple-packages-in-directory"))
      .setProperty("sonar.skipPackageDesign", "true")
      .setGoals("sonar:sonar");
    result = orchestrator.executeBuildQuietly(inspection);
    assertThat(result.getLastStatus()).isZero();
  }

  /**
   * SONARJAVA-1615
   */
  @Test
  public void filtered_issues() {
    MavenBuild build = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("filtered-issues"))
      .setCleanPackageSonarGoals();

    TestUtils.provisionProject(orchestrator, "org.example:example", "filtered-issues", "java", "filtered-issues");
    orchestrator.executeBuild(build);

    assertThat(getMeasureAsInteger("org.example:example", "violations")).isEqualTo(2);

    List<Issue> issues = TestUtils.issuesForComponent(orchestrator, "org.example:example:src/main/java/EclispeI18NFiltered.java");

    assertThat(issues).hasSize(2);
    for (Issue issue : issues) {
      assertThat(issue.getRule()).matches(value -> "java:S1444".equals(value) || "java:S1104".equals(value));
      assertThat(issue.getLine()).isEqualTo(17);
    }
  }

  /**
   * SONAR-4768
   */
  @Test
  public void support_jav_file_extension() {
    SonarScanner scan = TestUtils.createSonarScanner()
      .setProjectDir(TestUtils.projectDir("jav-file-extension"))
      .setProperty("sonar.projectKey", "jav-file-extension")
      .setProperty("sonar.projectName", "jav-file-extension")
      .setProperty("sonar.projectVersion", "1.0-SNAPSHOT")
      .setProperty("sonar.sources", "src");
    orchestrator.executeBuild(scan);

    assertThat(getMeasureAsInteger("jav-file-extension", "files")).isEqualTo(1);
    assertThat(getMeasureAsInteger("jav-file-extension", "ncloc")).isPositive();
  }

  @Test
  public void support_change_of_extension_property() {
    SonarScanner scan = TestUtils.createSonarScanner()
      .setProjectDir(TestUtils.projectDir("jav-file-extension"))
      .setProperty("sonar.projectKey", "jav-file-extension")
      .setProperty("sonar.projectName", "jav-file-extension")
      .setProperty("sonar.projectVersion", "1.0-SNAPSHOT")
      .setProperty("sonar.java.file.suffixes", ".txt,.foo")
      .setProperty("sonar.sources", "src")
      .setProperty("sonar.java.binaries", "src");
    orchestrator.executeBuild(scan);

    assertThat(getMeasureAsInteger("jav-file-extension", "files")).isEqualTo(2);
    assertThat(getMeasureAsInteger("jav-file-extension", "ncloc")).isPositive();
  }

  @Test
  public void should_execute_rule_on_test() {
    MavenLocation junit411 = MavenLocation.of("junit", "junit", "4.11");
    orchestrator.getConfiguration().locators().copyToDirectory(junit411, tmp.getRoot());
    MavenBuild build = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("java-inner-classes"))
      .setProperty("sonar.java.test.binaries", "target/test-classes")
      .setProperty("sonar.java.test.libraries", new File(tmp.getRoot(), junit411.getFilename()).getAbsolutePath())
      .setCleanPackageSonarGoals();
    TestUtils.provisionProject(orchestrator, "org.sonarsource.it.projects:java-inner-classes", "java-inner-classes", "java", "ignored-test-check");
    orchestrator.executeBuild(build);
    assertThat(getMeasureAsInteger("org.sonarsource.it.projects:java-inner-classes", "violations")).isEqualTo(1);
  }

  @Test
  public void java_aware_visitor_rely_on_java_version() {
    String sonarJavaSource = "sonar.java.source";

    MavenBuild build = TestUtils.createMavenBuild()
      .setPom(TestUtils.projectPom("java-version-aware-visitor"))
      .setCleanSonarGoals();
    String projectKey = "java-version-aware-visitor";
    build.setProperties("sonar.projectKey", projectKey);

    TestUtils.provisionProject(orchestrator, projectKey, "java-version-aware-visitor", "java", "java-version-aware-visitor");

    orchestrator.executeBuild(build);
    // if not specifying java version, it gets the maven default compiling version - JDK may vary
    // for mvn 3.8.x: java 5 -> 0 issue
    // for mvn 3.9.y: java 8 -> 1 issue
    int numberIssuesWithoutJavaVersionSet = getMeasureAsInteger(projectKey, "violations");
    assertThat(numberIssuesWithoutJavaVersionSet).isIn(0, 1);

    // invalid java version. got issue on java 6 code
    build.setProperty(sonarJavaSource, "jdk_1.6");
    BuildResult buildResult = orchestrator.executeBuild(build);
    // build should not fail
    assertThat(buildResult.getLastStatus()).isZero();
    // build logs should contains warning related to sources
    assertThat(buildResult.getLogs()).contains("Invalid java version");
    int numberIssuesWithInvalidJDKVersion = getMeasureAsInteger(projectKey, "violations");
    assertThat(numberIssuesWithInvalidJDKVersion).isEqualTo(1);

    // upper version. got issue on java 8 code
    build.setProperty(sonarJavaSource, "1.8");
    orchestrator.executeBuild(build);
    int numberIssuesWithJava8 = getMeasureAsInteger(projectKey, "violations");
    assertThat(numberIssuesWithJava8).isEqualTo(1);

    // lower version. no issue on java 6 code
    build.setProperty(sonarJavaSource, "1.6");
    orchestrator.executeBuild(build);
    int numberIssuesWithJava6 = getMeasureAsInteger(projectKey, "violations");
    assertThat(numberIssuesWithJava6).isZero();

    SonarScanner scan = TestUtils.createSonarScanner()
      .setProjectDir(TestUtils.projectDir("java-version-aware-visitor"))
      .setProperty("sonar.projectKey", "org.example:example-scanner")
      .setProperty("sonar.projectName", "example")
      .setProperty("sonar.projectVersion", "1.0-SNAPSHOT")
      .setProperty("sonar.sources", "src/main/java");
    TestUtils.provisionProject(orchestrator, "org.example:example-scanner", "java-version-aware-visitor", "java", "java-version-aware-visitor");
    orchestrator.executeBuild(scan);
    // no java version specified, got issue on java 7 code
    assertThat(getMeasureAsInteger("org.example:example-scanner", "violations")).isEqualTo(1);
  }
}
