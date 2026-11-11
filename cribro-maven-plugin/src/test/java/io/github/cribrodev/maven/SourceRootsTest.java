/*
 * Copyright (C) 2026 cribro contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.cribrodev.maven;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Kotlin module's sources lie where {@code kotlin-maven-plugin}'s {@code sourceDirs} say, which
 * the POM may write at the plugin or at an execution bound to a goal.
 */
class SourceRootsTest {

  @TempDir Path repository;

  /** A module at {@code repository/m} with Maven's default source and test roots. */
  private MavenProject module() {
    MavenProject project = new MavenProject();
    project.setArtifactId("m");
    project.setFile(repository.resolve("m/pom.xml").toFile());
    project.getModel().setBuild(new Build());
    return project;
  }

  /** Returns the module with {@code kotlin-maven-plugin} applied, configured as given. */
  private static MavenProject withKotlinPlugin(
      MavenProject project, String configuration, PluginExecution... executions)
      throws IOException, XmlPullParserException {
    Plugin plugin = new Plugin();
    plugin.setGroupId("org.jetbrains.kotlin");
    plugin.setArtifactId("kotlin-maven-plugin");
    if (configuration != null) {
      plugin.setConfiguration(dom(configuration));
    }
    for (PluginExecution execution : executions) {
      plugin.addExecution(execution);
    }
    project.getBuild().addPlugin(plugin);
    return project;
  }

  private static PluginExecution execution(String goal, String configuration)
      throws IOException, XmlPullParserException {
    PluginExecution execution = new PluginExecution();
    execution.setId(goal);
    execution.setGoals(List.of(goal));
    execution.setConfiguration(dom(configuration));
    return execution;
  }

  private static Xpp3Dom dom(String xml) throws IOException, XmlPullParserException {
    return Xpp3DomBuilder.build(new StringReader(xml));
  }

  private String absolute(String relativeToModule) {
    return repository.resolve("m").resolve(relativeToModule).toFile().getAbsolutePath();
  }

  @Nested
  class TheKotlinSourceDirs {

    @Test
    void aBuildWithoutTheKotlinPluginHasNone() {
      assertEquals(List.of(), SourceRoots.kotlinSourceDirs(module(), "compile"));
    }

    @Test
    void aPluginWithoutSourceDirsNamesNone() throws Exception {
      MavenProject project = withKotlinPlugin(module(), "<configuration><jvmTarget>17</jvmTarget></configuration>");
      assertEquals(List.of(), SourceRoots.kotlinSourceDirs(project, "compile"));
    }

    /** Maven has interpolated {@code ${project.basedir}} before the plugin reads the model. */
    @Test
    void thePluginConfigurationNamesDirectoriesForEveryGoal() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              "<configuration><sourceDirs><sourceDir>"
                  + absolute("src/main/kotlin")
                  + "</sourceDir></sourceDirs></configuration>");

