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

import io.github.cribrodev.SourceLines;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The main source directories of a module in the form git diff prints paths in, so that the
 * coverage report and {@code cribro:pitest-diff} select the same changed files.
 *
 * <p>Maven's compile source roots hold the Java sources. A Kotlin module keeps its sources where
 * the {@code sourceDirs} of {@code kotlin-maven-plugin} say, usually {@code src/main/kotlin}, which
 * is a compile source root only where the POM also names it as one, so those directories are read
 * from the plugin's configuration.
 */
final class SourceRoots {

  static final String KOTLIN_PLUGIN = "org.jetbrains.kotlin:kotlin-maven-plugin";

  private SourceRoots() {}

  /**
   * Returns the module's main source directories that exist under the repository, relative to it
   * and separated by {@code /}, each once. A root outside the repository, such as a generated one
   * under {@code target}, holds no changed line.
   */
  static List<String> of(File repository, MavenProject project) {
    Set<String> roots = new LinkedHashSet<>();
    List<String> directories = new ArrayList<>(project.getCompileSourceRoots());
    directories.addAll(kotlinSourceDirs(project, "compile"));
    for (String sourceRoot : directories) {
      File directory = new File(sourceRoot);
      Path relative = SourceLines.relativeTo(repository, directory);
      if (relative != null && directory.isDirectory()) {
        roots.add(relative.toString().replace(File.separatorChar, '/'));
      }
    }
    return new ArrayList<>(roots);
  }

  /**
   * Returns the module's test source directories as absolute paths: Maven's test compile source
   * roots, then the {@code sourceDirs} of {@code kotlin-maven-plugin}'s {@code test-compile} goal.
   */
  static List<String> testSourceDirs(MavenProject project) {
    List<String> directories = new ArrayList<>(project.getTestCompileSourceRoots());
    directories.addAll(kotlinSourceDirs(project, "test-compile"));
    return directories;
  }

  /** Returns whether any of the module's test source directories exists. */
  static boolean hasTestSourceDirectory(MavenProject project) {
    return testSourceDirs(project).stream().anyMatch(root -> new File(root).isDirectory());
  }

  /**
   * Returns the {@code sourceDirs} that {@code kotlin-maven-plugin} is configured with for the
   * goal, as absolute paths, or none where the build does not apply it or configures none, in
   * which case the plugin compiles the compile source roots.
   *
   * <p>The plugin's configuration and the configuration of each execution bound to the goal are
   * read. Their values arrive interpolated by Maven's model builder, so a directory written as
   * {@code ${project.basedir}/src/main/kotlin} is already absolute; a relative directory is taken
   * relative to the module's base directory. A value that still holds a {@code ${...}} expression
   * is one Maven could not interpolate, and it is left out; {@link #unresolvedKotlinSourceDirs}
   * names it.
   *
   * @param goal {@code compile} or {@code test-compile}
   */
  static List<String> kotlinSourceDirs(MavenProject project, String goal) {
    Set<String> directories = new LinkedHashSet<>();
    for (String value : sourceDirValues(project, goal)) {
      if (!value.contains("${")) {
        File directory = new File(value);
        directories.add(
            (directory.isAbsolute() ? directory : new File(project.getBasedir(), value))
                .getAbsolutePath());
      }
    }
    return new ArrayList<>(directories);
  }

  /**
   * Returns the {@code sourceDirs} of the goal that {@link #kotlinSourceDirs} left out, as the POM
   * writes them, each holding a {@code ${...}} expression Maven did not interpolate. A caller says
   * so, since the changes under such a directory are not measured and the user can fix the POM.
   */
  static List<String> unresolvedKotlinSourceDirs(MavenProject project, String goal) {
    List<String> unresolved = new ArrayList<>();
    for (String value : sourceDirValues(project, goal)) {
      if (value.contains("${")) {
        unresolved.add(value);
      }
    }
    return unresolved;
  }

  /** Returns the non-blank {@code sourceDir} values configured for the goal, trimmed, in order. */
  private static List<String> sourceDirValues(MavenProject project, String goal) {
    Plugin plugin = project.getPlugin(KOTLIN_PLUGIN);
    if (plugin == null) {
      return List.of();
    }
    List<Object> configurations = new ArrayList<>();
    configurations.add(plugin.getConfiguration());
    for (PluginExecution execution : plugin.getExecutions()) {
      if (execution.getGoals().contains(goal)) {
        configurations.add(execution.getConfiguration());
      }
    }
    List<String> values = new ArrayList<>();
    for (Object configuration : configurations) {
      if (!(configuration instanceof Xpp3Dom)) {
        continue;
      }
      Xpp3Dom sourceDirs = ((Xpp3Dom) configuration).getChild("sourceDirs");
      if (sourceDirs == null) {
        continue;
      }
      for (Xpp3Dom child : sourceDirs.getChildren()) {
        String value = child.getValue();
        if (value != null && !value.isBlank()) {
          values.add(value.trim());
        }
      }
    }
    return values;
  }
}
