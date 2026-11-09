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

import java.util.Collections;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The changed lines of one source file of a module, under the key JaCoCo and PIT record the file
 * by.
 *
 * <p>The path is the file as git spells it, relative to the repository, and is what the reports
 * print. The key is the {@link SourceFileKey}, which is how a JaCoCo report and a PIT mutant name
 * the same file.
 */
public final class ChangedFile {

  private final String path;
  private final String key;
  private final NavigableSet<Integer> lines;

  public ChangedFile(String path, String key, NavigableSet<Integer> lines) {
    this.path = path;
    this.key = key;
    this.lines = Collections.unmodifiableNavigableSet(new TreeSet<>(lines));
  }

  /** The file, relative to the repository and spelled with {@code /}. */
  public String path() {
    return path;
  }

  /** The package path and file name a class compiled from the file records. */
  public String key() {
    return key;
  }

  /** The 1-based numbers of the changed lines. */
  public NavigableSet<Integer> lines() {
    return lines;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ChangedFile)) {
      return false;
    }
    ChangedFile that = (ChangedFile) other;
    return path.equals(that.path) && key.equals(that.key) && lines.equals(that.lines);
  }

  @Override
  public int hashCode() {
    return Objects.hash(path, key, lines);
  }

  @Override
  public String toString() {
    return path + " as " + key + " " + lines;
  }
}
