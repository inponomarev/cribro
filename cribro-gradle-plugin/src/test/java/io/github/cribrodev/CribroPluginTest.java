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
package io.github.cribrodev;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Project;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plugin replaces Gradle's bundled JaCoCo only with a newer one, so the comparison decides
 * whether a build on a future Gradle is downgraded; the source directories it measures are those
 * that hold sources, whatever else the build does with them.
 */
class CribroPluginTest {

  @TempDir Path directory;

  @Nested
  class TheSourceDirectories {

    private SourceSet main(Project project) {
      return project
          .getExtensions()
          .getByType(SourceSetContainer.class)
          .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    }

    private static Set<String> names(Set<File> dirs) {
      return dirs.stream().map(File::getName).collect(Collectors.toSet());
    }

    @Test
    void theJavaDirectoriesAreSourceDirectoriesAndTheResourcesAreNot() {
      Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
      project.getPluginManager().apply("java");

      assertEquals(Set.of("java"), names(CribroPlugin.sourceDirs(main(project))));
    }

    /**
     * A build may list its Java directory as a resources directory too, for files kept beside the
     * classes such as mapper XML; it stays a source directory.
     */
    @Test
    void aSourceDirectoryThatIsAlsoAResourcesDirectoryStaysASourceDirectory() {
      Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
      project.getPluginManager().apply("java");
      SourceSet main = main(project);
      main.getResources().srcDir("src/main/java");
      main.getResources().exclude("**/*.java");

      assertEquals(Set.of("java"), names(CribroPlugin.sourceDirs(main)));
    }

    /** A plugin that adds its directories to {@code allJava}, as the Kotlin plugin does, is seen. */
    @Test
    void aDirectoryAddedToAllJavaIsASourceDirectory() {
      Project project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build();
      project.getPluginManager().apply("java");
      SourceSet main = main(project);
      main.getAllJava().srcDir("src/main/kotlin");

      assertEquals(Set.of("java", "kotlin"), names(CribroPlugin.sourceDirs(main)));
    }
  }

  @Nested
  class ThePitestPlugins {

    /**
     * Applies cribro to a build that sets the property, as {@code -P} or gradle.properties would.
     * Resolving a configuration locks files under the Gradle user home for the life of the JVM,
     * so that home lies under the build directory rather than in the temporary directory, which
     * JUnit deletes after the test.
     */
    private Project build(String pitestPlugins) throws IOException {
      Files.writeString(
          directory.resolve("gradle.properties"), "cribro.pitestPlugins=" + pitestPlugins + "\n");
      Project project =
          ProjectBuilder.builder()
              .withProjectDir(directory.toFile())
              .withGradleUserHomeDir(new File("build/tmp/projectBuilderUserHome"))
              .build();
      project.getPluginManager().apply("java");
      project.getPluginManager().apply(CribroPlugin.class);
      return project;
    }

    /**
     * Returns what the configuration asks for. The default dependencies are added only when the
     * configuration is resolved, and with no repository the result names them as unresolved.
     */
    private static List<String> dependencies(Project project) {
      return project
          .getConfigurations()
          .getByName(CribroPlugin.PITEST_CONFIGURATION)
          .getIncoming()
          .getResolutionResult()
          .getRoot()
          .getDependencies()
          .stream()
          .map(dependency -> dependency.getRequested().getDisplayName())
          .collect(Collectors.toList());
    }

    @Test
    void theNamedPluginsJoinPitOnItsClasspath() throws IOException {
      Project project = build("com.arcmutate:pitest-kotlin-plugin:1.5.2, demo:other:2");

      List<String> dependencies = dependencies(project);
      assertAll(
          () -> assertTrue(dependencies.contains("com.arcmutate:pitest-kotlin-plugin:1.5.2"), dependencies.toString()),
          () -> assertTrue(dependencies.contains("demo:other:2"), dependencies.toString()),
          () -> assertTrue(dependencies.stream().anyMatch(it -> it.startsWith("org.pitest:pitest-command-line:")), dependencies.toString()));
    }
  }

  @Test
  void aLaterPatchIsNewer() {
    assertTrue(CribroPlugin.compareVersions("0.8.13", "0.8.16") < 0);
  }

  /** Compared as text, 0.8.9 would sort after 0.8.16. */
  @Test
  void partsCompareAsNumbers() {
    assertTrue(CribroPlugin.compareVersions("0.8.9", "0.8.16") < 0);
  }

  @Test
  void aSnapshotComparesByItsNumbers() {
    assertEquals(0, CribroPlugin.compareVersions("0.8.16-SNAPSHOT", "0.8.16"));
  }

  @Test
  void aNewerBundledVersionIsKept() {
    assertTrue(CribroPlugin.compareVersions("0.9.1", "0.8.16") > 0);
  }
}
