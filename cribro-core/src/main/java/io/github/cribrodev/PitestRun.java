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
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The PIT command line for the lines a branch changed, and the reading of what PIT left behind.
 *
 * <p>The Gradle task and the Maven goal each find the classpath, the JVM, and the JVM arguments of
 * their own tests, start {@link #MAIN_CLASS} with them, and hand the result back here, so that PIT
 * sees the same options and the reader the same report from either tool.
 */
public final class PitestRun {

  /** The class to run, with PIT, its test framework plugin, and the cribro jar on the classpath. */
  public static final String MAIN_CLASS =
      "org.pitest.mutationtest.commandline.MutationCoverageReport";

  /**
   * The charset of the classpath file, which is also the {@code file.encoding} the PIT JVM starts
   * with: PIT reads {@code --classPathFile} in its JVM's default charset, which before JDK 18 is
   * the platform's.
   */
  private static final String FILE_ENCODING = "UTF-8";

  /** The file PIT's own output goes to, in the report directory. */
  public static final String LOG = "pit.log";

  /** How many lines of {@link #LOG} a failure prints. */
  private static final int LOG_TAIL = 30;

  /**
   * The {@code targetTests} filter that lets every test kill a mutant, where the caller names none.
   *
   * <p>PIT reads a filter that starts with {@code ~} as a regular expression of the rest, so this
   * is the expression {@code .*}. It is not the glob {@code *} because on Windows the Java launcher
   * expands an argument that is a bare wildcard into the names of the files in the working
   * directory, so PIT would be told to run a test class named {@code .git}. The launcher leaves
   * {@code ~.*} alone unless a file in that directory starts with {@code ~.}. PIT escapes a {@code
   * +} in either form of filter, so {@code ~.+} would match nothing.
   */
  public static final String ALL_TESTS = "~.*";

  private List<String> targetClasses = List.of();
  private File reportDir;
  private File changedLines;
  private String sourceEncoding;
  private String targetTests;
  private List<File> mutableCodePaths = List.of();
  private List<File> sourceDirs = List.of();
  private List<File> classPath = List.of();
  private String jvmArgs = "";
  private String mutators = "DEFAULTS";
  private int threads = 1;
  private int timeoutConst = 4000;
  private final Map<String, String> extraOptions = new LinkedHashMap<>();

  /** The classes to mutate, as {@link ChangedLinesFile#targetClasses} names them. */
  public PitestRun targetClasses(List<String> targetClasses) {
    this.targetClasses = List.copyOf(targetClasses);
    return this;
  }

  /** Where PIT writes its reports and the caller writes {@link #LOG}. */
  public PitestRun reportDir(File reportDir) {
    this.reportDir = reportDir;
    return this;
  }

  /** The {@link ChangedLinesFile} PIT's filter reads, which limits the mutants to changed lines. */
  public PitestRun changedLines(File changedLines) {
    this.changedLines = changedLines;
    return this;
  }

  /** The charset the build compiles the sources in, which PIT reads them in too. Required. */
  public PitestRun sourceEncoding(String sourceEncoding) {
    this.sourceEncoding = sourceEncoding;
    return this;
  }

  /** A PIT glob for the tests allowed to kill a mutant; null lets every test. */
  public PitestRun targetTests(String targetTests) {
    this.targetTests = targetTests;
    return this;
  }

  /** The class directories PIT may mutate; a glob cannot reach a class outside them. */
  public PitestRun mutableCodePaths(List<File> mutableCodePaths) {
    this.mutableCodePaths = List.copyOf(mutableCodePaths);
    return this;
  }

  /** The source directories, where PIT and the report find the text of a mutated line. */
  public PitestRun sourceDirs(List<File> sourceDirs) {
    this.sourceDirs = List.copyOf(sourceDirs);
    return this;
  }

  /** The classpath the tests run with, the classes to mutate included. */
  public PitestRun classPath(List<File> classPath) {
    this.classPath = List.copyOf(classPath);
    return this;
  }

  /** The JVM arguments of the tests, as {@link PitestJvmArgs} joins them. */
  public PitestRun jvmArgs(String jvmArgs) {
    this.jvmArgs = jvmArgs;
    return this;
  }

  public PitestRun mutators(String mutators) {
    this.mutators = mutators;
    return this;
  }

  public PitestRun threads(int threads) {
    this.threads = threads;
    return this;
  }

  /** The constant PIT adds to a test's normal run time before it calls a mutant a timeout. */
  public PitestRun timeoutConst(int timeoutConst) {
    this.timeoutConst = timeoutConst;
    return this;
  }

  /** Sets one more PIT option, such as {@code excludedGroups}, by its command-line name. */
  public PitestRun option(String name, String value) {
    extraOptions.put(name, value);
    return this;
  }

  /**
   * Returns the PIT command line, after writing the test classpath to a file in the report
   * directory, which PIT reads through {@code --classPathFile}.
   *
   * <p>The file keeps the command line short, which Windows limits to 32767 characters, and it
   * carries paths with commas in them, which {@code --classPath} would split.
   */
  public List<String> arguments() throws IOException {
    if (sourceEncoding == null) {
      throw new IllegalStateException("sourceEncoding is not set");
    }
    File classPathFile = new File(reportDir, "classpath.txt");
    Files.createDirectories(reportDir.toPath());
    Files.write(
        classPathFile.toPath(),
        classPath.stream().map(File::getAbsolutePath).collect(Collectors.toList()),
        Charset.forName(FILE_ENCODING));
    Map<String, String> options = new LinkedHashMap<>();
    options.put("reportDir", reportDir.getAbsolutePath());
    options.put("targetClasses", String.join(",", targetClasses));
    options.put("targetTests", targetTests == null ? ALL_TESTS : targetTests);
    // Only these directories are mutated, so a glob cannot reach a test class of the same name.
    options.put("mutableCodePaths", paths(mutableCodePaths));
    options.put("sourceDirs", paths(sourceDirs));
    options.put("classPathFile", classPathFile.getAbsolutePath());
    // PIT otherwise puts its own launch classpath (commons-lang3, commons-text, ASM, jopt-simple)
    // before the tests' classpath, and a project with a class of the same name gets PIT's copy
    // mutated and tested. Off, PIT keeps only the jars of its own plugins.
    options.put("includeLaunchClasspath", "false");
    options.put("jvmArgs", jvmArgs);
    options.put("mutators", mutators);
    options.put("threads", Integer.toString(threads));
    options.put("outputFormats", "HTML," + MutantsFile.LISTENER_FORMAT);
    options.put("timestampedReports", "false");
    // A changed line can be a comment or a declaration with no instruction to mutate.
    options.put("failWhenNoMutations", "false");
    options.put("timeoutConst", Integer.toString(timeoutConst));
    // PIT runs every test, including those the build's filter excludes, and refuses to start when
    // one fails before any mutation. A test failing there is left out instead.
    options.put("skipFailingTests", "true");
    // The JVM's default charset is the classpath file's, so the sources need theirs named.
    options.put("inputEncoding", sourceEncoding);
    options.putAll(extraOptions);
    List<String> arguments = new ArrayList<>();
    options.forEach(
        (name, value) -> {
          arguments.add("--" + name);
          arguments.add(value);
        });
    return arguments;
  }

  /**
   * Returns the system properties the PIT JVM starts with: the {@link ChangedLinesFile} for PIT's
   * filter, and the {@code file.encoding} PIT reads the classpath file of {@link #arguments()} in.
   */
  public Map<String, String> systemProperties() {
    if (changedLines == null) {
      throw new IllegalStateException("changedLines is not set");
    }
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(ChangedLinesFile.PITEST_PROPERTY, changedLines.getAbsolutePath());
    properties.put("file.encoding", FILE_ENCODING);
    return properties;
  }

  private static String paths(List<File> files) {
    return files.stream().map(File::getAbsolutePath).collect(Collectors.joining(","));
  }

  /**
   * Returns the dependency coordinates of the further PIT plugins a {@code cribro.pitestPlugins}
   * property names, which both build tools put on PIT's classpath: comma-separated, each trimmed,
   * blanks left out. Null, the property unset, names none.
   */
  public static List<String> plugins(String property) {
    List<String> coordinates = new ArrayList<>();
    if (property == null) {
      return coordinates;
    }
    for (String coordinate : property.split(",")) {
      if (!coordinate.isBlank()) {
        coordinates.add(coordinate.trim());
      }
    }
    return coordinates;
  }

  /**
   * Returns the line naming the JVM arguments PIT ran the tests without, or null where there are
   * none.
   *
   * @param source what the arguments came from, such as {@code the test task}
   */
  public static String leftOutMessage(List<String> leftOut, String source) {
    if (leftOut.isEmpty()) {
      return null;
    }
    return "PIT ran the tests without these JVM arguments of "
        + source
        + ", since it splits arguments at commas and whitespace: "
        + String.join(" ", leftOut);
  }

  /**
   * Returns the message of a PIT run that failed: its exit code and the end of {@link #LOG}.
   *
   * @param leftOut the line {@link #leftOutMessage} returned, or null
   */
  public static String failureMessage(int exitCode, File repository, File log, String leftOut)
      throws IOException {
    List<String> lines = Files.readAllLines(log.toPath());
    return "PIT failed with exit code "
        + exitCode
        + ". The end of "
        + SourceLines.relativize(repository, log)
        + ":\n"
        + String.join("\n", lines.subList(Math.max(0, lines.size() - LOG_TAIL), lines.size()))
        + (leftOut == null ? "" : "\n" + leftOut);
  }

  /**
   * Reads the mutants a successful run left in the report directory, writes the whole report to
   * {@code survivors.txt} there, and returns the text to print.
   *
   * @param changedLines the {@link ChangedLinesFile} the run read, whose header names the base and
   *     whose entries name the path of each changed file
   * @param targetTests the glob the run was given, or null
   * @param sourceDirs absolute source directories, searched in order for a mutated file the
   *     changed-lines file does not name or names at a path that no longer exists
   */
  public static String report(
      File changedLines,
      File reportDir,
      String targetTests,
      File repository,
      List<File> sourceDirs,
      int maxFiles,
      int maxLinesPerFile,
      int maxLines,
      boolean showAll)
      throws IOException {
    String header = ChangedLinesFile.readHeader(changedLines.toPath());
    String tests = targetTests != null ? "tests matching " + targetTests : "all tests";
    PitestSurvivors report =
        PitestSurvivors.read(
            (header == null ? "against an unknown base" : header) + ", " + tests,
            new File(reportDir, MutantsFile.NAME),
            repository,
            sourceDirs,
            ChangedLinesFile.readPaths(changedLines.toPath()));
    return report.writeAndRender(
        repository,
        new File(reportDir, "survivors.txt"),
        maxFiles,
        maxLinesPerFile,
        maxLines,
        showAll,
        List.of(SourceLines.relativize(repository, new File(reportDir, "index.html"))));
  }
}
