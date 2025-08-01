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

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sonar.java.model.JavaVersionImpl;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.JavaCheck;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.JavaVersion;
import org.sonar.plugins.java.api.JavaVersionAwareVisitor;
import org.sonar.plugins.java.api.tree.Tree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JavaVersionAwareVisitorTest {

  private JavaCheck[] javaChecks;
  private List<String> messages;

  @BeforeEach
  void setUp() {
    messages = new LinkedList<>();
    javaChecks = new JavaCheck[] {
      new JavaVersionCheck(7, messages),
      new JavaVersionCheck(8, messages),
      new SimpleCheck(messages),
      new ContextualCheck(messages)
    };
  }

  @Test
  void all_check_executed_when_no_java_version() {
    checkIssues(new JavaVersionImpl());
    assertThat(messages).containsExactly("JavaVersionCheck_7", "JavaVersionCheck_8", "SimpleCheck", "ContextualCheck");
  }

  @Test
  void all_check_executed_when_invalid_java_version() {
    checkIssues(new JavaVersionImpl());
    assertThat(messages).containsExactly("JavaVersionCheck_7", "JavaVersionCheck_8", "SimpleCheck", "ContextualCheck");
  }

  @Test
  void only_checks_with_adequate_java_version_higher_than_configuration_version_are_executed() {
    checkIssues(new JavaVersionImpl(7));
    assertThat(messages).containsExactly("JavaVersionCheck_7", "SimpleCheck", "ContextualCheck_7");

    checkIssues(new JavaVersionImpl(8));
    assertThat(messages).containsExactly("JavaVersionCheck_7", "JavaVersionCheck_8", "SimpleCheck", "ContextualCheck_8");
  }

  @Test
  void no_java_version_matching() {
    checkIssues(new JavaVersionImpl(6));
    assertThat(messages).containsExactly("SimpleCheck", "ContextualCheck_6");
  }

  private void checkIssues(JavaVersion version) {
    messages.clear();
    JavaFrontend frontend = new JavaFrontend(version, null, mock(Measurer.class), null, null, javaChecks);
    frontend.scan(Collections.singletonList(TestUtils.inputFile("src/test/files/JavaVersionAwareChecks.java")),
      Collections.emptyList(), Collections.emptyList());
  }

  private static class SimpleCheck extends IssuableSubscriptionVisitor {
    private final List<String> messages;

    public SimpleCheck(List<String> messages) {
      this.messages = messages;
    }

    @Override
    public List<Tree.Kind> nodesToVisit() {
      return Collections.singletonList(Tree.Kind.CLASS);
    }

    @Override
    public void visitNode(Tree tree) {
      messages.add(getName());
    }

    public String getName() {
      return this.getClass().getSimpleName().toString();
    }
  }

  private static class ContextualCheck extends SimpleCheck {

    private JavaVersion javaVersion;

    public ContextualCheck(List<String> messages) {
      super(messages);
    }

    @Override
    public void setContext(JavaFileScannerContext context) {
      this.javaVersion = context.getJavaVersion();
      super.setContext(context);
    }

    @Override
    public String getName() {
      return super.getName() + (javaVersion.isNotSet() ? "" : "_" + javaVersion);
    }

  }

  private static class JavaVersionCheck extends SimpleCheck implements JavaVersionAwareVisitor {

    private final Integer target;

    private JavaVersionCheck(Integer target, List<String> messages) {
      super(messages);
      this.target = target;
    }

    @Override
    public boolean isCompatibleWithJavaVersion(JavaVersion version) {
      if (target == 7) {
        return version.isJava7Compatible();
      }
      return version.isJava8Compatible();
    }

    @Override
    public String getName() {
      return super.getName() + "_" + target;
    }
  }
}
