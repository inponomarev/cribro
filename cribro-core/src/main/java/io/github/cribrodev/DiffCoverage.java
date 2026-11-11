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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Prints which of the lines a branch changed a test run executed, for one module.
 *
 * <p>It intersects two sets: the lines a git diff marks as changed, and the lines JaCoCo XML
 * reports record as executed. It reads no coverage data from the base commit, so nothing has to be
 * recorded before the run or stored in the repository; the base is a git ref, used only to decide
 * which lines count as changed.
 *
 * <p>The Gradle task and the Maven extension each collect the inputs their own way and print what
 * {@link #describe()} returns, so that both tools word every outcome alike.
 */
public final class DiffCoverage {

  public static final String NO_REPORT =
      "Diff coverage: no JaCoCo report was produced, so no line counts as executed.";

  /**
   * Returns the text printed when no base ref resolves.
   *
   * @param flag how the build tool sets a property on its command line: {@code -P} or {@code -D}
   */
  public static String noBase(String flag) {
    return "Diff coverage: no base ref resolved, so no line counts as changed. "
        + ChangedLines.noBaseHint(flag);
  }

  /**
   * Returns whether any of the changed files lies under one of the source roots, so that a caller
   * with many modules prepares a report only for those the change reached.
   *
   * @param sourceRoots source directories relative to the repository root
   */
  public static boolean reaches(List<String> sourceRoots, ChangedLines diff) {
    return DiffCoverageReport.reaches(sourceRoots, diff.lines().keySet());
  }

  private final Git git;
  private String baseRef;
  private String propertyFlag = "-P";
  private List<String> sourceRoots = List.of();
  private List<File> reports = List.of();
  private JacocoLines coverage;
  private String scopeDescription = "";
  private boolean filtered;
  private int maxFiles = 10;
  private int maxLinesPerFile = 10;
  private int maxLinesTotal = 50;
  private boolean showAll;
  private File outputFile;
  private Long measuredAt;
  private Function<List<String>, String> testFilter = DiffCoverage::gradleTestFilter;

  private DiffCoverage(Git git) {
    this.git = git;
  }

  /** Starts a report whose diff is taken in the repository {@code git} runs in. */
  public static DiffCoverage in(Git git) {
    return new DiffCoverage(git);
  }

  /** The ref whose merge base with HEAD the diff is taken from; null tries the defaults. */
  public DiffCoverage baseRef(String baseRef) {
    this.baseRef = baseRef;
    return this;
  }

  /** How the build tool sets a property on its command line, for the hint about the base ref. */
  public DiffCoverage propertyFlag(String propertyFlag) {
    this.propertyFlag = propertyFlag;
    return this;
  }

  /** Source directories of the module, relative to the repository root. */
  public DiffCoverage sourceRoots(List<String> sourceRoots) {
    this.sourceRoots = List.copyOf(sourceRoots);
    return this;
  }

  /**
   * The JaCoCo XML reports the counters come from; files that do not exist are ignored. Their paths
   * are printed below the report, and they are read unless {@link #coverage} is set.
   */
  public DiffCoverage reports(List<File> reports) {
    this.reports = List.copyOf(reports);
    return this;
  }

  /** The counters the caller already holds for the reports, so that they are not read back. */
  public DiffCoverage coverage(JacocoLines coverage) {
    this.coverage = coverage;
    return this;
  }

  /** What the run covered, printed so that a filtered run is not read as missing coverage. */
  public DiffCoverage scope(String scopeDescription, boolean filtered) {
    this.scopeDescription = scopeDescription;
    this.filtered = filtered;
    return this;
  }

  /** How much of the report the console shows; {@code showAll} lifts every limit. */
  public DiffCoverage limits(int maxFiles, int maxLinesPerFile, int maxLinesTotal, boolean showAll) {
    this.maxFiles = maxFiles;
    this.maxLinesPerFile = maxLinesPerFile;
    this.maxLinesTotal = maxLinesTotal;
    this.showAll = showAll;
    return this;
  }

  /**
   * The file the whole report is written to, whatever the console limits leave out.
   *
   * <p>The printed block ends with its path, so that a reader who needs the rest reads a report
   * rather than the JaCoCo XML.
   */
  public DiffCoverage outputFile(File outputFile) {
    this.outputFile = outputFile;
    return this;
  }

  /**
   * When the execution data was recorded, in milliseconds since the epoch; a source edited later
   * is named as stale. Unset, the newest report's modification time stands in for it.
   */
  public DiffCoverage measuredAt(long measuredAt) {
    this.measuredAt = measuredAt;
    return this;
  }

  /**
   * How the build tool narrows a test run to some test methods, given each as {@code
   * pkg.Class.method}; the Gradle form, {@code --tests 'pkg.Class.method'}, is the default.
   */
  public DiffCoverage testFilter(Function<List<String>, String> testFilter) {
    this.testFilter = testFilter;
    return this;
  }

  static String gradleTestFilter(List<String> tests) {
    return tests.stream().map(test -> "--tests '" + test + "'").collect(Collectors.joining(" "));
  }

  /**
   * Returns the text this run prints. Where the change reached none of the module, that is only
   * the lines {@link ChangedLines#unreadable()} returns, naming the untracked files this JVM could
   * not read, often none.
   *
   * <p>The output file is rewritten here, and is deleted where this run measured nothing, so that
   * it holds this run's report or no report at all.
   *
   * @throws CribroException where the base ref named explicitly does not resolve
   */
  public String describe() throws IOException {
    // The file belongs to this report, so a run that measures nothing must not leave the last run's
    // report where a reader would take it for this one. A path that names no regular file is left
    // to the write below, which reports what is wrong with it.
    Path output = outputFile.toPath();
    if (Files.isRegularFile(output)) {
      Files.delete(output);
    }
    File repository = git.repositoryRoot();
    ChangedLines diff = git.changedLines(baseRef);
    if (diff == null) {
      return noBase(propertyFlag);
    }
    List<String> unreadable = new ArrayList<>();
    for (String note : diff.unreadable()) {
      unreadable.add("Diff coverage: " + note);
    }
    Map<String, NavigableSet<Integer>> changed = diff.lines();

    // A module the change never reached has nothing to say, and saying it anyway would put a line
    // in every whole-build run for every module with no test and for every module the change
    // missed. Decided before the report, since a module with no test produces none.
    if (!DiffCoverageReport.reaches(sourceRoots, changed.keySet())) {
      return String.join("\n", unreadable);
    }
    List<File> existing = new ArrayList<>(reports);
    existing.removeIf(file -> !file.isFile());
    if (existing.isEmpty()) {
      return NO_REPORT;
    }
    List<ChangedFile> files = ChangedLines.underSourceRoots(repository, changed, sourceRoots);
    JacocoLines lines = coverage;
    if (lines == null) {
      Set<String> keys = files.stream().map(ChangedFile::key).collect(Collectors.toSet());
      lines = JacocoLines.read(existing, keys::contains);
    }
    SourceLines sources = SourceLines.under(repository);
    DiffCoverageReport report = DiffCoverageReport.measure(files, lines, sources);
    ExposedCodeReport exposed =
        ExposedCodeReport.measure(
            ExposedCode.find(repository, changed, sourceRoots),
            files.stream().collect(Collectors.toMap(ChangedFile::path, ChangedFile::key)),
            lines,
            sources);
    Function<List<ExposedCode.TestMethod>, String> tests =
        methods ->
            testFilter.apply(
                methods.stream().map(ExposedCode.TestMethod::toString).collect(Collectors.toList()));
    String scope =
        "against " + diff.ref() + " (" + diff.shortCommit() + "), from " + scopeDescription;
    List<String> stale =
        measuredAt != null
            ? staleSources(repository, report.measuredPaths(), measuredAt)
            : staleSources(repository, report.measuredPaths(), existing);
    DiffCoverageReport.Limits limits =
        showAll
            ? DiffCoverageReport.Limits.NONE
            : new DiffCoverageReport.Limits(maxFiles, maxLinesPerFile, maxLinesTotal);
    String full =
        withExposed(
            report.render(scope, filtered, stale, DiffCoverageReport.Limits.NONE),
            exposed.render(scopeDescription, filtered, tests, DiffCoverageReport.Limits.NONE));
    String console =
        withExposed(
            report.render(scope, filtered, stale, limits),
            exposed.render(scopeDescription, filtered, tests, limits));
    String text = console.isEmpty() ? console : withReportPaths(repository, existing, console, full);
    if (unreadable.isEmpty()) {
      return text;
    }
    // The silence rule is for a module the change never reached. A file the report could not read
    // is a change it did reach and could not measure, so it is said either way.
    String notes = String.join("\n", unreadable);
    return text.isEmpty() ? notes : notes + "\n" + text;
  }

  /** Appends the exposed-code block to a report that printed anything at all. */
  private static String withExposed(String report, String exposed) {
    return report.isEmpty() || exposed.isEmpty() ? report : report + "\n\n" + exposed;
  }

  /**
   * Writes the whole report to a file and returns the text to print, ending in the paths of both.
   *
   * <p>The console block is a summary of work, so the reader is left with somewhere to read the
   * rest: the file holds every uncovered line, and the JaCoCo XML holds the run it was measured
   * from. A file that cannot be written is reported in place of its path, since a path that names
   * no file sends the reader to a stale report or to none.
   */
  private String withReportPaths(
      File repository, List<File> reports, String console, String full) {
    StringBuilder jacoco =
        new StringBuilder(reports.size() == 1 ? "Full JaCoCo report:" : "Full JaCoCo reports:");
    for (File report : reports) {
      jacoco.append("\n  ").append(SourceLines.relativize(repository, report));
    }
    String diffCoverage;
    try {
      Files.createDirectories(outputFile.toPath().getParent());
      Files.writeString(outputFile.toPath(), full + "\n\n" + jacoco + "\n");
      diffCoverage =
          "Full diff coverage report:\n  " + SourceLines.relativize(repository, outputFile);
    } catch (IOException e) {
      diffCoverage =
          "Full diff coverage report: cannot write "
              + SourceLines.relativize(repository, outputFile)
              + ": "
              + e;
    }
    return console + "\n\n" + diffCoverage + "\n" + jacoco;
  }

  /**
   * Returns the files among measuredPaths that were edited after the execution data was recorded.
   *
   * <p>JaCoCo numbers lines against the sources that were compiled, so an edit after the run shifts
   * every line below it and the report stops naming the code it was recorded for. Only the files
   * the counts came from are checked; an edit elsewhere moves no line number in this report.
   */
  static List<String> staleSources(
      File repository, List<String> measuredPaths, List<File> reports) {
    // The newest report decides, since it is the one the counts were read from.
    long newest = reports.stream().mapToLong(File::lastModified).max().orElse(Long.MAX_VALUE);
    return staleSources(repository, measuredPaths, newest);
  }

  /** Returns the files among measuredPaths that were edited after {@code measuredAt}. */
  static List<String> staleSources(File repository, List<String> measuredPaths, long measuredAt) {
    List<String> stale = new ArrayList<>();
    for (String path : measuredPaths) {
      File source = new File(repository, path);
      if (source.isFile() && source.lastModified() > measuredAt) {
        stale.add(path);
      }
    }
    return stale;
  }
}
