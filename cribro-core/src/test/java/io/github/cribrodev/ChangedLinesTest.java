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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The changed files of a module are those under its source roots, each under the key a class
 * compiled from it records: the package the file declares, then its name.
 */
class ChangedLinesTest {

  private static final NavigableSet<Integer> LINES = new TreeSet<>(List.of(4));

  @TempDir Path repository;

  private List<ChangedFile> underSourceRoots(String path, String... sourceRoots) {
    return ChangedLines.underSourceRoots(repository.toFile(), Map.of(path, LINES), List.of(sourceRoots));
  }

  private void write(String path, String text) throws IOException {
    Path file = repository.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void aFileUnderASourceRootIsKeyedByThePackageItDeclaresAndItsName() throws IOException {
    write("m/src/main/java/com/x/A.java", "package com.x;\n\nclass A {}\n");
    assertEquals(
        List.of(new ChangedFile("m/src/main/java/com/x/A.java", "com/x/A.java", LINES)),
        underSourceRoots("m/src/main/java/com/x/A.java", "m/src/main/java"));
  }

  /** Kotlin lets a file declare a package its directory does not follow. */
  @Test
  void aKotlinFileIsKeyedByItsDeclaredPackageWhateverItsDirectory() throws IOException {
    write("m/src/main/kotlin/Calc.kt", "package demo.math\n\nfun max(a: Int, b: Int) = a\n");
    assertEquals(
        List.of(new ChangedFile("m/src/main/kotlin/Calc.kt", "demo/math/Calc.kt", LINES)),
        underSourceRoots("m/src/main/kotlin/Calc.kt", "m/src/main/kotlin"));
  }

  @Test
  void aFileThatDeclaresNoPackageIsKeyedByItsNameAlone() throws IOException {
    write("m/src/main/java/com/x/A.java", "class A {}\n");
    assertEquals(
        List.of(new ChangedFile("m/src/main/java/com/x/A.java", "A.java", LINES)),
        underSourceRoots("m/src/main/java/com/x/A.java", "m/src/main/java"));
  }

  /** A file the working tree no longer holds, or cannot be read, falls back to its directory. */
  @Test
  void aFileThatCannotBeReadIsKeyedByItsPathBelowTheRoot() {
    assertEquals(
        List.of(new ChangedFile("m/src/main/java/com/x/A.java", "com/x/A.java", LINES)),
        underSourceRoots("m/src/main/java/com/x/A.java", "m/src/main/java"));
  }

  @Test
  void aFileOutsideEverySourceRootIsLeftOut() {
    assertEquals(List.of(), underSourceRoots("m/src/test/java/com/x/ATest.java", "m/src/main/java"));
  }

  @Test
  void aRootIsMatchedOnlyAsAWholeDirectory() {
    assertEquals(List.of(), underSourceRoots("m/src/main/java2/com/x/A.java", "m/src/main/java"));
  }

  @Test
  void aFileUnderNestedRootsFallsBackToItsPathBelowTheInnermostRoot() {
    assertEquals(
        List.of(new ChangedFile("m/src/main/java/gen/com/x/A.java", "com/x/A.java", LINES)),
        underSourceRoots("m/src/main/java/gen/com/x/A.java", "m/src/main/java", "m/src/main/java/gen"));
  }

  @Test
  void aRootWrittenWithBackslashesMatchesTheSlashesOfADiff() {
    assertEquals(
        List.of(new ChangedFile("m/src/main/java/com/x/A.java", "com/x/A.java", LINES)),
        underSourceRoots("m/src/main/java/com/x/A.java", "m\\src\\main\\java"));
  }

  @Test
  void theFilesComeOrderedByPath() throws IOException {
    List<ChangedFile> files =
        ChangedLines.underSourceRoots(
            repository.toFile(),
            Map.of("src/b/B.java", LINES, "src/a/A.java", LINES),
            List.of("src"));
    assertEquals(List.of("src/a/A.java", "src/b/B.java"), files.stream().map(ChangedFile::path).toList());
  }

  @Test
  void theDiffAndTheUntrackedFilesAreLimitedToJavaAndKotlinSources() {
    List<String> command = ChangedLines.diffCommand("origin/main");
    assertEquals(
        List.of("--", "*.java", "*.kt"), command.subList(command.indexOf("--"), command.size()));
  }
}
