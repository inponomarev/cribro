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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;

import io.github.cribrodev.DiffCoverageReport.Limits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The exposed lines are measured against the run's counters and printed as advice after the diff
 * coverage total, ending with how to rerun the change's own tests.
 */
class ExposedCodeReportTest {

  private static final String ROOT = "lib/src/main/java";
  private static final String SAMPLE = ROOT + "/p/Sample.java";
  private static final String SAMPLE_TEST = "lib/src/test/java/p/SampleTest.java";

  /** A case label changed at line 7 exposes lines 8-11; {@code helper} is called from line 9. */
  private static final String SOURCE =
      """
      package p;
      class Sample {
        int run(int state, int c) {
          int out = 0;
          switch (state) {
            case 0:
            case 1:
              if (c > 0) {
                out = helper(c);
              }
              break;
            case 2:
              out = -c;
              break;
          }
          return out;
        }
        int helper(int c) {
          int twice = c * 2;
          return twice;
        }
      }
      """;

  private static final String TEST =
      """
      package p;
      class SampleTest {
        @org.junit.jupiter.api.Test
        void edited() {
          new Sample().run(1, 1);
        }
      }
      """;

  @TempDir Path directory;

  /** Returns a JaCoCo {@code <line>} element; above zero covered instructions it is executed. */
  private static String line(
      int number, int coveredInstructions, int coveredBranches, int missedBranches) {
    return String.format(
        "<line nr=\"%d\" mi=\"%d\" ci=\"%d\" mb=\"%d\" cb=\"%d\"/>",
        number, coveredInstructions > 0 ? 0 : 4, coveredInstructions, missedBranches,
        coveredBranches);
  }

  /**
   * Renders the block for the change of line 7 of Sample.java and line 5 of SampleTest.java,
   * against a run that took one branch of line 8, never reached line 9, and ran the helper whole.
   */
  private String render(String scope, boolean filtered, Limits limits) throws IOException {
    write(SAMPLE, SOURCE);
    write(SAMPLE_TEST, TEST);
    Map<String, NavigableSet<Integer>> changed =
        Map.of(SAMPLE, new TreeSet<>(List.of(7)), SAMPLE_TEST, new TreeSet<>(List.of(5)));
    Path xml = directory.resolve("jacoco.xml");
    Files.writeString(
        xml,
        "<report name=\"test\"><package name=\"p\"><sourcefile name=\"Sample.java\">"
            + line(8, 4, 1, 1)
            + line(9, 0, 0, 0)
            + line(11, 1, 0, 0)
            + line(19, 4, 0, 0)
            + line(20, 2, 0, 0)
            + "</sourcefile></package></report>");
    ExposedCode exposed = ExposedCode.find(directory.toFile(), changed, List.of(ROOT));
    return ExposedCodeReport.measure(
            exposed,
            Map.of(SAMPLE, "p/Sample.java"),
            JacocoLines.read(List.of(xml.toFile())),
            SourceLines.under(directory.toFile()))
        .render(
            scope,
            filtered,
            tests -> "--tests " + tests.stream().map(Object::toString).toList(),
            limits);
  }

  private void write(String path, String text) throws IOException {
    Path file = directory.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void anUnfilteredRunListsWhatItLeftUncoveredAndHowToRunTheChangesTests() throws IOException {
    assertLinesMatch(
        List.of(
            "Unchanged code the change routes new inputs to",
            "  advice, not required: the change sends this code inputs it may not have"
                + " handled before;",
            "  test a gap below where those inputs can reach it",
            "",
            "  lib/src/main/java/p/Sample.java",
            "    run lines 8-11, reached through the conditions changed at line 7",
            "      2/3 lines executed, 1/2 branches covered",
            "      8: 1 of 2 branches covered: if (c > 0) {",
            "      9: not executed: out = helper(c);",
            "    helper, whole body, called from line 9",
            "      2/2 lines executed",
            "",
            "  The counts include every test the run executed, and older tests may reach",
            "  this code under the old conditions. To list only what the change's own tests leave"
                + " uncovered here,",
            "  rerun the tests with this filter in place of any other:",
            "    --tests [p.SampleTest.edited]"),
        render(":lib:test", false, Limits.NONE).lines().toList());
  }

  @Test
  void aRunFilteredToOtherTestsStillOffersTheChangesOwnTests() throws IOException {
    assertLinesMatch(
        List.of(
            ">> methods >>",
            "  The counts include every test of the filtered run, and older tests may reach",
            ">> wording >>",
            "    --tests [p.SampleTest.edited]"),
        render(":lib:test --tests p.SampleTest", true, Limits.NONE).lines().toList());
  }

  /** Gradle prints a filter as {@code pkg.Class.method}; Surefire, as {@code pkg.Class#method}. */
  @ParameterizedTest
  @ValueSource(strings = {":lib:test --tests p.SampleTest.edited", "-Dtest=p.SampleTest#edited"})
  void aRunFilteredToTheChangesOwnTestsSaysSoAndOffersNoFilter(String scope)
      throws IOException {
    assertLinesMatch(
        List.of(
            ">> methods >>",
            "  This run is filtered to the change's own tests, so the gaps above are what those"
                + " tests",
            "  leave uncovered."),
        render(scope, true, Limits.NONE).lines().toList());
  }

  @Test
  void theLimitsCutTheMethodsAndTheRowsEachOneShows() throws IOException {
    assertLinesMatch(
        List.of(
            ">> header >>",
            "    run lines 8-11, reached through the conditions changed at line 7",
            "      2/3 lines executed, 1/2 branches covered",
            "      8: 1 of 2 branches covered: if (c > 0) {",
            "      1 more line in the full report",
            "",
            "  1 more exposed method in the full report",
            ">> tail >>"),
        render(":lib:test", false, new Limits(1, 1, 50)).lines().toList());
  }

  @Test
  void consecutiveLinesPrintAsARange() {
    assertEquals(
        "4, 7-9, 12-13",
        ExposedCodeReport.lineList(new TreeSet<>(List.of(4, 7, 8, 9, 12, 13))));
  }
}
