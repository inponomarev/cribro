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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Runs PIT over the lines a {@link ChangedLinesTask} listed and prints the mutants no test detected.
 *
 * <p>PIT runs in a JVM of its own, with the jar of this plugin on its classpath so that it finds the
 * changed-lines interceptor and the mutants listener. Everything PIT prints goes to {@code pit.log}
 * in the report directory, which the console names only when PIT fails, together with the end of
 * the log; otherwise the console gets the report of {@link PitestSurvivors}.
 */
@UntrackedTask(because = "the working tree and the branch decide the result, and Gradle tracks neither")
public abstract class PitestDiffTask extends DefaultTask {

  /** PIT and the jar of this plugin, which PIT's process starts with. */
  @Classpath
  public abstract ConfigurableFileCollection getPitestClasspath();

  /** The JVM PIT runs in, which is the one the module's tests run in. */
  @Nested
  public abstract Property<JavaLauncher> getJavaLauncher();

  /** The {@link ChangedLinesFile} that names the lines to mutate. */
  @InputFile
  @PathSensitive(PathSensitivity.NONE)
  public abstract RegularFileProperty getChangedLinesFile();

  /**
   * The classpath the tests run with, the main classes included, and a JUnit Platform launcher
   * where the tests bring none.
   */
  @Classpath
  public abstract ConfigurableFileCollection getTestRuntimeClasspath();

  /** The main classes, the only code PIT mutates; they are on the test runtime classpath too. */
  @Internal
  public abstract ConfigurableFileCollection getMainClassesDirs();

  /** The main source directories, where the report finds the text of a mutated line. */
  @Internal
  public abstract ConfigurableFileCollection getMainSourceDirs();

  /** The JVM arguments of the test task, as {@link PitestJvmArgs} joins them. */
  @Input
  public abstract Property<String> getTestJvmArgs();

  /** The test task's JVM arguments that PIT cannot pass, which the console output names. */
  @Input
  public abstract ListProperty<String> getLeftOutJvmArgs();

  /** A PIT glob for the tests allowed to kill a mutant; unset, every test is. */
  @Input
  @Optional
  public abstract Property<String> getTargetTests();

  /** The PIT mutator group. */
  @Input
  public abstract Property<String> getMutators();

  /** How many mutants PIT analyzes at once. */
  @Input
  public abstract Property<Integer> getThreads();

  /** The constant PIT adds to a test's normal run time before it calls a mutant a timeout. */
  @Input
  public abstract Property<Integer> getTimeoutConst();

  /** How many files the console report describes. */
  @Input
  public abstract Property<Integer> getMaxFiles();

  /** How many lines of one file the console report describes. */
  @Input
  public abstract Property<Integer> getMaxLinesPerFile();

  /** How many lines the console report describes in all. */
  @Input
  public abstract Property<Integer> getMaxLines();

  /** Whether the console prints the whole report. */
  @Input
  public abstract Property<Boolean> getShowAll();

  /** Where PIT writes its reports and the task writes {@code pit.log} and {@code survivors.txt}. */
  @OutputDirectory
  public abstract DirectoryProperty getReportDir();

  /** The directory the printed paths are relative to. */
  @Internal
  public abstract DirectoryProperty getRepositoryRoot();

  @Inject
  protected abstract ExecOperations getExecOperations();

  @Inject
  protected abstract FileSystemOperations getFileSystemOperations();

  /** Returns whether the changed lines include a class of the main sources to mutate. */
  public boolean hasTargetClasses() throws IOException {
    return !ChangedLinesFile.targetClasses(
            ChangedLinesFile.read(getChangedLinesFile().get().getAsFile().toPath()).keySet())
        .isEmpty();
  }

  @TaskAction
  public void run() throws IOException {
    Path changedLines = getChangedLinesFile().get().getAsFile().toPath();
    File reportDir = getReportDir().get().getAsFile();
    // PIT writes no report when no mutant is left on the changed lines, so the directory is emptied
    // first: a report found after the run is this run's.
    getFileSystemOperations().delete(spec -> spec.delete(reportDir));
    Files.createDirectories(reportDir.toPath());
    File log = new File(reportDir, PitestRun.LOG);
    PitestRun pit =
        new PitestRun()
            .targetClasses(ChangedLinesFile.targetClasses(ChangedLinesFile.read(changedLines).keySet()))
            .reportDir(reportDir)
            .targetTests(getTargetTests().getOrNull())
            .mutableCodePaths(List.copyOf(getMainClassesDirs().getFiles()))
            .sourceDirs(List.copyOf(getMainSourceDirs().getFiles()))
            .classPath(List.copyOf(getTestRuntimeClasspath().getFiles()))
            .jvmArgs(getTestJvmArgs().get())
            .mutators(getMutators().get())
            .threads(getThreads().get())
            .timeoutConst(getTimeoutConst().get())
            .changedLines(changedLines.toFile())
            // javac compiles in the Gradle JVM's charset where the build sets no encoding.
            .sourceEncoding(Charset.defaultCharset().name());
    List<String> arguments = pit.arguments();
    ExecResult result;
    try (OutputStream out = new FileOutputStream(log)) {
      result =
          getExecOperations()
              .javaexec(
                  spec -> {
                    spec.setExecutable(
                        getJavaLauncher().get().getExecutablePath().getAsFile().getAbsolutePath());
                    spec.setClasspath(getPitestClasspath());
                    spec.getMainClass().set(PitestRun.MAIN_CLASS);
                    spec.systemProperties(pit.systemProperties());
                    spec.setArgs(arguments);
                    spec.setStandardOutput(out);
                    spec.setErrorOutput(out);
                    spec.setIgnoreExitValue(true);
                  });
    }
    File root = getRepositoryRoot().get().getAsFile();
    // A test that depends on a left-out argument may fail in PIT's first run and be left out, or
    // make PIT fail, so the reader needs to know the run differed from the test task.
    String leftOut = PitestRun.leftOutMessage(getLeftOutJvmArgs().get(), "the test task");
    if (result.getExitValue() != 0) {
      throw new GradleException(
          PitestRun.failureMessage(result.getExitValue(), root, log, leftOut));
    }
    if (leftOut != null) {
      getLogger().quiet(leftOut);
    }
    getLogger()
        .quiet(
            PitestRun.report(
                changedLines.toFile(),
                reportDir,
                getTargetTests().getOrNull(),
                root,
                List.copyOf(getMainSourceDirs().getFiles()),
                getMaxFiles().get(),
                getMaxLinesPerFile().get(),
                getMaxLines().get(),
                getShowAll().get()));
  }
}
