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

import io.github.cribrodev.ChangedLines;
import io.github.cribrodev.CribroException;
import io.github.cribrodev.DiffCoverage;
import io.github.cribrodev.Git;
import io.github.cribrodev.JacocoLines;
import io.github.cribrodev.SourceFileKey;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.BuildSummary;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.xml.XMLFormatter;

/**
 * Builds the diff coverage text a Maven session prints when it ends.
 *
 * <p>The execution data of every module's tests is merged before any module is analyzed, since a
 * module's code can be tested from another module, as Guava's is from {@code guava-tests}. Each
 * module the change reached then gets a JaCoCo XML report of its own classes and a diff coverage
 * block, worded by {@link DiffCoverage} as the Gradle plugin words it.
 */
final class CoverageReport {

  private final MavenSession session;

  private final CribroState state;

  private final List<String> notes = new ArrayList<>();

  CoverageReport(MavenSession session, CribroState state) {
    this.session = session;
    this.state = state;
  }

  /** Returns the text to print, which is empty where no test ran under cribro. */
  String describe() throws IOException {
    List<MavenProject> projects = session.getProjects();
    List<TestRun> runs = state.runs(projects);
    if (runs.isEmpty()) {
      return "";
    }
    // A module this run leaves without a report must not keep the last run's, which a reader would
    // take for this one; writeXml and DiffCoverage.describe write the reports this run has.
    for (MavenProject project : projects) {
      File directory = new File(project.getBuild().getDirectory(), "cribro");
      Files.deleteIfExists(new File(directory, "jacoco.xml").toPath());
      Files.deleteIfExists(new File(directory, "diff-coverage.txt").toPath());
    }
    for (TestRun run : runs) {
      if (run.note != null) {
        notes.add(run.note);
      }
    }
    ExecutionDataStore data = new ExecutionDataStore();
    List<SessionInfo> sessions = new ArrayList<>();
    Set<TestRun> measuredRuns = read(runs, data, sessions);
    List<TestRun> measured = new ArrayList<>();
    long measuredAt = 0;
    for (TestRun run : runs) {
      if (measuredRuns.contains(run)) {
        measured.add(run);
        measuredAt = Math.max(measuredAt, run.end == 0 ? System.currentTimeMillis() : run.end);
      }
    }
    if (measured.isEmpty()) {
      // Surefire ran with no test, or skipped them, as -DskipTests does: there is nothing to say.
      return String.join("\n", notes);
    }
    addFailures(runs, projects);
    Git git = state.git();
    if (git == null) {
      notes.add("Diff coverage: " + session.getExecutionRootDirectory() + " is not in a git work tree.");
      return String.join("\n", notes);
    }
    // The top-level POM is where a build sets the base for every module; pitest-diff reads it there too.
    String base =
        CribroProperties.get(session, session.getTopLevelProject(), ChangedLines.BASE_PROPERTY);
    ChangedLines diff;
    try {
      diff = git.changedLines(base);
    } catch (CribroException e) {
      notes.add("Diff coverage: " + e.getMessage());
      return String.join("\n", notes);
    }
    if (diff == null) {
      notes.add(DiffCoverage.noBase("-D"));
      return String.join("\n", notes);
    }
    List<String> blocks = new ArrayList<>();
    String scope = scope(measured);
    boolean filtered = measured.stream().anyMatch(run -> run.filtered);
    for (MavenProject project : projects) {
      for (String unresolved : SourceRoots.unresolvedKotlinSourceDirs(project, "compile")) {
        notes.add("Diff coverage: " + PitestDiffMojo.unresolvedSourceDirMessage(project, unresolved));
      }
      List<String> sourceRoots = SourceRoots.of(git.repositoryRoot(), project);
      if (!DiffCoverage.reaches(sourceRoots, diff)) {
        continue;
      }
      File directory = new File(project.getBuild().getDirectory(), "cribro");
      File xml = new File(directory, "jacoco.xml");
      File classes = new File(project.getBuild().getOutputDirectory());
      JacocoLines coverage =
          classes.isDirectory() ? analyze(project, classes, data, sessions, xml) : null;
      String text =
          DiffCoverage.in(git)
              .baseRef(base)
              .propertyFlag("-D")
              .testFilter(CoverageReport::surefireTestFilter)
              .sourceRoots(sourceRoots)
              .reports(List.of(xml))
              .coverage(coverage)
              .scope(scope, filtered)
              .limits(
                  CribroProperties.intValue(session, project, "cribro.diffCoverageMaxFiles", 10),
                  CribroProperties.intValue(
                      session, project, "cribro.diffCoverageMaxLinesPerFile", 10),
                  CribroProperties.intValue(session, project, "cribro.diffCoverageMaxLines", 50),
                  CribroProperties.flag(session, project, "cribro.diffCoverageShowAll", false))
              .outputFile(new File(directory, "diff-coverage.txt"))
              .measuredAt(measuredAt)
              .describe();
      if (!text.isEmpty()) {
        blocks.add(text);
      }
    }
    List<String> all = new ArrayList<>(notes);
    all.addAll(blocks);
    return String.join("\n\n", all);
  }

