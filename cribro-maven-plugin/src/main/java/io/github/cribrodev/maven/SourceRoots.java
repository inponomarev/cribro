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
   * and separated by {@code /}. A root outside the repository, such as a generated one under
   * {@code target}, holds no changed line.
   */
  static List<String> of(File repository, MavenProject project) {
    List<String> roots = new ArrayList<>();
    Set<String> directories = new LinkedHashSet<>(project.getCompileSourceRoots());
    directories.addAll(kotlinSourceDirs(project, "compile"));
    for (String sourceRoot : directories) {
      File directory = new File(sourceRoot);
      Path relative = SourceLines.relativeTo(repository, directory);
      if (relative != null && directory.isDirectory()) {
        String root = relative.toString().replace(File.separatorChar, '/');
        if (!roots.contains(root)) {
          roots.add(root);
        }
      }
    }
    return roots;
  }

  /** Returns whether any of the module's test source directories exists. */
  static boolean hasTestSourceDirectory(MavenProject project) {
    List<String> directories = new ArrayList<>(project.getTestCompileSourceRoots());
    directories.addAll(kotlinSourceDirs(project, "test-compile"));
    return directories.stream().anyMatch(root -> new File(root).isDirectory());
  }

  /**
   * Returns the {@code sourceDirs} that {@code kotlin-maven-plugin} is configured with for the
   * given goal, as absolute paths, or none where the build does not apply it or configures none,
   * in which case the plugin compiles the compile source roots.
   *
   * <p>A directory is usually written as {@code ${project.basedir}/src/main/kotlin}; the
   * expressions a POM commonly uses for it are resolved here, and a directory whose expression is
   * not is left out.
   */
  static List<String> kotlinSourceDirs(MavenProject project, String goal) {
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
    Set<String> directories = new LinkedHashSet<>();
    for (Object configuration : configurations) {
      if (!(configuration instanceof Xpp3Dom)) {
        continue;
      }
      Xpp3Dom sourceDirs = ((Xpp3Dom) configuration).getChild("sourceDirs");
      if (sourceDirs == null) {
        continue;
      }
      for (Xpp3Dom child : sourceDirs.getChildren()) {
        String directory = resolve(child.getValue(), project);
        if (directory != null) {
          directories.add(directory);
        }
      }
    }
    return new ArrayList<>(directories);
  }

  /** Returns the directory as an absolute path, or null where it is blank or an unknown expression. */
  private static String resolve(String value, MavenProject project) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String basedir = project.getBasedir().getPath();
    String resolved =
        value
            .trim()
            .replace("${project.basedir}", basedir)
            .replace("${basedir}", basedir)
            .replace("${project.build.sourceDirectory}", project.getBuild().getSourceDirectory())
            .replace(
                "${project.build.testSourceDirectory}", project.getBuild().getTestSourceDirectory())
            .replace("${project.build.directory}", project.getBuild().getDirectory());
    if (resolved.contains("${")) {
      return null;
    }
    File directory = new File(resolved);
    return (directory.isAbsolute() ? directory : new File(project.getBasedir(), resolved))
        .getAbsolutePath();
  }
}
