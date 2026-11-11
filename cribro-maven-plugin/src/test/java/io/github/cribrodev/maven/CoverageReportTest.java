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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import io.github.cribrodev.JacocoLines;
import org.apache.maven.project.MavenProject;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.internal.analysis.BundleCoverageImpl;
import org.jacoco.core.internal.analysis.CounterImpl;
import org.jacoco.core.internal.analysis.PackageCoverageImpl;
import org.jacoco.core.internal.analysis.SourceFileCoverageImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How the report reads the execution data of a session's test runs. */
class CoverageReportTest {

  @TempDir Path directory;

  private File execFile(long... sessionStarts) throws IOException {
    File file = directory.resolve("jacoco.exec").toFile();
    try (OutputStream out = Files.newOutputStream(file.toPath())) {
      ExecutionDataWriter writer = new ExecutionDataWriter(out);
      for (long start : sessionStarts) {
        writer.visitSessionInfo(new SessionInfo("s" + start, start, start + 1));
        writer.visitClassExecution(new ExecutionData(start, "C" + start, new boolean[] {true}));
      }
    }
    return file;
  }

  private static TestRun run(String artifactId, long start, long end, File execFile) {
    MavenProject project = new MavenProject();
    project.setArtifactId(artifactId);
    TestRun run = TestRun.measured(project, start, null, null, execFile);
    run.end = end;
    return run;
  }

  /**
   * Modules whose agent appends to one file each count their own sessions, and a session of an
   * earlier build counts for none.
   */
  @Test
  void aSharedFileGivesEachRunTheSessionsOfItsWindow() throws IOException {
    File file = execFile(500, 1500, 2500);
    TestRun first = run("first", 1000, 2000, file);
    TestRun second = run("second", 2001, 3000, file);
    TestRun third = run("third", 3001, 4000, file);
    ExecutionDataStore data = new ExecutionDataStore();
    List<SessionInfo> sessions = new ArrayList<>();

    Set<TestRun> measured = CoverageReport.read(List.of(first, second, third), data, sessions);

    assertEquals(Set.of(first, second), measured, "measured runs");
    assertEquals(
        List.of("s1500", "s2500"),
        sessions.stream().map(SessionInfo::getId).collect(Collectors.toList()),
        "sessions");
    assertEquals(
        List.of("C1500", "C2500"),
        data.getContents().stream().map(ExecutionData::getName).sorted().collect(Collectors.toList()),
        "classes");
  }

  /** Modules that build in parallel under -T run their tests in overlapping windows. */
  @Test
  void aSessionInTwoOverlappingWindowsIsReadOnce() throws IOException {
    File file = execFile(1500);
    TestRun first = run("first", 1000, 2000, file);
    TestRun second = run("second", 1200, 2200, file);
    List<SessionInfo> sessions = new ArrayList<>();

    Set<TestRun> measured =
        CoverageReport.read(List.of(first, second), new ExecutionDataStore(), sessions);

    assertEquals(Set.of(first, second), measured, "measured runs");
    assertEquals(1, sessions.size(), () -> "sessions: " + sessions);
  }

  /**
   * The counters match what the XML report writes for the same lines: a line that emits no
   * bytecode is left out, and a file with no line at all is still known.
   */
  @Test
  void theLinesOfAnAnalyzedBundleCarryTheCountersTheXmlReportWrites() {
    SourceFileCoverageImpl sample = new SourceFileCoverageImpl("Sample.java", "com/example");
    sample.increment(CounterImpl.getInstance(1, 4), CounterImpl.getInstance(3, 1), 3);
    sample.increment(CounterImpl.getInstance(2, 0), CounterImpl.COUNTER_0_0, 5);
    SourceFileCoverageImpl empty = new SourceFileCoverageImpl("Empty.java", "com/example");
    PackageCoverageImpl packageCoverage =
        new PackageCoverageImpl("com/example", List.of(), List.of(sample, empty));

    assertEquals(
        new JacocoLines.Builder()
            .line("com/example/Sample.java", 3, 4, 1, 3)
            .line("com/example/Sample.java", 5, 0, 0, 0)
            .sourceFile("com/example/Empty.java")
            .build(),
        CoverageReport.lines(new BundleCoverageImpl("bundle", List.of(packageCoverage))));
    assertEquals(ICounter.EMPTY, sample.getLine(4).getStatus(), "status of line 4");
  }

  /** JaCoCo names the default package with an empty string; a file in it is keyed by its name. */
  @Test
  void aSourceFileInTheDefaultPackageIsKeyedByItsNameAlone() {
    SourceFileCoverageImpl sample = new SourceFileCoverageImpl("A.java", "");
    sample.increment(CounterImpl.getInstance(0, 2), CounterImpl.COUNTER_0_0, 3);
    PackageCoverageImpl packageCoverage = new PackageCoverageImpl("", List.of(), List.of(sample));

    assertEquals(
        new JacocoLines.Builder().line("A.java", 3, 2, 0, 0).build(),
        CoverageReport.lines(new BundleCoverageImpl("bundle", List.of(packageCoverage))));
  }

  /** The header names the flags the user passed, which the runs hold, and every module measured. */
  @Test
  void theScopeNamesTheModulesAndTheFiltersTheUserPassed() {
    MavenProject app = new MavenProject();
    app.setArtifactId("app");
    MavenProject lib = new MavenProject();
    lib.setArtifactId("lib");
    File exec = new File("jacoco.exec");

    assertEquals(
        "the tests of app, lib -Dtest=FooTest",
        CoverageReport.scope(
            List.of(
                TestRun.measured(app, 0, "FooTest", null, exec),
                TestRun.measured(lib, 0, null, null, exec))));
  }

  @Test
  void theSurefireFilterSeparatesTheMethodFromItsClassWithAHash() {
    assertEquals(
        "-Dtest='p.ATest#one,p.ATest$Inner#two' -Dsurefire.failIfNoSpecifiedTests=false",
        CoverageReport.surefireTestFilter(List.of("p.ATest.one", "p.ATest$Inner.two")));
  }
}
