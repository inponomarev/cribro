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
package io.github.cribrodev.maven;

import io.github.cribrodev.ChangedFile;
import io.github.cribrodev.ChangedLines;
import io.github.cribrodev.ChangedLinesFile;
import io.github.cribrodev.Git;
import io.github.cribrodev.CribroException;
import io.github.cribrodev.PitestJvmArgs;
import io.github.cribrodev.PitestRun;
import io.github.cribrodev.SourceFileKey;
import io.github.cribrodev.Versions;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.PatternIncludesArtifactFilter;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

/**
 * Runs PIT on the lines the branch changed in this module, with the module's tests, and prints the
 * mutants that survived.
 *
 * <p>The tests run as surefire's {@code default-test} execution runs them: with its classpath, JVM,
 * JVM arguments, system properties, environment, working directory, groups, includes, and
 * excludes. Run it after the classes are compiled, as {@code mvn test-compile cribro:pitest-diff}; it compiles nothing itself.
 *
 * <p>A module mutates its own changed classes, and those of the modules it tests that have no tests
 * of their own, as Guava's {@code guava-tests} tests {@code guava}.
 */
@Mojo(
    name = "pitest-diff",
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true)
public class PitestDiffMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession session;

  @Parameter(defaultValue = "${plugin}", readonly = true)
  private PluginDescriptor plugin;

  /**
   * A PIT glob for the tests allowed to kill a mutant, comma-separated; unset uses surefire's
   * includes.
   */
  @Parameter(property = "cribro.pitestTargetTests")
  private String targetTests;

  /** The PIT mutator group: {@code DEFAULTS}, {@code STRONGER}, or {@code ALL}. */
  @Parameter(property = "cribro.pitestMutators", defaultValue = "DEFAULTS")
  private String mutators;

  /** Mutants analyzed in parallel; unset uses half the cores. */
  @Parameter(property = "cribro.pitestThreads")
  private Integer threads;

  /** The time PIT adds to a test's normal run before it calls a mutant a timeout, in ms. */
  @Parameter(property = "cribro.pitestTimeoutConst", defaultValue = "4000")
  private int timeoutConst;

  /** The PIT version; unset uses the one cribro was built with. */
  @Parameter(property = "cribro.pitestVersion")
  private String pitestVersion;

  /** The version of PIT's JUnit Platform plugin; unset uses the one cribro was built with. */
  @Parameter(property = "cribro.pitestJunit5PluginVersion")
  private String pitestJunit5PluginVersion;

  /**
   * Further PIT plugins to put on PIT's classpath, as comma-separated coordinates, such as
   * Arcmutate's Kotlin plugin, which filters the mutants Kotlin's compiler-generated bytecode
   * produces beyond what PIT's own Kotlin filter catches.
   */
  @Parameter(property = "cribro.pitestPlugins")
  private String pitestPlugins;

  @Parameter(property = "cribro.pitestShowAll", defaultValue = "false")
  private boolean showAll;

  @Parameter(property = "cribro.pitestMaxFiles", defaultValue = "10")
  private int maxFiles;

  @Parameter(property = "cribro.pitestMaxLinesPerFile", defaultValue = "20")
  private int maxLinesPerFile;

  @Parameter(property = "cribro.pitestMaxLines", defaultValue = "50")
  private int maxLines;

  @Parameter(defaultValue = "${project.build.directory}/cribro/pitest-diff", readonly = true)
  private File reportDir;

  @Component private RepositorySystem repositorySystem;

  @Component private LifecycleExecutor lifecycleExecutor;

  @Component(role = ComponentConfigurator.class, hint = "basic")
  private ComponentConfigurator configurator;

  @Component private ToolchainManager toolchainManager;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {
      run();
    } catch (CribroException e) {
      throw new MojoFailureException(e.getMessage(), e);
    } catch (IOException
        | URISyntaxException
        | DependencyResolutionRequiredException
        | DependencyResolutionException e) {
      throw new MojoExecutionException(e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MojoExecutionException("interrupted while PIT ran", e);
    }
  }

  private void run()
      throws IOException,
          URISyntaxException,
          DependencyResolutionRequiredException,
          DependencyResolutionException,
          InterruptedException,
          MojoFailureException,
          MojoExecutionException {
    MavenExecutionRequest request = session.getRequest();
    if (addedByAlsoMake(
        request.getMakeBehavior(),
        request.getSelectedProjects(),
        request.getBaseDirectory() == null ? null : new File(request.getBaseDirectory()),
        project)) {
      getLog()
          .info(
              "Skipped: -am builds "
                  + project.getArtifactId()
                  + " only for the modules -pl names; add it to -pl to run PIT on its tests.");
      return;
    }
    File testClasses = new File(project.getBuild().getTestOutputDirectory());
    if (!hasClasses(testClasses)) {
      getLog().info("Skipped: " + project.getArtifactId() + " has no compiled tests.");
      return;
    }
    // Found from the session's top directory, as the coverage report finds it, so that both reports
    // measure the same repository against one base. The instance lasts for the session, so the
    // goal measures the diff once however many modules run it.
    Git git = CribroState.of(session).git();
    if (git == null) {
      getLog().info("Skipped: " + session.getExecutionRootDirectory() + " is not in a git work tree.");
      return;
    }
    File repository = git.repositoryRoot();
    String diffBase =
        CribroProperties.get(session, session.getTopLevelProject(), ChangedLines.BASE_PROPERTY);
    ChangedLines diff = git.changedLines(diffBase);
    if (diff == null) {
      getLog().info("Skipped: no base ref resolved. " + ChangedLines.noBaseHint("-D"));
      return;
    }
    List<MavenProject> mutated = mutatedProjects(project, session.getProjects());
    List<ChangedFile> changed = new ArrayList<>();
    List<File> sourceDirs = new ArrayList<>();
    List<File> classDirs = new ArrayList<>();
    for (MavenProject module : mutated) {
      for (String unresolved : SourceRoots.unresolvedKotlinSourceDirs(module, "compile")) {
        getLog().warn(unresolvedSourceDirMessage(module, unresolved));
      }
      List<String> roots = SourceRoots.of(repository, module);
      for (String root : roots) {
        sourceDirs.add(new File(repository, root));
      }
      changed.addAll(ChangedLines.underSourceRoots(repository, diff.lines(), roots));
      classDirs.add(new File(module.getBuild().getOutputDirectory()));
    }
    if (changed.isEmpty()) {
      getLog()
          .info(
              "Skipped: the branch changed no line in the main sources of "
                  + mutated.stream().map(MavenProject::getArtifactId).collect(Collectors.joining(", "))
                  + ".");
      return;
    }
    SurefireConfiguration surefire = surefireConfiguration();
    List<String> notes = new ArrayList<>();
    String tests = testSelection(targetTests, surefire, notes);
    String excluded = excludedTests(surefire, notes);
    if (!hasMatchingTest(testClasses.toPath(), tests, excluded)) {
      getLog()
          .info(
              "Skipped: no test class of "
                  + project.getArtifactId()
                  + " is among the tests PIT runs: "
                  + (tests != null ? tests : PitestRun.ALL_TESTS)
                  + (excluded != null ? ", except " + excluded : "")
                  + ".");
      return;
    }
    deleteRecursively(reportDir.toPath());
    Files.createDirectories(reportDir.toPath());
    File changedLines = new File(reportDir, "changed-lines.txt");
    ChangedLinesFile.write(
        changedLines.toPath(),
        List.of("against " + diff.ref() + " (" + diff.shortCommit() + ")"),
        changed);

    Artifact engine = artifact("org.junit.platform", "junit-platform-engine");
    List<File> testClasspath = testClasspath(surefire, engine);
    List<File> pitClasspath = pitClasspath(engine != null);

    List<String> unresolved = new ArrayList<>();
    List<String> arguments = argLineArguments(surefire.argLine, project.getProperties(), unresolved);
    PitestJvmArgs jvmArgs =
        PitestJvmArgs.of(
            arguments,
            systemProperties(
                surefire, session.getUserProperties(), project.getBasedir(), localRepository()),
            null);
    List<String> leftOut = new ArrayList<>(unresolved);
    leftOut.addAll(jvmArgs.leftOut());
    String leftOutMessage = PitestRun.leftOutMessage(leftOut, "surefire's default-test");

    PitestRun pit =
        new PitestRun()
            .targetClasses(
                ChangedLinesFile.targetClasses(
                    changed.stream().map(ChangedFile::key).collect(Collectors.toList())))
            .reportDir(reportDir)
            .mutableCodePaths(classDirs)
            .sourceDirs(sourceDirs)
            .classPath(testClasspath)
            .jvmArgs(jvmArgs.value())
            .mutators(mutators)
            .threads(
                threads != null
                    ? threads
                    : Math.max(1, Runtime.getRuntime().availableProcessors() / 2))
            .timeoutConst(timeoutConst)
            .changedLines(changedLines)
            .sourceEncoding(sourceEncoding(project.getProperties()));
    addTestSelection(pit, tests, excluded, surefire);

    List<String> command =
        command(javaExecutable(surefire), pitClasspath, pit.systemProperties(), pit.arguments());
    File log = new File(reportDir, PitestRun.LOG);
    ProcessBuilder builder = pitProcess(command, surefire, project.getBasedir(), log);
    Files.createDirectories(builder.directory().toPath());
    getLog().info("Running PIT on " + changed.size() + " changed files; its output goes to " + log);
    int exitCode = waitFor(builder.start());
    if (exitCode != 0) {
      throw new MojoFailureException(
          PitestRun.failureMessage(exitCode, repository, log, leftOutMessage));
    }
    String text =
        PitestRun.report(
            changedLines,
            reportDir,
            tests,
            repository,
            sourceDirs,
            maxFiles,
            maxLinesPerFile,
            maxLines,
            showAll);
    if (leftOutMessage != null) {
      notes.add(leftOutMessage);
    }
    notes.add(text);
    // The report is the goal's output, so it is printed even under mvn -q.
    System.out.println(String.join("\n", notes));
    System.out.flush();
  }

  /**
   * Waits for PIT to exit and returns its exit code, stopping PIT and its minions where the wait
   * ends otherwise: the thread is interrupted, or Maven's JVM is shutting down, as on SIGTERM.
   */
  static int waitFor(Process process) throws IOException, InterruptedException {
    return waitFor(process, Duration.ofSeconds(10));
  }

  /** As {@link #waitFor(Process)}, with the time PIT's processes get to exit before they are killed. */
  static int waitFor(Process process, Duration grace) throws IOException, InterruptedException {
    Thread hook = new Thread(() -> stop(process, grace), "cribro-stop-pit");
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      process.getOutputStream().close();
      return process.waitFor();
    } finally {
      stop(process, grace);
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException e) {
        // The JVM is shutting down, and the hook stops PIT.
      }
    }
  }

  /**
   * Stops the process and its descendants, and kills each one still running after the grace
   * period. The descendants are listed before any is signalled: one that outlives the process is
   * no longer its descendant.
   */
  private static void stop(Process process, Duration grace) {
    if (!process.isAlive()) {
      return;
    }
    List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());
    // The interrupt that ended the wait may still be pending, and it would end these waits at
    // once, so the flag is cleared while they run and restored after.
    boolean interrupted = Thread.interrupted();
    try {
      descendants.forEach(ProcessHandle::destroy);
      process.destroy();
      interrupted |= awaitExit(process, descendants, grace);
      descendants.forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      // destroyForcibly returns before the process has exited, on Windows at least.
      interrupted |= awaitExit(process, descendants, grace);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Waits until the process and its descendants have exited or the time is up, and returns
   * whether the thread was interrupted meanwhile. An interrupt does not end the wait.
   *
   * <p>The process is awaited through its own handle. On Windows, {@link ProcessHandle#onExit()}
   * opens the process again by its pid, and completes at once where that fails, as it can while
   * the process is terminating.
   */
  private static boolean awaitExit(
      Process process, List<ProcessHandle> descendants, Duration timeout) {
    boolean interrupted = false;
    long deadline = System.nanoTime() + timeout.toNanos();
    for (ProcessHandle handle : descendants) {
      while (true) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return interrupted;
        }
        try {
          handle.onExit().get(left, TimeUnit.NANOSECONDS);
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        } catch (ExecutionException | TimeoutException e) {
          break;
        }
      }
    }
    while (true) {
      try {
        process.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        return interrupted;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
  }

  /**
   * Returns whether the build runs with {@code -am} and {@code -pl} does not name the project,
   * which {@code -am}, or {@code -amd} beside it, then added. Such a project is built for the
   * selected ones, so it runs no PIT of its own: one run per selected module, as Gradle's {@code
   * <project>:pitestDiff} gives, rather than one per module with tests, each mutating the
   * test-less modules it depends on again.
   *
   * @param selectors the {@code -pl} entries, matched as Maven matches them: {@code :artifactId},
   *     {@code groupId:artifactId}, or a path to the module's directory or POM relative to {@code
   *     baseDirectory}
   */
  static boolean addedByAlsoMake(
      String makeBehavior, List<String> selectors, File baseDirectory, MavenProject project) {
    if (selectors.isEmpty()
        || !(MavenExecutionRequest.REACTOR_MAKE_UPSTREAM.equals(makeBehavior)
            || MavenExecutionRequest.REACTOR_MAKE_BOTH.equals(makeBehavior))) {
      return false;
    }
    for (String selector : selectors) {
      if (selector.indexOf(':') >= 0) {
        if (selector.equals(":" + project.getArtifactId())
            || selector.equals(project.getGroupId() + ":" + project.getArtifactId())) {
          return false;
        }
      } else if (baseDirectory != null) {
        File selected = new File(new File(baseDirectory, selector).toURI().normalize());
        if (selected.isFile()
            ? selected.equals(project.getFile())
            : selected.equals(project.getBasedir())) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Returns the arguments of surefire's {@code argLine} that PIT passes to the tests: each
   * {@code @{name}} the project defines is expanded, surefire's fork number is 1 as {@link
   * SurefireConfiguration#firstFork} gives it, and JaCoCo agents are left out, since PIT attaches
   * its own coverage.
   *
   * @param unresolved receives the arguments with an {@code @{name}} the project does not define,
   *     which are left out
   */
  static List<String> argLineArguments(
      String argLine, Properties projectProperties, List<String> unresolved) {
    List<String> arguments = new ArrayList<>();
    for (String argument :
        ArgLine.withoutJacocoAgents(
            ArgLine.split(SurefireConfiguration.firstFork(ArgLine.expandLate(argLine, projectProperties))))) {
      // An @{name} the project never set reaches surefire's JVM as it is, and fails it; PIT would
      // fail the same way, so the argument is left out and named.
      if (argument.contains("@{")) {
        unresolved.add(argument);
      } else {
        arguments.add(argument);
      }
    }
    return arguments;
  }

  /**
   * Returns the process that runs PIT with surefire's working directory and environment, its
   * output going to the log.
   *
   * <p>PIT starts its minions with its own environment, so the tests get it as surefire sets it:
   * without {@code excludedEnvironmentVariables}, and with {@code environmentVariables}, where an
   * empty element is an empty string, as surefire passes it. Surefire's fork number in a value or
   * in the working directory is 1, as {@link SurefireConfiguration#firstFork} gives it.
   */
  static ProcessBuilder pitProcess(
      List<String> command, SurefireConfiguration surefire, File basedir, File log) {
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(
                surefire.workingDirectory != null
                    ? new File(SurefireConfiguration.firstFork(surefire.workingDirectory.getPath()))
                    : basedir)
            .redirectErrorStream(true)
            .redirectOutput(log);
    Map<String, String> environment = builder.environment();
    environment.keySet().removeAll(surefire.excludedEnvironmentVariables);
    surefire.environmentVariables.forEach(
        (name, value) ->
            environment.put(name, value == null ? "" : SurefireConfiguration.firstFork(value)));
    return builder;
  }

  /**
   * Returns the charset Maven's compiler reads the sources in: {@code project.build.sourceEncoding},
   * or the platform's charset where the project sets none.
   */
  static String sourceEncoding(Properties projectProperties) {
    String encoding = projectProperties.getProperty("project.build.sourceEncoding");
    return encoding != null ? encoding : Charset.defaultCharset().name();
  }

  /** Returns the command that starts PIT with the system properties {@link PitestRun} returns. */
  static List<String> command(
      String java,
      List<File> pitClasspath,
      Map<String, String> systemProperties,
      List<String> arguments) {
    List<String> command = new ArrayList<>();
    command.add(java);
    command.add("-cp");
    command.add(
        pitClasspath.stream()
            .map(File::getAbsolutePath)
            .collect(Collectors.joining(File.pathSeparator)));
    systemProperties.forEach((name, value) -> command.add("-D" + name + "=" + value));
    command.add(PitestRun.MAIN_CLASS);
    command.addAll(arguments);
    return command;
  }

  /**
   * Returns the module and the reactor modules on its test classpath that have no tests of their
   * own, whose changes only the module's tests can reach.
   */
  static List<MavenProject> mutatedProjects(MavenProject project, List<MavenProject> projects) {
    Map<String, MavenProject> reactor = new LinkedHashMap<>();
    for (MavenProject module : projects) {
      reactor.put(module.getGroupId() + ":" + module.getArtifactId(), module);
    }
    List<MavenProject> mutated = new ArrayList<>();
    mutated.add(project);
    for (Artifact artifact : project.getArtifacts()) {
      MavenProject module = reactor.get(artifact.getGroupId() + ":" + artifact.getArtifactId());
      if (module != null
          && module != project
          && artifact.getClassifier() == null
          && !hasTestSources(module)) {
        mutated.add(module);
      }
    }
    return mutated;
  }

  /**
   * Returns whether a class in the directory matches one of PIT's comma-separated globs and none
   * of the excluded ones, as PIT selects its tests. A directory that cannot be read counts as
   * matching.
   *
   * <p>A module none of whose tests match skips the goal: PIT would only report every mutant as not
   * reached, while the module whose tests match reports them.
   *
   * @param globs the tests PIT runs, or null for every class
   * @param excludedGlobs the tests PIT leaves out, or null for none
   */
  static boolean hasMatchingTest(Path testClasses, String globs, String excludedGlobs) {
    List<Pattern> patterns = pitGlobs(globs != null ? globs : "*");
    List<Pattern> excluded = excludedGlobs != null ? pitGlobs(excludedGlobs) : List.of();
    try (Stream<Path> walk = Files.walk(testClasses)) {
      return walk.map(path -> testClasses.relativize(path).toString())
          .filter(name -> name.endsWith(".class"))
          .map(
              name ->
                  name.substring(0, name.length() - ".class".length())
                      .replace(File.separatorChar, '.'))
          .anyMatch(
              name ->
                  patterns.stream().anyMatch(p -> p.matcher(name).matches())
                      && excluded.stream().noneMatch(p -> p.matcher(name).matches()));
    } catch (IOException e) {
      return true;
    }
  }

  private static List<Pattern> pitGlobs(String globs) {
    List<Pattern> patterns = new ArrayList<>();
    for (String glob : globs.split(",")) {
      patterns.add(pitGlob(glob.trim()));
    }
    return patterns;
  }

  /**
   * Returns the pattern PIT's {@code Glob} builds: {@code ~} starts a regular expression, in
   * which PIT reads every {@code +} as a literal one, {@code **.} stands for any number of
   * packages, {@code *} for any text, and {@code ?} for one character.
   */
  static Pattern pitGlob(String glob) {
    if (glob.startsWith("~")) {
      return Pattern.compile(glob.substring(1).replace("+", "\\+"));
    }
    StringBuilder regex = new StringBuilder();
    int literalStart = 0;
    for (int i = 0; i < glob.length(); i++) {
      String wildcard;
      int length = 1;
      if (glob.startsWith("**.", i)) {
        wildcard = "(?:.*\\.)*";
        length = 3;
      } else if (glob.charAt(i) == '*') {
        wildcard = ".*";
      } else if (glob.charAt(i) == '?') {
        wildcard = ".";
      } else {
        continue;
      }
      if (literalStart < i) {
        regex.append(Pattern.quote(glob.substring(literalStart, i)));
      }
      regex.append(wildcard);
      i += length - 1;
      literalStart = i + 1;
    }
    if (literalStart < glob.length()) {
      regex.append(Pattern.quote(glob.substring(literalStart)));
    }
    return Pattern.compile(regex.toString());
  }

  /**
   * Returns the line that says a {@code kotlin-maven-plugin} source directory of the module is not
   * measured, because its expression is one Maven did not interpolate.
   */
  static String unresolvedSourceDirMessage(MavenProject module, String sourceDir) {
    return "kotlin-maven-plugin sourceDir "
        + sourceDir
        + " of "
        + module.getArtifactId()
        + " holds an expression Maven did not resolve, so the changes under it are not measured.";
  }

  private static boolean hasTestSources(MavenProject module) {
    for (String root : SourceRoots.testSourceDirs(module)) {
      if (hasFiles(new File(root).toPath(), path -> SourceFileKey.isSource(path.toString()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasClasses(File directory) {
    return hasFiles(directory.toPath(), path -> path.toString().endsWith(".class"));
  }

  /** Returns whether the directory holds a wanted file, in one walk that stops at the first. */
  private static boolean hasFiles(Path directory, Predicate<Path> wanted) {
    if (!Files.isDirectory(directory)) {
      return false;
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.anyMatch(wanted);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Returns the configuration of surefire's {@code default-test} execution as surefire gets it:
   * the execution plan merges the plugin, its management, and the execution, and fills in the
   * defaults, and Maven's evaluator resolves the expressions.
   */
  private SurefireConfiguration surefireConfiguration() throws MojoExecutionException {
    SurefireConfiguration configuration = new SurefireConfiguration();
    try {
      MavenExecutionPlan plan = lifecycleExecutor.calculateExecutionPlan(session, "test");
      MojoExecution surefire =
          plan.getMojoExecutions().stream()
              .filter(CribroMojoListener::isSurefireDefaultTest)
              .findFirst()
              .orElse(null);
      if (surefire == null) {
        getLog().info(project.getArtifactId() + " has no surefire default-test execution; PIT uses defaults.");
        return configuration;
      }
      Xpp3Dom picked = new Xpp3Dom("configuration");
      for (Xpp3Dom child : surefire.getConfiguration().getChildren()) {
        if (SurefireConfiguration.NAMES.contains(child.getName())) {
          picked.addChild(new Xpp3Dom(child));
        }
      }
      configurator.configureComponent(
          configuration,
          new XmlPlexusConfiguration(picked),
          new PluginParameterExpressionEvaluator(session, surefire),
          plugin.getClassRealm());
    } catch (Exception e) {
      throw new MojoExecutionException("cannot read surefire's configuration: " + e.getMessage(), e);
    }
    return configuration;
  }

  /**
   * Returns the tests' classpath as surefire builds it, with the JUnit Platform launcher PIT's
   * plugin needs.
   *
   * <p>A reactor module appears as its classes directory rather than its jar, so that PIT runs the
   * classes it mutates: a jar an earlier build installed would hold other code.
   */
  private List<File> testClasspath(SurefireConfiguration surefire, Artifact engine)
      throws DependencyResolutionRequiredException, DependencyResolutionException {
    Map<String, File> reactorFiles = new LinkedHashMap<>();
    for (MavenProject module : session.getProjects()) {
      Artifact artifact = module.getArtifact();
      if (artifact != null && artifact.getFile() != null) {
        reactorFiles.put(
            artifact.getFile().getAbsolutePath(), new File(module.getBuild().getOutputDirectory()));
      }
    }
    Set<String> excluded = new LinkedHashSet<>();
    for (Artifact artifact : excluded(project.getArtifacts(), surefire)) {
      if (artifact.getFile() != null) {
        excluded.add(artifact.getFile().getAbsolutePath());
      }
    }
    Set<File> classpath = new LinkedHashSet<>();
    for (String element : project.getTestClasspathElements()) {
      File file = new File(element).getAbsoluteFile();
      if (!excluded.contains(file.getPath())) {
        classpath.add(reactorFiles.getOrDefault(file.getPath(), file));
      }
    }
    for (String element : surefire.additionalClasspathElements) {
      classpath.add(new File(element).getAbsoluteFile());
    }
    if (!surefire.additionalClasspathDependencies.isEmpty()) {
      List<Dependency> dependencies = new ArrayList<>();
      for (org.apache.maven.model.Dependency dependency : surefire.additionalClasspathDependencies) {
        dependencies.add(
            RepositoryUtils.toDependency(
                dependency, session.getRepositorySession().getArtifactTypeRegistry()));
      }
      for (File file : resolveDependencies(dependencies, project.getRemoteProjectRepositories())) {
        classpath.add(file.getAbsoluteFile());
      }
    }
    if (engine != null && artifact("org.junit.platform", "junit-platform-launcher") == null) {
      // PIT keeps only its plugins' jars from its own classpath, so the launcher has to come with
      // the tests, in the version of their engine.
      for (File file :
          resolve(List.of("org.junit.platform:junit-platform-launcher:" + engine.getBaseVersion()))) {
        classpath.add(file.getAbsoluteFile());
      }
    }
    return new ArrayList<>(classpath);
  }

  /**
   * Returns the artifacts of the tests' classpath that surefire leaves out: those of the scopes
   * {@code classpathDependencyScopeExclude} names, and those {@code classpathDependencyExcludes}
   * matches, with the filters surefire itself applies.
   */
  static Set<Artifact> excluded(Set<Artifact> artifacts, SurefireConfiguration surefire) {
    Set<Artifact> excluded = new LinkedHashSet<>();
    String scope = surefire.classpathDependencyScopeExclude;
    if (scope != null && !scope.isEmpty()) {
      ScopeArtifactFilter filter = new ScopeArtifactFilter(scope);
      artifacts.stream().filter(filter::include).forEach(excluded::add);
    }
    if (!surefire.classpathDependencyExcludes.isEmpty()) {
      PatternIncludesArtifactFilter filter =
          new PatternIncludesArtifactFilter(surefire.classpathDependencyExcludes);
      artifacts.stream().filter(filter::include).forEach(excluded::add);
    }
    return excluded;
  }

  /**
   * Returns the classpath PIT itself starts with: PIT, cribro, PIT's JUnit 5 plugin where the
   * tests run on the JUnit Platform, and the plugins {@code cribro.pitestPlugins} names.
   */
  private List<File> pitClasspath(boolean junitPlatform)
      throws DependencyResolutionException, URISyntaxException {
    List<String> coordinates = new ArrayList<>();
    coordinates.add(
        "org.pitest:pitest-command-line:" + (pitestVersion != null ? pitestVersion : Versions.pitest()));
    if (junitPlatform) {
      coordinates.add(
          "org.pitest:pitest-junit5-plugin:"
              + (pitestJunit5PluginVersion != null
                  ? pitestJunit5PluginVersion
                  : Versions.pitestJunit5Plugin()));
    }
    coordinates.addAll(PitestRun.plugins(pitestPlugins));
    List<File> files = resolve(coordinates);
    files.add(
        new File(ChangedLinesFile.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    return files;
  }

  private Artifact artifact(String groupId, String artifactId) {
    for (Artifact artifact : project.getArtifacts()) {
      if (artifact.getGroupId().equals(groupId) && artifact.getArtifactId().equals(artifactId)) {
        return artifact;
      }
    }
    return null;
  }

  private List<File> resolve(List<String> coordinates) throws DependencyResolutionException {
    List<Dependency> dependencies = new ArrayList<>();
    for (String coordinate : coordinates) {
      dependencies.add(new Dependency(new DefaultArtifact(coordinate), JavaScopes.RUNTIME));
    }
    List<RemoteRepository> repositories = new ArrayList<>(project.getRemotePluginRepositories());
    repositories.addAll(project.getRemoteProjectRepositories());
    return resolveDependencies(dependencies, repositories);
  }

  /** Resolves the dependencies with their runtime dependencies, as surefire resolves them. */
  private List<File> resolveDependencies(
      List<Dependency> dependencies, List<RemoteRepository> repositories)
      throws DependencyResolutionException {
    CollectRequest collect = new CollectRequest();
    collect.setDependencies(dependencies);
    collect.setRepositories(repositories);
    DependencyRequest request =
        new DependencyRequest(collect, DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
    List<File> files = new ArrayList<>();
    for (ArtifactResult result :
        repositorySystem
            .resolveDependencies(session.getRepositorySession(), request)
            .getArtifactResults()) {
      files.add(result.getArtifact().getFile());
    }
    return files;
  }

  /** Returns the local repository's path as surefire gives it to the tests, "." where it has none. */
  private String localRepository() {
    File basedir = session.getRepositorySession().getLocalRepository().getBasedir();
    return basedir == null ? "." : basedir.getAbsolutePath();
  }

  /**
   * Returns the system properties of the test JVM, in surefire's order of precedence, each source
   * overriding the ones before it: {@code systemProperties}, {@code systemPropertiesFile}, {@code
   * systemPropertyVariables}, the user's {@code -D} properties other than {@code cribro.*}, and
   * then {@code basedir} and {@code localRepository}, which surefire sets on every run. Surefire's
   * fork number in a value is 1, as {@link SurefireConfiguration#firstFork} gives it.
   *
   * @param localRepository the path of Maven's local repository, as surefire passes it
   */
  static Map<String, String> systemProperties(
      SurefireConfiguration surefire,
      Properties userProperties,
      File basedir,
      String localRepository)
      throws IOException {
    Map<String, String> properties = new LinkedHashMap<>();
    for (String name : surefire.systemProperties.stringPropertyNames()) {
      properties.put(name, surefire.systemProperties.getProperty(name));
    }
    if (surefire.systemPropertiesFile != null && surefire.systemPropertiesFile.isFile()) {
      Properties file = new Properties();
      try (InputStream in = Files.newInputStream(surefire.systemPropertiesFile.toPath())) {
        file.load(in);
      }
      for (String name : file.stringPropertyNames()) {
        properties.put(name, file.getProperty(name));
      }
    }
    surefire.systemPropertyVariables.forEach(
        (name, value) -> properties.put(name, value == null ? "" : value));
    if (surefire.promoteUserPropertiesToSystemProperties) {
      for (String name : userProperties.stringPropertyNames()) {
        // cribro's own properties steer this goal, not the tests.
        if (!name.startsWith("cribro.")) {
          properties.put(name, userProperties.getProperty(name));
        }
      }
    }
    properties.put("basedir", basedir.getAbsolutePath());
    properties.put("localRepository", localRepository);
    properties.replaceAll((name, value) -> SurefireConfiguration.firstFork(value));
    return properties;
  }

  /**
   * Returns the PIT glob of the tests to run: the user's {@code cribro.pitestTargetTests}, or else
   * surefire's includes, its defaults where the list is empty, or null for every test class.
   *
   * <p>The includes are those surefire runs: its {@code includes} and the lines of its {@code
   * includesFile}, or its defaults where both are empty. An include of the form {@code **}{@code
   * /<name>.java}, such as {@code **}{@code /*Test.java}, becomes a PIT pattern of the classes
   * with that simple name. Where any include does not map, having another form or a comma or a
   * dot in the name, the result is null, as without surefire's includes, and {@code notes} names
   * the includes PIT could not apply.
   *
   * @param targetTests the user's {@code cribro.pitestTargetTests}, or null
   * @throws IOException where surefire's {@code includesFile} cannot be read, which fails
   *     surefire's own run too
   */
  static String testSelection(
      String targetTests, SurefireConfiguration surefire, List<String> notes) throws IOException {
    if (targetTests != null) {
      return targetTests;
    }
    List<String> notApplied = new ArrayList<>();
    List<String> includes = patterns(surefire.includes, surefire.includesFile, "includesFile");
    if (includes.isEmpty()) {
      includes = SurefireConfiguration.DEFAULT_INCLUDES;
    }
    List<String> classes = classGlobs(includes, notApplied);
    if (!notApplied.isEmpty()) {
      notes.add(
          "PIT ran every test class, since these includes of surefire do not map to PIT, which"
              + " takes only **/<name>.java patterns whose name holds no comma or dot: "
              + String.join(" ", notApplied));
      return null;
    }
    return classes.isEmpty() ? null : String.join(",", classes);
  }

  /**
   * Returns the PIT patterns of the test classes surefire leaves out, comma-separated, or null where
   * none maps. The excludes are surefire's {@code excludes} and the lines of its {@code
   * excludesFile}, or its default {@code **}{@code /*$*} where both are empty. An exclude maps to
   * PIT as an include does in {@link #testSelection}; one that does not is named in {@code notes}.
   *
   * @throws IOException where surefire's {@code excludesFile} cannot be read
   */
  static String excludedTests(SurefireConfiguration surefire, List<String> notes)
      throws IOException {
    List<String> notApplied = new ArrayList<>();
    List<String> excludes = patterns(surefire.excludes, surefire.excludesFile, "excludesFile");
    if (excludes.isEmpty()) {
      excludes = SurefireConfiguration.DEFAULT_EXCLUDES;
    }
    List<String> classes = classGlobs(excludes, notApplied);
    if (!notApplied.isEmpty()) {
      notes.add(
          "PIT ran tests that these excludes of surefire leave out, since only **/<name>.java"
              + " patterns whose name holds no comma or dot, and **/*$*, map to PIT: "
              + String.join(" ", notApplied));
    }
    return classes.isEmpty() ? null : String.join(",", classes);
  }

  /**
   * Passes the tests to run, the tests to leave out, and surefire's groups to PIT.
   *
   * @param tests the patterns {@link #testSelection} returned, or null for every test class
   * @param excluded the patterns {@link #excludedTests} returned, or null
   */
  static void addTestSelection(
      PitestRun pit, String tests, String excluded, SurefireConfiguration surefire) {
    if (surefire.groups != null && !surefire.groups.isBlank()) {
      pit.option("includedGroups", surefire.groups);
    }
    if (surefire.excludedGroups != null && !surefire.excludedGroups.isBlank()) {
      pit.option("excludedGroups", surefire.excludedGroups);
    }
    if (tests != null) {
      pit.targetTests(tests);
    }
    if (excluded != null) {
      pit.option("excludedTestClasses", excluded);
    }
  }

  /**
   * Returns the patterns listed in the POM and then the lines of {@code file}, trimmed, without
   * blank lines and lines that start with {@code #}, as surefire reads the file.
   */
  private static List<String> patterns(List<String> listed, File file, String parameter)
      throws IOException {
    List<String> patterns = new ArrayList<>(listed == null ? List.of() : listed);
    if (file != null) {
      if (!file.isFile()) {
        throw new IOException("cannot read surefire's " + parameter + " " + file + ": no such file");
      }
      // Surefire reads the file in the platform charset too, so a pattern means the same in both.
      for (String line : Files.readAllLines(file.toPath(), Charset.defaultCharset())) {
        String pattern = line.trim();
        if (!pattern.isEmpty() && !pattern.startsWith("#")) {
          patterns.add(pattern);
        }
      }
    }
    return patterns;
  }

  /**
   * Returns the PIT patterns of surefire's file patterns, and adds the others to {@code notMapped}.
   * PIT's {@code *} crosses the dots between packages, where surefire's stops at a directory, so a
   * name with another wildcard than one leading {@code *} becomes a regular expression.
   */
  private static List<String> classGlobs(List<String> patterns, List<String> notMapped) {
    List<String> classes = new ArrayList<>();
    for (String raw : patterns) {
      String pattern = raw.trim();
      if (pattern.equals("**/*$*")) {
        // Surefire's default exclude, of every nested class.
        classes.add(classPattern("*$*"));
      } else if (pattern.startsWith("**/")
          && pattern.endsWith(".java")
          && pattern.indexOf('/', 3) < 0) {
        String name = pattern.substring(3, pattern.length() - ".java".length());
        String className = classPattern(name);
        if (className != null) {
          classes.add(className);
        } else {
          notMapped.add(pattern);
        }
      } else if (!pattern.isEmpty()) {
        notMapped.add(pattern);
      }
    }
    return classes;
  }

  /**
   * Returns the PIT pattern of the classes whose simple name matches surefire's file name pattern,
   * or null where the name holds a comma, at which PIT splits its patterns, or a dot, which no
   * simple name holds.
   */
  private static String classPattern(String name) {
    if (name.indexOf(',') >= 0 || name.indexOf('.') >= 0) {
      return null;
    }
    String rest = name.startsWith("*") ? name.substring(1) : name;
    if (rest.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '$')) {
      // A leading * ends at the last dot anyway, since the rest of the name holds none.
      return "**." + name;
    }
    StringBuilder regex = new StringBuilder("~(.*\\.)?");
    for (char c : name.toCharArray()) {
      if (c == '*') {
        regex.append("[^.]*");
      } else if (c == '?') {
        regex.append("[^.]");
      } else if (c == '+' || Character.isLetterOrDigit(c) || c == '_') {
        // PIT writes every + of a regular expression as \+ itself.
        regex.append(c);
      } else {
        // A backslash makes any character but a letter or a digit stand for itself.
        regex.append('\\').append(c);
      }
    }
    return regex.toString();
  }

  /**
   * Returns the java executable of the tests, chosen as surefire chooses it: its {@code jvm}, then
   * its {@code jdkToolchain}, then the build's toolchain, then the JVM Maven runs on.
   */
  private String javaExecutable(SurefireConfiguration surefire) throws MojoFailureException {
    if (surefire.jvm != null && !surefire.jvm.isBlank()) {
      return surefire.jvm;
    }
    Toolchain toolchain = null;
    if (!surefire.jdkToolchain.isEmpty()) {
      List<Toolchain> matching =
          toolchainManager.getToolchains(session, "jdk", surefire.jdkToolchain);
      if (matching.isEmpty()) {
        throw new MojoFailureException(
            "No toolchain matches surefire's jdkToolchain " + surefire.jdkToolchain);
      }
      toolchain = matching.get(0);
    }
    if (toolchain == null) {
      toolchain = toolchainManager.getToolchainFromBuildContext("jdk", session);
    }
    if (toolchain != null && toolchain.findTool("java") != null) {
      return toolchain.findTool("java");
    }
    return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Files.delete(path);
      }
    }
  }
}