      assertAll(
          () ->
              assertEquals(
                  List.of(absolute("src/main/kotlin")),
                  SourceRoots.kotlinSourceDirs(project, "compile"),
                  "compile"),
          () ->
              assertEquals(
                  List.of(absolute("src/main/kotlin")),
                  SourceRoots.kotlinSourceDirs(project, "test-compile"),
                  "test-compile"));
    }

    @Test
    void anExecutionNamesDirectoriesForItsGoalOnly() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              null,
              execution(
                  "compile",
                  "<configuration><sourceDirs><sourceDir>"
                      + absolute("src/main/kotlin")
                      + "</sourceDir></sourceDirs></configuration>"),
              execution(
                  "test-compile",
                  "<configuration><sourceDirs><sourceDir>"
                      + absolute("src/test/kotlin")
                      + "</sourceDir></sourceDirs></configuration>"));

      assertAll(
          () ->
              assertEquals(
                  List.of(absolute("src/main/kotlin")),
                  SourceRoots.kotlinSourceDirs(project, "compile"),
                  "compile"),
          () ->
              assertEquals(
                  List.of(absolute("src/test/kotlin")),
                  SourceRoots.kotlinSourceDirs(project, "test-compile"),
                  "test-compile"));
    }

    @Test
    void aRelativeDirectoryIsTakenAgainstTheModule() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              "<configuration><sourceDirs><sourceDir> src/main/kotlin </sourceDir></sourceDirs></configuration>");

      assertEquals(
          List.of(absolute("src/main/kotlin")), SourceRoots.kotlinSourceDirs(project, "compile"));
    }

    @Test
    void aBlankEntryIsLeftOut() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              "<configuration><sourceDirs><sourceDir> </sourceDir><sourceDir>src/main/kotlin</sourceDir></sourceDirs></configuration>");

      assertEquals(
          List.of(absolute("src/main/kotlin")), SourceRoots.kotlinSourceDirs(project, "compile"));
    }

    /** An expression Maven did not interpolate names no directory, and the caller is told which. */
    @Test
    void anUninterpolatedExpressionIsLeftOutAndNamed() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              "<configuration><sourceDirs><sourceDir>${kotlin.dir}/main</sourceDir><sourceDir>src/main/kotlin</sourceDir></sourceDirs></configuration>");

      assertAll(
          () ->
              assertEquals(
                  List.of(absolute("src/main/kotlin")),
                  SourceRoots.kotlinSourceDirs(project, "compile"),
                  "resolved"),
          () ->
              assertEquals(
                  List.of("${kotlin.dir}/main"),
                  SourceRoots.unresolvedKotlinSourceDirs(project, "compile"),
                  "unresolved"));
    }
  }

  @Nested
  class TheMainRoots {

    /** A Kotlin directory the POM names both as a compile root and as a sourceDir is one root. */
    @Test
    void aDirectoryNamedTwiceIsOneRoot() throws Exception {
      Files.createDirectories(repository.resolve("m/src/main/kotlin"));
      MavenProject project =
          withKotlinPlugin(
              module(),
              "<configuration><sourceDirs><sourceDir>src/main/kotlin</sourceDir></sourceDirs></configuration>");
      project.addCompileSourceRoot(absolute("src/main/kotlin"));

      assertEquals(List.of("m/src/main/kotlin"), SourceRoots.of(repository.toFile(), project));
    }

    @Test
    void aDirectoryThatDoesNotExistIsNoRoot() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              "<configuration><sourceDirs><sourceDir>src/main/kotlin</sourceDir></sourceDirs></configuration>");

      assertEquals(List.of(), SourceRoots.of(repository.toFile(), project));
    }
  }

  @Nested
  class TheTestSourceDirectories {

    @Test
    void aModuleWhoseOnlyTestDirectoryIsAKotlinOneHasTests() throws Exception {
      Files.createDirectories(repository.resolve("m/src/test/kotlin"));
      MavenProject project =
          withKotlinPlugin(
              module(),
              null,
              execution(
                  "test-compile",
                  "<configuration><sourceDirs><sourceDir>src/test/kotlin</sourceDir></sourceDirs></configuration>"));

      assertTrue(SourceRoots.hasTestSourceDirectory(project));
    }

    @Test
    void aKotlinTestDirectoryThatDoesNotExistCountsForNothing() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              null,
              execution(
                  "test-compile",
                  "<configuration><sourceDirs><sourceDir>src/test/kotlin</sourceDir></sourceDirs></configuration>"));

      assertFalse(SourceRoots.hasTestSourceDirectory(project));
    }

    @Test
    void theJavaTestRootsComeBeforeTheKotlinOnes() throws Exception {
      MavenProject project =
          withKotlinPlugin(
              module(),
              null,
              execution(
                  "test-compile",
                  "<configuration><sourceDirs><sourceDir>src/test/kotlin</sourceDir></sourceDirs></configuration>"));
      project.addTestCompileSourceRoot(absolute("src/test/java"));

      assertEquals(
          List.of(absolute("src/test/java"), absolute("src/test/kotlin")),
          SourceRoots.testSourceDirs(project));
    }
  }

  /** The path a POM writes relative to the module resolves against that module's directory. */
  @Test
  void theModuleDirectoryIsTheBaseOfARelativePath() throws Exception {
    MavenProject project =
        withKotlinPlugin(
            module(),
            "<configuration><sourceDirs><sourceDir>kt</sourceDir></sourceDirs></configuration>");

    assertEquals(
        new File(project.getBasedir(), "kt").getAbsolutePath(),
        SourceRoots.kotlinSourceDirs(project, "compile").get(0));
  }
}
