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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The lines a branch changed, and the base ref they were measured against.
 *
 * <p>Diff coverage and diff mutation testing answer different questions about the same set of
 * lines, so both read it from here: a change to how the base is chosen moves both reports at once,
 * and {@code cribro.diffBase} means the same thing to each of them.
 */
public final class ChangedLines {

  /** The Gradle and Maven property that names the base ref for every diff-based report. */
  public static final String BASE_PROPERTY = "cribro.diffBase";

  /**
   * Returns what to do when no base ref resolves.
   *
   * @param flag how the build tool sets a property on its command line: {@code -P} for Gradle,
   *     {@code -D} for Maven
   */
  public static String noBaseHint(String flag) {
    return "Pass "
        + flag
        + BASE_PROPERTY
        + "=<ref>, or set an upstream branch, or fetch origin/HEAD, origin/main, or origin/master.";
  }

  private static final String[] UPSTREAM_NAME = {"rev-parse", "--abbrev-ref", "@{upstream}"};

  private static final String[] BRANCH_NAME = {"rev-parse", "--abbrev-ref", "HEAD"};

  private final String ref;
  private final String commit;
  private final Map<String, NavigableSet<Integer>> lines;
  private final List<String> unreadable;

  private ChangedLines(
      String ref,
      String commit,
      Map<String, NavigableSet<Integer>> lines,
      List<String> unreadable) {
    this.ref = ref;
    this.commit = commit;
    this.lines = lines;
    this.unreadable = unreadable;
  }

  /** Returns the ref the diff was taken against, as the caller or git named it. */
  public String ref() {
    return ref;
  }

  /** Returns the merge base of {@link #ref()} and HEAD. */
  public String commit() {
    return commit;
  }

  /** Returns the changed lines of every Java and Kotlin file, keyed by repository-relative path. */
  public Map<String, NavigableSet<Integer>> lines() {
    return lines;
  }

  /** Returns a line naming each untracked file this JVM could not read. */
  public List<String> unreadable() {
    return unreadable;
  }

  /**
   * Returns the lines changed since the merge base of HEAD and the base ref, or null where no
   * base resolves.
   *
   * <p>Taking the merge base rather than the ref itself keeps commits that landed on the ref since
   * the branch point out of the diff, so the result covers this branch's own work. With no
   * explicit ref, the upstream branch is tried, then the default branches of {@code origin}.
   *
   * @param explicit the ref the caller named, or null
   * @throws CribroException where the explicit ref does not resolve, because diffing against
   *     something else would report the wrong lines as changed
   */
  public static ChangedLines measure(Git git, String explicit) {
    if (explicit != null && !resolves(git, explicit)) {
      throw new CribroException(BASE_PROPERTY + " " + explicit + " does not resolve to a commit");
    }
    String upstream = git.outputOrNull(UPSTREAM_NAME);
    String branch = git.outputOrNull(BRANCH_NAME);
    for (String candidate : baseCandidates(explicit, upstream, branch)) {
      if (!resolves(git, candidate)) {
        continue;
      }
      // A ref can resolve and still share no commit with HEAD, as in a shallow clone or after an
      // orphan branch, and merge-base then fails. That is a base this run does not have, not a
      // reason to fail a build whose tests passed.
      String mergeBase = git.outputOrNull("merge-base", candidate, "HEAD");
      if (mergeBase != null) {
        List<String> unreadable = new ArrayList<>();
        return new ChangedLines(
            candidate, mergeBase, changedLines(git, mergeBase, unreadable), unreadable);
      }
    }
    return null;
  }

  /** Returns the merge base abbreviated the way the reports print it. */
  public String shortCommit() {
    return commit.substring(0, Math.min(9, commit.length()));
  }

  /**
   * Returns the refs to try as the base, in order.
   *
   * <p>An explicit ref stands alone: falling through from the ref the caller named to another one
   * would report a different set of lines as changed, under a base the caller did not choose.
   *
   * <p>An upstream that is this branch's own pushed copy is left out. In a fork workflow the branch
   * tracks the fork, so after the first push its upstream is the branch itself, every line it
   * changed is already in the base, and the report says nothing was changed rather than that it
   * could not find a base.
   *
   * @param explicit the ref the caller named, or null
   * @param upstreamRef the upstream branch as git names it, such as {@code origin/develop}, or null
   * @param currentBranch the checked-out branch, or null on a detached HEAD
   */
  static List<String> baseCandidates(String explicit, String upstreamRef, String currentBranch) {
    if (explicit != null) {
      return List.of(explicit);
    }
    if (upstreamRef == null || isOwnPushedBranch(upstreamRef, currentBranch)) {
      return DEFAULT_BRANCHES;
    }
    List<String> candidates = new ArrayList<>();
    candidates.add(upstreamRef);
    candidates.addAll(DEFAULT_BRANCHES);
    return candidates;
  }

  /**
   * The bases tried when neither the caller nor the upstream names one: the branch {@code origin}
   * calls its default, then the two names a default branch usually has.
   */
  static final List<String> DEFAULT_BRANCHES = List.of("origin/HEAD", "origin/main", "origin/master");

  /**
   * Returns whether the upstream ref is the current branch pushed to a remote.
   *
   * <p>The comparison drops the remote name, which is the first segment, so that a branch of its
   * own named after the tail of another one is not mistaken for it.
   */
  static boolean isOwnPushedBranch(String upstreamRef, String currentBranch) {
    if (currentBranch == null) {
      return false;
    }
    int remoteEnd = upstreamRef.indexOf('/');
    String branch = remoteEnd < 0 ? upstreamRef : upstreamRef.substring(remoteEnd + 1);
    return branch.equals(currentBranch);
  }