  /**
   * Returns the Surefire properties that run only the given {@code pkg.Class.method} tests, in
   * every module of an {@code -am} build, most of which have none of them.
   */
  static String surefireTestFilter(List<String> tests) {
    List<String> patterns = new ArrayList<>();
    for (String test : tests) {
      int dot = test.lastIndexOf('.');
      patterns.add(test.substring(0, dot) + "#" + test.substring(dot + 1));
    }
    return "-Dtest='" + String.join(",", patterns) + "' -Dsurefire.failIfNoSpecifiedTests=false";
  }

  /**
   * Adds the sessions that started while one of the runs' tests ran, and their data, and returns
   * the runs that have such a session.
   *
   * <p>Each file is read once, however many runs write it: a build whose JaCoCo agent appends
   * every module to one file would otherwise read that file once per module. A session belongs to
   * every run whose window holds its start, and modules that build in parallel have overlapping
   * windows.
   *
   * <p>A session's data follows its header in the file, so the header read last decides whether
   * the data counts.
   */
  static Set<TestRun> read(List<TestRun> runs, ExecutionDataStore data, List<SessionInfo> sessions)
      throws IOException {
    Map<File, List<TestRun>> writers = new LinkedHashMap<>();
    for (TestRun run : runs) {
      for (File file : run.execFiles()) {
        writers.computeIfAbsent(file.getCanonicalFile(), f -> new ArrayList<>()).add(run);
      }
    }
    Set<TestRun> measured = new HashSet<>();
    for (Map.Entry<File, List<TestRun>> entry : writers.entrySet()) {
      boolean[] counts = {false};
      try (InputStream in = new BufferedInputStream(new FileInputStream(entry.getKey()))) {
        ExecutionDataReader reader = new ExecutionDataReader(in);
        reader.setSessionInfoVisitor(
            info -> {
              counts[0] = false;
              for (TestRun run : entry.getValue()) {
                if (run.contains(info.getStartTimeStamp())) {
                  counts[0] = true;
                  measured.add(run);
                }
              }
              if (counts[0]) {
                sessions.add(info);
              }
            });
        reader.setExecutionDataVisitor(
            execution -> {
              if (counts[0]) {
                data.visitClassExecution(execution);
              }
            });
        reader.read();
      }
    }
    return measured;
  }

  /**
   * Names the modules whose tests failed, and the modules the build stopped before, since the
   * counts then cover fewer tests than the module has.
   */
  private void addFailures(List<TestRun> runs, List<MavenProject> projects) {
    List<String> failed =
        runs.stream()
            .filter(run -> run.failed)
            .map(run -> run.project.getArtifactId())
            .collect(Collectors.toList());
    if (!failed.isEmpty()) {
      notes.add(
          "Diff coverage: tests failed in "
              + String.join(", ", failed)
              + ", so the counts include only the tests that ran before the failure.");
    }
    List<String> notBuilt = new ArrayList<>();
    for (MavenProject project : projects) {
      BuildSummary summary = session.getResult().getBuildSummary(project);
      if (summary == null && SourceRoots.hasTestSourceDirectory(project)) {
        notBuilt.add(project.getArtifactId());
      }
    }
    if (!notBuilt.isEmpty()) {
      notes.add(
          "Diff coverage: the build stopped before the tests of "
              + String.join(", ", notBuilt)
              + " ran.");
    }
  }

