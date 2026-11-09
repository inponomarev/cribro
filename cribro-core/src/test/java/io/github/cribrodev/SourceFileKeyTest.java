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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key of a source file is read from the package it declares, as the class files compiled from
 * it record it, so that a Kotlin file in a directory that spells no part of its package is still
 * matched to its JaCoCo counters and its PIT mutants.
 */
class SourceFileKeyTest {

  @TempDir Path repository;

  @Nested
  class TheDeclaredPackage {

    @Test
    void aJavaDeclarationEndsAtTheSemicolon() {
      assertEquals("com.x.y", SourceFileKey.declaredPackage("package com.x.y;\n\nclass A {}\n"));
    }

    @Test
    void aKotlinDeclarationEndsAtTheLine() {
      assertEquals("demo.math", SourceFileKey.declaredPackage("package demo.math\n\nfun f() = 1\n"));
    }

    @Test
    void aFileWithNoDeclarationIsInTheDefaultPackage() {
      assertEquals("", SourceFileKey.declaredPackage("class A {}\n"));
    }

    @Test
    void anEmptyFileIsInTheDefaultPackage() {
      assertEquals("", SourceFileKey.declaredPackage(""));
    }

    @Test
    void commentsBeforeTheDeclarationAreSkipped() {
      assertEquals(
          "com.x",
          SourceFileKey.declaredPackage(
              "/*\n * Copyright: package not.this;\n */\n// package nor.this\npackage com.x;\n"));
    }

    @Test
    void kotlinFileAnnotationsBeforeTheDeclarationAreSkipped() {
      assertEquals(
          "demo",
          SourceFileKey.declaredPackage(
              "@file:JvmName(\"Util)\")\n@file:Suppress(\"a\", \"b\")\npackage demo\n"));
    }

    @Test
    void aKotlinAnnotationListBeforeTheDeclarationIsSkipped() {
      assertEquals(
          "demo",
          SourceFileKey.declaredPackage("@file:[JvmName(\"U\") Suppress(\"x\")]\npackage demo\n"));
    }

    /** The scan stops at the first declaration, so a string further down names no package. */
    @Test
    void aStringLiteralThatSpellsAPackageDoesNotCount() {
      assertEquals(
          "",
          SourceFileKey.declaredPackage(
              "class A {\n  String s = \"\"\"\n      package demo;\n      \"\"\";\n}\n"));
    }

    @Test
    void anAnnotationOnTheFirstDeclarationLeavesTheFileInTheDefaultPackage() {
      assertEquals("", SourceFileKey.declaredPackage("@Deprecated\nclass A {}\n"));
    }

    @Test
    void whitespaceAndCommentsInsideTheNameAreDropped() {
      assertEquals("com.x", SourceFileKey.declaredPackage("package com . /* here */ x ;\n"));
    }

    @Test
    void aByteOrderMarkIsSkipped() {
      assertEquals("com.x", SourceFileKey.declaredPackage("﻿package com.x;\n"));
    }

    @Test
    void aBacktickedKotlinSegmentIsTakenWithoutTheBackticks() {
      assertEquals("demo.my pkg", SourceFileKey.declaredPackage("package demo.`my pkg`\n"));
    }

    @Test
    void anUnterminatedCommentHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("/* package com.x;\n"));
    }

    @Test
    void aDeclarationWithNoNameHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("package ;\n"));
    }
  }

  @Nested
  class TheKey {

    private void write(String path, String text) throws IOException {
      Path file = repository.resolve(path);
      Files.createDirectories(file.getParent());
      Files.writeString(file, text);
    }

    @Test
    void theKeyIsTheDeclaredPackageAsAPathAndTheFileName() throws IOException {
      write("src/main/kotlin/Calc.kt", "package demo.math\n");
      assertEquals(
          "demo/math/Calc.kt", SourceFileKey.of(repository.toFile(), "src/main/kotlin/Calc.kt", "Calc.kt"));
    }

    @Test
    void aFileInTheDefaultPackageIsKeyedByItsName() throws IOException {
      write("src/main/java/com/x/A.java", "class A {}\n");
      assertEquals(
          "A.java", SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A.java", "com/x/A.java"));
    }

    @Test
    void aFileThatDoesNotExistIsKeyedByItsPathBelowTheRoot() {
      assertEquals(
          "com/x/A.java",
          SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A.java", "com/x/A.java"));
    }

    @Test
    void aFileWithNoReadableDeclarationIsKeyedByItsPathBelowTheRoot() throws IOException {
      write("src/main/java/com/x/A.java", "/* never closed\npackage com.x;\n");
      assertEquals(
          "com/x/A.java",
          SourceFileKey.of(repository.toFile(), "src/main/java/com/x/A.java", "com/x/A.java"));
    }

    /** A comment in another charset must not hide the declaration. */
    @Test
    void aFileWithBytesThatAreNotUtf8IsStillRead() throws IOException {
      Path file = repository.resolve("A.java");
      Files.write(file, new byte[] {'/', '/', ' ', (byte) 0xE9, '\n', 'p', 'a', 'c', 'k', 'a', 'g', 'e',
          ' ', 'c', 'o', 'm', '.', 'x', ';', '\n'});
      assertEquals("com/x/A.java", SourceFileKey.of(repository.toFile(), "A.java", "A.java"));
    }
  }

  @Test
  void javaAndKotlinSourcesAreMeasuredAndNothingElse() {
    assertEquals(true, SourceFileKey.isSource("a/B.java"));
    assertEquals(true, SourceFileKey.isSource("a/B.kt"));
    assertEquals(false, SourceFileKey.isSource("build.gradle.kts"));
    assertEquals(false, SourceFileKey.isSource("a/B.groovy"));
  }
}
