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

import java.io.IOException;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Writes the lines this branch changed in one module's main sources to a {@link ChangedLinesFile}.
 *
 * <p>The file holds only files under the module's source roots, each under the key a class
 * compiled from it records, so it is empty for a module the change did not reach. Untracked Java
 * and Kotlin files count as changed in full.
 */
@UntrackedTask(
    because = "the working tree and the branch decide the result, and Gradle tracks neither")
public abstract class ChangedLinesTask extends DefaultTask {

  /** Source directories of the module, relative to the repository root. */
  @Internal
  public abstract ListProperty<String> getSourceRoots();

  /** The ref whose merge base with HEAD the diff is taken from; see {@link ChangedLines#measure}. */
  @Internal
  public abstract Property<String> getBaseRef();

  /** The file the changed lines are written to. */
  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  /** Runs the git commands, and names the repository the diff is read from. */
  @ServiceReference(DiffCoverageTask.GIT_SERVICE)
  public abstract Property<GitService> getGit();

  @TaskAction
  public void write() throws IOException {
    Git git = getGit().get().git();
    ChangedLines diff = git.changedLines(getBaseRef().getOrNull());
    if (diff == null) {
      getLogger().warn("No base ref resolved, so no line counts as changed. {}",
          ChangedLines.noBaseHint("-P"));
      ChangedLinesFile.write(
          getOutputFile().get().getAsFile().toPath(), List.of("no base ref resolved"), List.of());
      return;
    }
    for (String note : diff.unreadable()) {
      getLogger().warn(note);
    }
    ChangedLinesFile.write(
        getOutputFile().get().getAsFile().toPath(),
        List.of("against " + diff.ref() + " (" + diff.shortCommit() + ")"),
        ChangedLines.underSourceRoots(git.repositoryRoot(), diff.lines(), getSourceRoots().get()));
  }
}
