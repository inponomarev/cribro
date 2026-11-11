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

import io.github.cribrodev.DiffCoverageReport.AffectedLine;
import io.github.cribrodev.DiffCoverageReport.Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.function.Function;

/**
 * How much of the unchanged code a change sends new inputs to the run executed, method by method.
 *
 * <p>The block is advice rather than work the change owes: it lists what the change made reachable
 * under new conditions and what of it the run left unexecuted. A run of every test says little
 * here, because tests that predate the change execute the same lines under the old conditions; a
 * run filtered to the change's own tests shows what those tests exercise, so the block names them.
 */
final class ExposedCodeReport {

  /** One exposed method and its counters. */
  static final class MethodCoverage {
    final ExposedCode.Region region;
    int executableLines;
    int executedLines;
    int coveredBranches;
    int totalBranches;
    final List<AffectedLine> uncovered = new ArrayList<>();

    MethodCoverage(ExposedCode.Region region) {
      this.region = region;
    }
  }

  final List<MethodCoverage> methods = new ArrayList<>();
  private final List<ExposedCode.TestMethod> changedTests;
  private final boolean noParser;

  private ExposedCodeReport(ExposedCode exposed) {
    this.changedTests = exposed.changedTests;
    this.noParser = exposed.noParser;
  }

  /**
   * Measures the exposed lines against the run's counters; a method with no line that emits
   * bytecode is left out.
   *
   * @param keys the {@link SourceFileKey} of each changed file by its path, the key the coverage
   *     block looks the same counters up by
   */
  static ExposedCodeReport measure(
      ExposedCode exposed, Map<String, String> keys, JacocoLines lines, SourceLines sources) {
    ExposedCodeReport report = new ExposedCodeReport(exposed);
    for (ExposedCode.Region region : exposed.regions) {
      String key = keys.get(region.path);
      if (key == null || !lines.covers(key)) {
        continue;
      }
      MethodCoverage method = new MethodCoverage(region);
      for (int number : region.lines) {
        JacocoLines.Counters counters = lines.get(key, number);
        if (counters == null) {
          continue;
        }
        method.executableLines++;
        if (counters.isExecuted()) {
          method.executedLines++;
        }
        method.coveredBranches += counters.coveredBranches;
        method.totalBranches += counters.totalBranches;
        if (!counters.isExecuted() || counters.hasUntakenBranch()) {
          method.uncovered.add(
              new AffectedLine(
                  number,
                  counters.isExecuted(),
                  counters.coveredBranches,
                  counters.totalBranches,
                  DiffCoverageReport.quote(sources.get(region.path, number))));
        }
      }
      if (method.executableLines > 0) {
        report.methods.add(method);
      }
    }
    return report;
  }

  /**
   * Returns the block the report prints after its total: an empty string where the change exposes
   * nothing that emits bytecode, or one line saying so where this JVM has no javac to look with.
   *
   * @param scope what the run covered, such as the test task and its filter
   * @param filtered whether the run was narrowed to some of the tests
   * @param testFilter formats the change's test methods as the arguments that rerun only them
   * @param limits how many methods to describe, and how many rows each
   */
  String render(
      String scope,
      boolean filtered,
      Function<List<ExposedCode.TestMethod>, String> testFilter,
      Limits limits) {
    if (methods.isEmpty()) {
      return noParser
          ? "Exposed code: this JVM has no javac to parse the sources with, so none was looked for."
          : "";
    }
    StringBuilder out =
        new StringBuilder("Unchanged code the change routes new inputs to")
            .append("\n  advice, not required: the change sends this code inputs it may not have")
            .append(" handled before;\n  test a gap below where those inputs can reach it");
    String currentPath = null;
    int described = 0;
    for (MethodCoverage method : methods) {
      if (described == limits.files) {
        int left = methods.size() - described;
        out.append("\n\n  ")
            .append(left)
            .append(left == 1 ? " more exposed method" : " more exposed methods")
            .append(" in the full report");
        break;
      }
      ExposedCode.Region region = method.region;
      if (!region.path.equals(currentPath)) {
        out.append("\n\n  ").append(region.path);
        currentPath = region.path;
      }
      out.append("\n    ").append(region.method);
      if (region.called) {
        out.append(", whole body, called from ");
      } else {
        out.append(" lines ")
            .append(region.lines.first())
            .append('-')
            .append(region.lines.last())
            .append(", reached through the conditions changed at ");
      }
      out.append(region.triggers.size() == 1 ? "line " : "lines ")
          .append(lineList(region.triggers));
      out.append("\n      ")
          .append(method.executedLines)
          .append('/')
          .append(method.executableLines)
          .append(" lines executed");
      if (method.totalBranches > 0) {
        out.append(", ")
            .append(method.coveredBranches)
            .append('/')
            .append(method.totalBranches)
            .append(" branches covered");
      }
      int shown = Math.min(limits.linesPerFile, method.uncovered.size());
      for (int i = 0; i < shown; i++) {
        out.append("\n      ").append(method.uncovered.get(i).describe());
      }
      if (shown < method.uncovered.size()) {
        int left = method.uncovered.size() - shown;
        out.append("\n      ")
            .append(left)
            .append(left == 1 ? " more line" : " more lines")
            .append(" in the full report");
      }
      described++;
    }
    if (filtered && filtersToChangedTests(scope)) {
      out.append("\n\n  This run is filtered to the change's own tests, so the gaps above are what")
          .append(" those tests\n  leave uncovered.");
      return out.toString();
    }
    out.append("\n\n  The counts include ")
        .append(filtered ? "every test of the filtered run" : "every test the run executed")
        .append(", and older tests may reach\n  this code under the old conditions.");
    if (changedTests.isEmpty()) {
      out.append(" No test method the change added or edited was found in this module\n  to narrow")
          .append(" the run to.");
    } else {
      out.append(" To list only what the change's own tests leave uncovered here,\n  rerun the")
          .append(" tests with this filter in place of any other:\n    ")
          .append(testFilter.apply(changedTests));
    }
    return out.toString();
  }

  /**
   * Returns whether the scope names every test method the change added or edited, as Gradle spells
   * one ({@code pkg.Class.method}) or as Surefire does ({@code pkg.Class#method}).
   */
  private boolean filtersToChangedTests(String scope) {
    return !changedTests.isEmpty()
        && changedTests.stream()
            .allMatch(
                test ->
                    scope.contains(test.toString())
                        || scope.contains(test.className + "#" + test.method));
  }

  /** Returns the line numbers as ranges, such as {@code 1355-1359, 1396}. */
  static String lineList(NavigableSet<Integer> lines) {
    StringBuilder out = new StringBuilder();
    Integer start = null;
    Integer previous = null;
    for (int line : lines) {
      if (previous != null && line == previous + 1) {
        previous = line;
        continue;
      }
      appendRange(out, start, previous);
      start = line;
      previous = line;
    }
    appendRange(out, start, previous);
    return out.toString();
  }

  private static void appendRange(StringBuilder out, Integer start, Integer end) {
    if (start == null) {
      return;
    }
    if (out.length() > 0) {
      out.append(", ");
    }
    out.append(start);
    if (!start.equals(end)) {
      out.append('-').append(end);
    }
  }
}