  /**
   * Returns the git arguments the diff is taken with.
   *
   * <p>{@code --unified=0} is what makes a hunk header name only changed lines, and the prefixes
   * are pinned because {@code diff.noprefix} and {@code diff.mnemonicPrefix} are personal settings
   * that would otherwise change the {@code a/} and {@code b/} that {@link UnifiedDiff} strips.
   *
   * <p>{@code core.quotePath} is off because it wraps a path holding a non-ASCII byte in quotes
   * and escapes the byte, as in {@code "b/src/caf\303\251.java"}. Such a {@code +++} line starts
   * with a quotation mark rather than with the prefix, so it is no file header, and every hunk of
   * that file leaves the report with nothing to show that it happened.
   */
  static List<String> diffCommand(String base) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "-c",
                "core.quotePath=false",
                "diff",
                "--unified=0",
                "--no-color",
                "--src-prefix=a/",
                "--dst-prefix=b/",
                base,
                "--"));
    command.addAll(SourceFileKey.pathspecs());
    return command;
  }

  /** Returns whether the ref names a commit of this repository. */
  private static boolean resolves(Git git, String ref) {
    return git.outputOrNull("rev-parse", "--verify", "--quiet", ref + "^{commit}") != null;
  }

  /**
   * Returns the changed lines of every Java and Kotlin file, keyed by repository-relative path.
   *
   * <p>Two sources are merged: the post-image lines of the diff between base and the working tree,
   * which covers committed, staged and unstaged changes alike, and every line of an untracked
   * file, which no diff mentions at all.
   *
   * @param unreadable collects a line naming each untracked file this JVM cannot decode
   */
  private static Map<String, NavigableSet<Integer>> changedLines(
      Git git, String base, List<String> unreadable) {
    String diff = git.output(diffCommand(base).toArray(new String[0]));
    Map<String, NavigableSet<Integer>> changed = UnifiedDiff.parse(diff);
    // core.quotePath is off here for the reason diffCommand gives: a quoted path names no file
    // this JVM can open, and the file would be reported as unreadable rather than measured.
    List<String> lsFiles =
        new ArrayList<>(
            List.of("-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard", "--"));
    lsFiles.addAll(SourceFileKey.pathspecs());
    String untracked = git.output(lsFiles.toArray(new String[0]));
    if (untracked.isEmpty()) {
      return changed;
    }
    for (String path : untracked.split("\n")) {
      if (path.isEmpty()) {
        continue;
      }
      // An untracked file has no diff, so every line in it counts as changed.
      NavigableSet<Integer> lines = new TreeSet<>();
      try {
        int count = Files.readAllLines(git.repositoryRoot().toPath().resolve(path)).size();
        for (int number = 1; number <= count; number++) {
          lines.add(number);
        }
      } catch (IOException e) {
        // A broken symlink or a file this JVM cannot decode is not worth failing a build whose
        // tests passed, so it is named and left out of the counts.
        unreadable.add("cannot read the untracked file " + path + ": " + e);
        continue;
      }
      changed.put(path, lines);
    }
    return changed;
  }

  /**
   * Returns the files whose path lies under one of the source roots, ordered by path, each under
   * the {@link SourceFileKey} read from the working tree, which is how a JaCoCo report and a PIT
   * mutant name the file.
   *
   * <p>Two files may share a key where two source roots each hold a file of one name in one
   * package; the list keeps both, and {@link ChangedLinesFile#write} and {@link
   * DiffCoverageReport#measure} say how each treats them.
   *
   * @param repository the repository root, where the files are read from
   * @param changed the changed lines, keyed by repository-relative path
   * @param sourceRoots repository-relative source directories of the module
   */
  public static List<ChangedFile> underSourceRoots(
      File repository, Map<String, NavigableSet<Integer>> changed, List<String> sourceRoots) {
    List<ChangedFile> files = new ArrayList<>();
    for (Map.Entry<String, NavigableSet<Integer>> entry : new TreeMap<>(changed).entrySet()) {
      String path = entry.getKey();
      String relative = underSourceRoot(path, sourceRoots);
      if (relative != null) {
        files.add(
            new ChangedFile(path, SourceFileKey.of(repository, path, relative), entry.getValue()));
      }
    }
    return files;
  }

  /**
   * Returns the path with its source root stripped, or null where no root contains it.
   *
   * <p>The longest matching root wins, since a root nested inside another would otherwise leave the
   * outer root's extra directories in the key and match no package JaCoCo reports.
   *
   * <p>A root's separators are normalised first. The paths come from a git diff and always use
   * {@code /}, while a root built from a {@code java.nio.file.Path} uses the platform separator, so
   * on Windows no root would match and every changed file would leave the report.
   */
  static String underSourceRoot(String path, List<String> sourceRoots) {
    String longest = null;
    for (String sourceRoot : sourceRoots) {
      String root = sourceRoot.replace('\\', '/');
      String prefix = root.endsWith("/") ? root : root + "/";
      if (path.startsWith(prefix) && (longest == null || prefix.length() > longest.length())) {
        longest = prefix;
      }
    }
    return longest == null ? null : path.substring(longest.length());
  }
}
