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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pitest.util.Glob;

/**
 * A changed-lines file holds one source file per line, its changed lines written as comma-separated
 * ranges after a tab, and reads back to the line numbers it was written from.
 */
class ChangedLinesFileTest {

  @TempDir Path directory;

  private static NavigableSet<Integer> lines(Integer... numbers) {
    return new TreeSet<>(Arrays.asList(numbers));
  }

  @Test
  void consecutiveLinesCollapseIntoOneRange() {
    assertEquals("3-5", ChangedLinesFile.ranges(lines(3, 4, 5)));
  }

  @Test
  void twoConsecutiveLinesAreARange() {
    assertEquals("3-4", ChangedLinesFile.ranges(lines(3, 4)));
  }

  @Test
  void aGapStartsANewRange() {
    assertEquals("3,5-6,9", ChangedLinesFile.ranges(lines(3, 5, 6, 9)));
  }

  /** Returns a changed file under {@code src/}, keyed by its path below that root. */
  private static ChangedFile changed(String key, NavigableSet<Integer> lines) {
    return new ChangedFile("src/" + key, key, lines);
  }

  @Test
  void aFileReadsBackToTheLinesItWasWrittenFrom() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file,
        List.of("against origin/master (abc)"),
        List.of(changed("a/B.java", lines(1, 2, 3, 7)), changed("C.java", lines(12))));

    assertEquals(
        Map.of("a/B.java", lines(1, 2, 3, 7), "C.java", lines(12)), ChangedLinesFile.read(file));
  }

  @Test
  void aFileReadsBackToThePathOfEachEntry() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file,
        List.of(),
        List.of(new ChangedFile("m/src/main/kotlin/Calc.kt", "demo/math/Calc.kt", lines(3))));

    assertEquals(
        Map.of("demo/math/Calc.kt", "m/src/main/kotlin/Calc.kt"), ChangedLinesFile.readPaths(file));
  }

  @Test
  void aFileWithNoChangedLineIsLeftOut() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file, List.of(), List.of(changed("a/Empty.java", lines()), changed("a/B.java", lines(4))));

    assertEquals(Map.of("a/B.java", lines(4)), ChangedLinesFile.read(file));
  }

  /**
   * Two source roots can each hold a file of one name in one package. The entry names the path
   * that sorts first, whichever of the two files comes first.
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void twoFilesUnderOneKeyAreWrittenAsOneEntryWithTheLinesOfBoth(boolean generatedFirst)
      throws IOException {
    Path file = directory.resolve("changed-lines.txt");
    ChangedFile source = new ChangedFile("src/a/B.java", "a/B.java", lines(2));
    ChangedFile generated = new ChangedFile("gen/a/B.java", "a/B.java", lines(7));

    ChangedLinesFile.write(
        file, List.of(), generatedFirst ? List.of(generated, source) : List.of(source, generated));

    assertAll(
        () -> assertEquals(Map.of("a/B.java", lines(2, 7)), ChangedLinesFile.read(file), "lines"),
        () ->
            assertEquals(
                Map.of("a/B.java", "gen/a/B.java"), ChangedLinesFile.readPaths(file), "paths"));
  }

  /** The entries come ordered by key, so the file reads the same whatever order the files came in. */
  @Test
  void theEntriesAreOrderedByKey() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file, List.of(), List.of(changed("b/B.java", lines(1)), changed("a/A.java", lines(1))));

    assertEquals(
        List.of("a/A.java", "b/B.java"), List.copyOf(ChangedLinesFile.read(file).keySet()));
  }

  @Test
  void theHeaderReadsBackToTheFirstCommentLine() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file,
        List.of("against origin/master (abc)", "second"),
        List.of(changed("B.java", lines(1))));

    assertEquals("against origin/master (abc)", ChangedLinesFile.readHeader(file));
  }

  /** Returns the classes among the names that PIT selects with the patterns for the changed lines. */
  private static List<String> targeted(Map<String, NavigableSet<Integer>> changed, String... names) {
    List<String> patterns = ChangedLinesFile.targetClasses(changed.keySet());
    return Arrays.stream(names)
        .filter(name -> patterns.stream().anyMatch(pattern -> new Glob(pattern).matches(name)))
        .toList();
  }

  /**
   * A second top-level class declared in a changed file, such as {@code Helper} in {@code A.java},
   * has a name of its own, so the whole package is targeted.
   */
  @Test
  void everyClassOfTheChangedFilesPackageIsTargetedAndNoOther() {
    assertEquals(
        List.of("com.x.A", "com.x.A$Inner", "com.x.Helper"),
        targeted(
            Map.of("com/x/A.java", lines(3)),
            "com.x.A",
            "com.x.A$Inner",
            "com.x.Helper",
            "com.x.sub.C",
            "com.y.B",
            "com.xy.D"));
  }

  @Test
  void aChangedFileInTheDefaultPackageTargetsOnlyThatPackage() {
    assertEquals(List.of("A", "Helper"), targeted(Map.of("A.java", lines(1)), "A", "Helper", "com.x.B"));
  }

  /** A Kotlin file compiles to the classes it declares and to a facade class named after it. */
  @Test
  void aChangedKotlinFileTargetsEveryClassOfItsPackage() {
    assertEquals(
        List.of("demo.math.Calc", "demo.math.CalcKt"),
        targeted(Map.of("demo/math/Calc.kt", lines(3)), "demo.math.Calc", "demo.math.CalcKt", "demo.Other"));
  }

  @Test
  void packageAndModuleInfoAreNotTargeted() {
    assertEquals(
        List.of(),
        ChangedLinesFile.targetClasses(List.of("com/x/package-info.java", "module-info.java")));
  }

  @Test
  void aFileOfAnotherLanguageIsNotTargeted() {
    assertEquals(List.of(), ChangedLinesFile.targetClasses(List.of("com/x/A.groovy")));
  }

  @Test
  void commentsAndBlankLinesAreSkipped() throws IOException {
    Path file = directory.resolve("changed-lines.txt");
    Files.writeString(file, "# against origin/master\n\na/B.java\tsrc/a/B.java\t3-5,9\n");

    assertEquals(Map.of("a/B.java", lines(3, 4, 5, 9)), ChangedLinesFile.read(file));
  }

  /** The file is written and read by one cribro, so an entry short of a field is corruption. */
  @Test
  void anEntryWithoutAPathIsRefused() throws IOException {
    Path file = directory.resolve("changed-lines.txt");
    Files.writeString(file, "a/B.java\t3\n");

    IOException refused = assertThrows(IOException.class, () -> ChangedLinesFile.read(file));
    assertEquals(true, refused.getMessage().endsWith("line: a/B.java\t3"), refused.getMessage());
  }
}
