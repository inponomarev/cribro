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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The key of a source file is read from the package it declares, as the class files compiled from
 * it record it, so that a Kotlin file in a directory that does not follow its package is still
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

    /** A Kotlin file often ends right after its declaration, with no newline. */
    @Test
    void aDeclarationThatEndsTheTextIsRead() {
      assertEquals("demo", SourceFileKey.declaredPackage("package demo"));
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
    void aFileThatIsOneLineCommentWithoutANewlineIsInTheDefaultPackage() {
      assertEquals("", SourceFileKey.declaredPackage("// note"));
    }

    @Test
    void commentsBeforeTheDeclarationAreSkipped() {
      assertEquals(
          "com.x",
          SourceFileKey.declaredPackage(
              "/*\n * Copyright: package not.this;\n */\n// package nor.this\npackage com.x;\n"));
    }

    /** Kotlin block comments nest, so a comment that holds another ends at the matching close. */
    @Test
    void aNestedKotlinCommentEndsAtItsMatchingClose() {
      assertEquals(
          "demo.math",
          SourceFileKey.declaredPackage(
              "/*\n * Example: /* nested */\n */\npackage demo.math\n\nfun f() = 1\n", true));
    }

    /** A Java block comment ends at the first close, whatever opened inside it. */
    @Test
    void aJavaCommentEndsAtTheFirstClose() {
      assertEquals(
          "x", SourceFileKey.declaredPackage("/* a /* b */ package x; /* c */\n", false));
    }

    @Test
    void anUnclosedNestedKotlinCommentHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("/* a /* b */ package x;\n", true));
    }

    @Test
    void kotlinFileAnnotationsBeforeTheDeclarationAreSkipped() {
      assertEquals(
          "demo",
          SourceFileKey.declaredPackage(
              "@file:JvmName(\"Util)\")\n@file:Suppress(\"a\", \"b\")\npackage demo\n"));
    }

    @Test
    void aQualifiedAnnotationNameIsSkippedWhole() {
      assertEquals(
          "demo", SourceFileKey.declaredPackage("@file:kotlin.jvm.JvmName(\"X\")\npackage demo\n"));
    }

    @Test
    void spaceAfterTheUseSiteTargetAndBeforeTheArgumentsIsSkipped() {
      assertEquals("demo", SourceFileKey.declaredPackage("@file: JvmName (\"X\")\npackage demo\n"));
    }

    @Test
    void anEscapedQuoteInAnArgumentDoesNotEndTheLiteral() {
      assertEquals(
          "demo", SourceFileKey.declaredPackage("@file:JvmName(\"a\\\")b\")\npackage demo\n"));
    }

    @Test
    void aRawStringArgumentIsSkippedWhole() {
      assertEquals(
          "demo", SourceFileKey.declaredPackage("@file:JvmName(\"\"\"a)b\"\"\")\npackage demo\n"));
    }

    @Test
    void aCharacterLiteralArgumentHoldingABracketDoesNotCount() {
      assertEquals("demo", SourceFileKey.declaredPackage("@file:Suppress(')')\npackage demo\n"));
    }

    @Test
    void anUnterminatedArgumentListHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("@file:JvmName(\"X\"\npackage demo\n"));
    }

    @Test
    void anUnterminatedArgumentLiteralHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("@file:JvmName(\"X)\npackage demo\n"));
    }

    @Test
    void aKotlinAnnotationListBeforeTheDeclarationIsSkipped() {
      assertEquals(
          "demo",
          SourceFileKey.declaredPackage("@file:[JvmName(\"U\") Suppress(\"x\")]\npackage demo\n"));
    }

    /** Java allows an annotation on the package declaration of {@code package-info.java}. */
    @Test
    void aJavaPackageAnnotationIsSkipped() {
      assertEquals("com.x", SourceFileKey.declaredPackage("@Deprecated\npackage com.x;\n"));
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

    @Test
    void aKeywordThatEndsTheTextHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("package"));
    }

    @Test
    void aNameThatEndsInADotHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("package demo."));
    }

    @Test
    void anUnterminatedBacktickedSegmentHasNoReadableDeclaration() {
      assertNull(SourceFileKey.declaredPackage("package demo.`x"));
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

    /** A Kotlin file's comments nest, and its key is read past one that holds another. */
    @Test
    void aKotlinFileIsReadWithNestedComments() throws IOException {
      write("src/main/kotlin/Calc.kt", "/* a /* b */ c */\npackage demo.math\n");
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

    @Test
    void aPackagePathAndANameJoinWithASlash() {
      assertEquals("com/x/A.java", SourceFileKey.of("com/x", "A.java"));
    }

    /** JaCoCo names the default package with an empty string; a class file's package is null. */
    @ParameterizedTest
    @ValueSource(strings = {""})
    void theDefaultPackageKeysAFileByItsNameAlone(String packagePath) {
      assertEquals("A.java", SourceFileKey.of(packagePath, "A.java"));
    }

    @Test
    void aNullPackageKeysAFileByItsNameAlone() {
      assertEquals("A.java", SourceFileKey.of(null, "A.java"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"a/B.java", "a/B.kt"})
  void javaAndKotlinSourcesAreMeasured(String path) {
    assertTrue(SourceFileKey.isSource(path), path);
  }

  @ParameterizedTest
  @ValueSource(strings = {"build.gradle.kts", "a/B.groovy", "a/B.java.txt"})
  void otherFilesAreNotMeasured(String path) {
    assertFalse(SourceFileKey.isSource(path), path);
  }
}
