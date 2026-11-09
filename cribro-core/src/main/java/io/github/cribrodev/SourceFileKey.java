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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The key JaCoCo and PIT record a source file by: the package of its classes as a path, then the
 * file name, as in {@code com/example/Sample.kt}.
 *
 * <p>A class file records its package and, in its {@code SourceFile} attribute, the name of the
 * file it was compiled from, but not the directory the file lay in. So a tool that sees only
 * bytecode knows a file by this key alone. For Java the directory below the source root spells the
 * package by convention; Kotlin lets a file declare any package wherever it lies, and Kotlin
 * projects often drop the leading directories. The key is therefore read from the {@code package}
 * declaration in the file, and the directory stands in only where the file cannot be read.
 */
public final class SourceFileKey {

  /** The extensions of the source files cribro measures. */
  static final List<String> EXTENSIONS = List.of(".java", ".kt");

  private SourceFileKey() {}

  /** Returns the git pathspecs that select the source files cribro measures. */
  static List<String> pathspecs() {
    return EXTENSIONS.stream().map(extension -> "*" + extension).collect(Collectors.toList());
  }

  /** Returns whether the path names a source file cribro measures, by its extension. */
  static boolean isSource(String path) {
    return EXTENSIONS.stream().anyMatch(path::endsWith);
  }

  /**
   * Returns the key of a source file of the working tree.
   *
   * @param repository the repository root
   * @param path the file, relative to the repository and spelled with {@code /} as git prints it
   * @param relativeToRoot the path below the source root that holds the file, which is the key
   *     where the file cannot be read
   */
  static String of(File repository, String path, String relativeToRoot) {
    String declared;
    try {
      // Decoded leniently: a comment in another charset must not hide the declaration.
      declared =
          declaredPackage(
              new String(
                  Files.readAllBytes(repository.toPath().resolve(path)), StandardCharsets.UTF_8));
    } catch (IOException | InvalidPathException e) {
      return relativeToRoot;
    }
    if (declared == null) {
      return relativeToRoot;
    }
    String name = path.substring(path.lastIndexOf('/') + 1);
    return declared.isEmpty() ? name : declared.replace('.', '/') + "/" + name;
  }

  /**
   * Returns the package a Java or Kotlin source declares, an empty string where it declares none,
   * or null where the text has no readable declaration, as with an unterminated comment.
   *
   * <p>Only comments and, for Kotlin, file annotations may precede the declaration, so the text is
   * scanned rather than searched: a string literal or a comment further down that spells {@code
   * package} names no package.
   */
  static String declaredPackage(String text) {
    int at = text.startsWith("﻿") ? 1 : 0;
    at = skipPreamble(text, at);
    if (at < 0) {
      return null;
    }
    if (!text.startsWith("package", at)
        || at + "package".length() >= text.length()
        || !Character.isWhitespace(text.charAt(at + "package".length()))) {
      return "";
    }
    at += "package".length();
    StringBuilder name = new StringBuilder();
    while (true) {
      at = skipPreamble(text, at);
      if (at < 0 || at >= text.length()) {
        return null;
      }
      int start = at;
      if (text.charAt(at) == '`') {
        int end = text.indexOf('`', at + 1);
        if (end < 0) {
          return null;
        }
        name.append(text, at + 1, end);
        at = end + 1;
      } else {
        if (!Character.isJavaIdentifierStart(text.charAt(at))) {
          return null;
        }
        while (at < text.length() && Character.isJavaIdentifierPart(text.charAt(at))) {
          at++;
        }
        name.append(text, start, at);
      }
      at = skipPreamble(text, at);
      if (at >= 0 && at < text.length() && text.charAt(at) == '.') {
        name.append('.');
        at++;
        continue;
      }
      return name.toString();
    }
  }

  /**
   * Returns the index of the first character after the whitespace, comments, and annotations at
   * {@code at}, or -1 where one of them never ends.
   */
  private static int skipPreamble(String text, int at) {
    while (at >= 0 && at < text.length()) {
      char c = text.charAt(at);
      if (Character.isWhitespace(c)) {
        at++;
      } else if (text.startsWith("//", at)) {
        int end = text.indexOf('\n', at);
        at = end < 0 ? text.length() : end + 1;
      } else if (text.startsWith("/*", at)) {
        int end = text.indexOf("*/", at + 2);
        at = end < 0 ? -1 : end + 2;
      } else if (c == '@') {
        at = skipAnnotation(text, at + 1);
      } else {
        break;
      }
    }
    return at;
  }

  /**
   * Returns the index after the annotation whose name starts at {@code at}: a Kotlin use-site
   * target such as {@code file:}, a qualified name, and the arguments in parentheses or, for a
   * Kotlin annotation list, in brackets.
   */
  private static int skipAnnotation(String text, int at) {
    while (true) {
      while (at < text.length()
          && (Character.isJavaIdentifierPart(text.charAt(at)) || text.charAt(at) == '.')) {
        at++;
      }
      int next = at;
      while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
        next++;
      }
      if (next >= text.length()) {
        return at;
      }
      char c = text.charAt(next);
      if (c == ':') {
        at = next + 1;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
          at++;
        }
        continue;
      }
      if (c == '(') {
        return skipBalanced(text, next, '(', ')');
      }
      if (c == '[') {
        return skipBalanced(text, next, '[', ']');
      }
      return at;
    }
  }

  /**
   * Returns the index after the bracket that closes the one at {@code at}, or -1 where none does.
   * A string or character literal between them is skipped, so a bracket in it does not count.
   */
  private static int skipBalanced(String text, int at, char open, char close) {
    int depth = 0;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == open) {
        depth++;
      } else if (c == close) {
        depth--;
        if (depth == 0) {
          return at + 1;
        }
      } else if (c == '"' || c == '\'') {
        at = skipLiteral(text, at);
        if (at < 0) {
          return -1;
        }
        continue;
      }
      at++;
    }
    return -1;
  }

  /** Returns the index after the literal that opens at {@code at}, or -1 where it never closes. */
  private static int skipLiteral(String text, int at) {
    char quote = text.charAt(at);
    if (quote == '"' && text.startsWith("\"\"\"", at)) {
      int end = text.indexOf("\"\"\"", at + 3);
      return end < 0 ? -1 : end + 3;
    }
    at++;
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c == '\\') {
        at += 2;
        continue;
      }
      if (c == quote) {
        return at + 1;
      }
      at++;
    }
    return -1;
  }
}
