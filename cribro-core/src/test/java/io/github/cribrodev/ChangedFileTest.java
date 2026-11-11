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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Two changed files are equal only where their path, their key, and their lines all agree, which
 * is what lets the tests of the key compare whole files.
 */
class ChangedFileTest {

  private static ChangedFile file(String path, String key, Integer... lines) {
    return new ChangedFile(path, key, new TreeSet<>(java.util.Arrays.asList(lines)));
  }

  @Test
  void filesThatAgreeInEveryPartAreEqual() {
    assertAll(
        () -> assertEquals(file("src/A.kt", "p/A.kt", 3), file("src/A.kt", "p/A.kt", 3)),
        () ->
            assertEquals(
                file("src/A.kt", "p/A.kt", 3).hashCode(),
                file("src/A.kt", "p/A.kt", 3).hashCode(),
                "hashCode"));
  }

  @Test
  void filesThatDifferOnlyInKeyAreNotEqual() {
    assertNotEquals(file("src/A.kt", "p/A.kt", 3), file("src/A.kt", "A.kt", 3));
  }

  @Test
  void filesThatDifferOnlyInPathAreNotEqual() {
    assertNotEquals(file("src/A.kt", "p/A.kt", 3), file("gen/A.kt", "p/A.kt", 3));
  }

  @Test
  void filesThatDifferOnlyInLinesAreNotEqual() {
    assertNotEquals(file("src/A.kt", "p/A.kt", 3), file("src/A.kt", "p/A.kt", 4));
  }

  @Test
  void aFileIsNotEqualToAnotherType() {
    assertNotEquals(file("src/A.kt", "p/A.kt", 3), "src/A.kt");
  }
}