  static String scope(List<TestRun> measured) {
    Set<String> filters = new LinkedHashSet<>();
    for (TestRun run : measured) {
      if (run.testFilter != null) {
        filters.add("-Dtest=" + run.testFilter);
      }
      if (run.groups != null) {
        filters.add("-Dgroups=" + run.groups);
      }
    }
    String modules =
        measured.stream().map(run -> run.project.getArtifactId()).collect(Collectors.joining(", "));
    return "the tests of " + modules + (filters.isEmpty() ? "" : " " + String.join(" ", filters));
  }

  /**
   * Returns the counters of the module's classes, and writes them to a JaCoCo XML report.
   *
   * <p>A class that the tests ran in a different version, as after a rebuild without its tests,
   * gets no counts, and the report names it.
   */
  private JacocoLines analyze(
      MavenProject project,
      File classes,
      ExecutionDataStore data,
      List<SessionInfo> sessions,
      File xml)
      throws IOException {
    CoverageBuilder builder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(data, builder);
    Path root = classes.toPath();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files =
          walk.filter(path -> path.toString().endsWith(".class"))
              // A multi-release jar's module keeps other versions of its classes under
              // META-INF/versions, and JaCoCo refuses two classes of one name in a bundle.
              .filter(path -> !root.relativize(path).startsWith("META-INF"))
              .sorted()
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      try (InputStream in = Files.newInputStream(file)) {
        analyzer.analyzeClass(in, file.toString());
      }
    }
    List<String> mismatched = new ArrayList<>();
    for (IClassCoverage coverage : builder.getNoMatchClasses()) {
      mismatched.add(coverage.getName().replace('/', '.'));
    }
    if (!mismatched.isEmpty()) {
      notes.add(
          "Diff coverage: the tests ran another build of "
              + mismatched.size()
              + " classes of "
              + project.getArtifactId()
              + ", so their lines count as not executed: "
              + String.join(", ", mismatched.subList(0, Math.min(5, mismatched.size())))
              + (mismatched.size() > 5 ? ", ..." : ""));
    }
    IBundleCoverage bundle = builder.getBundle(project.getArtifactId());
    Files.createDirectories(xml.toPath().getParent());
    try (OutputStream out = Files.newOutputStream(xml.toPath())) {
      IReportVisitor visitor = new XMLFormatter().createVisitor(out);
      visitor.visitInfo(sessions, data.getContents());
      visitor.visitBundle(bundle, null);
      visitor.visitEnd();
    }
    return lines(bundle);
  }

  /** Returns the counters of every line the XML report of the bundle lists. */
  static JacocoLines lines(IBundleCoverage bundle) {
    JacocoLines.Builder lines = new JacocoLines.Builder();
    for (IPackageCoverage packageCoverage : bundle.getPackages()) {
      for (ISourceFileCoverage sourceFile : packageCoverage.getSourceFiles()) {
        String key = SourceFileKey.of(packageCoverage.getName(), sourceFile.getName());
        lines.sourceFile(key);
        for (int number = sourceFile.getFirstLine(); number <= sourceFile.getLastLine(); number++) {
          ILine line = sourceFile.getLine(number);
          // The XML report leaves out a line that emits no bytecode, and so does this.
          if (line.getStatus() == ICounter.EMPTY) {
            continue;
          }
          lines.line(
              key,
              number,
              line.getInstructionCounter().getCoveredCount(),
              line.getBranchCounter().getCoveredCount(),
              line.getBranchCounter().getMissedCount());
        }
      }
    }
    return lines.build();
  }
}
