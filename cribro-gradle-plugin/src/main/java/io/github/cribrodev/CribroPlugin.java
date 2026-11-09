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
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.testing.jacoco.plugins.JacocoPlugin;
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension;
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension;
import org.gradle.testing.jacoco.tasks.JacocoReport;

/**
 * Adds {@code diffCoverage}, {@code changedLines}, and {@code pitestDiff} to every project that
 * applies the {@code java} plugin, which the Kotlin JVM plugin does too, and has the {@code test}
 * task report which of the lines the branch changed it executed. Every project, Java or not, gets
 * the {@code cribroProjectElements} configuration, which tells the {@code cribro} task whether the
 * project applies the java plugin.
 *
 * <p>{@link CribroSettingsPlugin} applies it, often from an init script to a build that knows
 * nothing about it, so it overrides the build's own JaCoCo settings for the {@code test} task: a
 * build that applies JaCoCo with the report switched off still gets the per-module XML report that
 * diff coverage reads.
 */
public abstract class CribroPlugin implements Plugin<Project> {

  static final String PITEST_CONFIGURATION = "cribroPitest";

  /**
   * The consumable configuration of every project, which the {@code cribro} task of the root
   * project resolves to find the projects that apply the java plugin.
   */
  static final String PROJECT_ELEMENTS_CONFIGURATION = "cribroProjectElements";

  /**
   * The {@code Usage} and {@code Category} value of that configuration. Both are set, so that a
   * request for either attribute that names another value, as the JaCoCo and test report
   * aggregation plugins request {@code Category}, does not select it.
   */
  static final String PROJECT_USAGE = "io.github.cribrodev.project";

  /** Whether the project that owns the variant applies the java plugin. */
  static final Attribute<Boolean> APPLIES_JAVA =
      Attribute.of("io.github.cribrodev.appliesJava", Boolean.class);

  static final String JACOCO_VERSION = Versions.jacoco();

  static String version() {
    return Versions.cribro();
  }

  static String pitestVersion() {
    return Versions.pitest();
  }

  @Override
  public void apply(Project project) {
    // The cribro task of the root project resolves this configuration, because under Isolated
    // Projects a dependency is the only way one project can read another.
    project
        .getConfigurations()
        .create(
            PROJECT_ELEMENTS_CONFIGURATION,
            c -> {
              c.setCanBeConsumed(true);
              c.setCanBeResolved(false);
              c.setDescription("Whether this project applies the java plugin, for the cribro task");
              c.getAttributes()
                  .attribute(
                      Usage.USAGE_ATTRIBUTE,
                      project.getObjects().named(Usage.class, PROJECT_USAGE));
              c.getAttributes()
                  .attribute(
                      Category.CATEGORY_ATTRIBUTE,
                      project.getObjects().named(Category.class, PROJECT_USAGE));
              c.getAttributes()
                  .attributeProvider(
                      APPLIES_JAVA,
                      project.provider(() -> project.getPlugins().hasPlugin(JavaPlugin.class)));
            });
    // The overrides of the build's own settings wait until its scripts have run, or a build that
    // disables JaCoCo or its report for `test` would win. afterEvaluate is registered here: Gradle
    // refuses it inside the callback that sees the java plugin applied.
    List<Runnable> pending = new ArrayList<>();
    project
        .getPlugins()
        .withType(
            JavaPlugin.class,
            java -> {
              Runnable overrides = configure(project);
              if (project.getState().getExecuted()) {
                overrides.run();
              } else {
                pending.add(overrides);
              }
            });
    if (!project.getState().getExecuted()) {
      project.afterEvaluate(p -> pending.forEach(Runnable::run));
    }
  }

  /** Registers the tasks and returns the overrides of the build's own settings. */
  private static Runnable configure(Project project) {
    ProviderFactory providers = project.getProviders();
    // -Pcribro.diffCoverageEnabled=false keeps the tasks and drops the instrumentation of `test`, which
    // costs about 20% of its wall time, for a run that is not about coverage.
    boolean diffCoverageEnabled =
        providers
            .gradleProperty("cribro.diffCoverageEnabled")
            .map(it -> it.isEmpty() || Boolean.parseBoolean(it))
            .getOrElse(true);
    Provider<String> diffBase = providers.gradleProperty("cribro.diffBase");

    String jacocoVersion = providers.gradleProperty("cribro.jacocoVersion").getOrNull();

    File repositoryRoot = repositoryRoot(project.getRootDir());
    project
        .getGradle()
        .getSharedServices()
        .registerIfAbsent(
            DiffCoverageTask.GIT_SERVICE,
            GitService.class,
            spec -> spec.getParameters().getRepositoryRoot().set(repositoryRoot));

    SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
    SourceSet main = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    SourceSet test = sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME);
    // The main source roots relative to the repository root, the form git diff prints paths in. A
    // root outside the repository, such as a generated one under build/, holds no changed line.
    Provider<List<String>> mainSourceRoots =
        providers.provider(
            () -> {
              Path root = repositoryRoot.toPath();
              List<String> roots = new ArrayList<>();
              for (File dir : sourceDirs(main)) {
                Path path = dir.toPath().toAbsolutePath().normalize();
                if (path.startsWith(root)) {
                  roots.add(root.relativize(path).toString());
                }
              }
              return roots;
            });

    TaskProvider<Test> testTask = project.getTasks().named(JavaPlugin.TEST_TASK_NAME, Test.class);
    Provider<List<String>> testFilterPatterns =
        providers.provider(() -> testFilterPatterns(testTask.get()));
    String projectPath = project.getPath();
    TaskProvider<DiffCoverageTask> diffCoverage =
        project
            .getTasks()
            .register(
                "diffCoverage",
                DiffCoverageTask.class,
                task -> {
                  task.setDescription(
                      "Reports which of the lines this branch changed the last test run executed");
                  task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
                  // Read without a dependency on jacocoTestReport, so that running diffCoverage on its
                  // own reports on the last test run instead of running the tests again.
                  task.getReportFiles()
                      .from(
                          providers.provider(
                              () ->
                                  project
                                      .getTasks()
                                      .named("jacocoTestReport", JacocoReport.class)
                                      .get()
                                      .getReports()
                                      .getXml()
                                      .getOutputLocation()
                                      .get()));
                  task.getSourceRoots().set(mainSourceRoots);
                  // A filtered run leaves the rest of the change looking unexecuted, so the scope is
                  // printed beside the counts rather than left for the reader to remember.
                  task.getScopeDescription()
                      .set(
                          testFilterPatterns.map(
                              patterns ->
                                  patterns.isEmpty()
                                      ? projectPath + ":test"
                                      : projectPath
                                          + ":test --tests "
                                          + String.join(" --tests ", patterns)));
                  task.getFiltered().set(testFilterPatterns.map(it -> !it.isEmpty()));
                  task.getMaxFiles().set(intProperty(providers, "cribro.diffCoverageMaxFiles", 10));
                  task.getMaxLinesPerFile()
                      .set(intProperty(providers, "cribro.diffCoverageMaxLinesPerFile", 10));
                  task.getMaxLinesTotal().set(intProperty(providers, "cribro.diffCoverageMaxLines", 50));
                  task.getShowAll().set(flag(providers, "cribro.diffCoverageShowAll"));
                  task.getOutputFile()
                      .set(project.getLayout().getBuildDirectory().file("reports/diff-coverage/test.txt"));
                  task.getBaseRef().set(diffBase);
                });

    Runnable overrides =
        () -> {
          // JaCoCo is applied only now, so that the build's own scripts show whether it uses it.
          boolean buildAppliesJacoco = project.getPluginManager().hasPlugin("jacoco");
          project.getPluginManager().apply(JacocoPlugin.class);
          JacocoPluginExtension jacoco =
              project.getExtensions().getByType(JacocoPluginExtension.class);
          // The JaCoCo that Gradle bundles lags behind the JDKs that tests run on, so a build that
          // names no version of its own gets one that reads current class files.
          if (jacocoVersion != null) {
            jacoco.setToolVersion(jacocoVersion);
          } else if (JacocoPlugin.DEFAULT_JACOCO_VERSION.equals(jacoco.getToolVersion())
              && compareVersions(jacoco.getToolVersion(), JACOCO_VERSION) < 0) {
            jacoco.setToolVersion(JACOCO_VERSION);
          }
          if (!buildAppliesJacoco) {
            // JaCoCo instruments every Test task once applied, and only `test` feeds the report;
            // the others, such as integration or multi-JDK runs, would pay the cost for nothing.
            project
                .getTasks()
                .withType(Test.class)
                .matching(t -> !t.getName().equals(JavaPlugin.TEST_TASK_NAME))
                .configureEach(
                    t -> t.getExtensions().getByType(JacocoTaskExtension.class).setEnabled(false));
          }
          TaskProvider<JacocoReport> jacocoTestReport =
              project.getTasks().named("jacocoTestReport", JacocoReport.class);
          testTask.configure(
              t -> {
                t.getExtensions().getByType(JacocoTaskExtension.class).setEnabled(diffCoverageEnabled);
                if (diffCoverageEnabled) {
                  // Running the report as part of the test run keeps the XML in step with the
                  // execution data, so diffCoverage cannot read line numbers from an earlier run.
                  t.finalizedBy(jacocoTestReport);
                }
              });
          jacocoTestReport.configure(
              r -> {
                r.setEnabled(diffCoverageEnabled);
                r.getReports().getXml().getRequired().set(true);
                if (diffCoverageEnabled) {
                  r.finalizedBy(diffCoverage);
                }
              });
        };

    TaskProvider<ChangedLinesTask> changedLines =
        project
            .getTasks()
            .register(
                "changedLines",
                ChangedLinesTask.class,
                task -> {
                  task.setDescription(
                      "Writes the lines this branch changed in the main sources, for pitestDiff");
                  task.getSourceRoots().set(mainSourceRoots);
                  task.getBaseRef().set(diffBase);
                  task.getOutputFile()
                      .set(project.getLayout().getBuildDirectory().file("diff/changed-lines.txt"));
                });

    Configuration pitest =
        project
            .getConfigurations()
            .create(
                PITEST_CONFIGURATION,
                c -> {
                  c.setCanBeConsumed(false);
                  c.setCanBeResolved(true);
                  c.setDescription("PIT and its test framework plugin, for pitestDiff");
                });
    // Prefixed, since a build may set pitestVersion for a PIT plugin of its own.
    String pitestVersion =
        providers
            .gradleProperty("cribro.pitestVersion")
            .getOrElse(pitestVersion());
    String pitestJunit5Version =
        providers
            .gradleProperty("cribro.pitestJunit5PluginVersion")
            .getOrElse(Versions.pitestJunit5Plugin());
    // Further PIT plugins, such as Arcmutate's Kotlin plugin, which filters the mutants Kotlin's
    // compiler-generated bytecode produces beyond what PIT's own Kotlin filter catches.
    List<String> pitestPlugins = coordinates(providers.gradleProperty("cribro.pitestPlugins"));
    pitest.defaultDependencies(
        deps -> {
          deps.add(
              project
                  .getDependencies()
                  .create("org.pitest:pitest-command-line:" + pitestVersion));
          // PIT finds JUnit 4 tests on its own and JUnit Platform tests only through this plugin.
          if (testTask.get().getOptions() instanceof JUnitPlatformOptions) {
            deps.add(
                project
                    .getDependencies()
                    .create("org.pitest:pitest-junit5-plugin:" + pitestJunit5Version));
          }
          for (String plugin : pitestPlugins) {
            deps.add(project.getDependencies().create(plugin));
          }
        });

    project
        .getTasks()
        .register(
            "pitestDiff",
            PitestDiffTask.class,
            task -> {
              task.setDescription("Runs PIT mutation testing over the lines this branch changed");
              task.setGroup(LifecycleBasePlugin.VERIFICATION_GROUP);
              // Read while pitestDiff is configured: a provider derived from the test task's
              // provider would make pitestDiff run the whole test suite first.
              Test t = testTask.get();
              Provider<PitestJvmArgs> jvmArgs =
                  providers.provider(
                      () -> PitestJvmArgs.of(t.getJvmArgs(), t.getSystemProperties(), t.getMaxHeapSize()));
              task.getTestJvmArgs().set(jvmArgs.map(PitestJvmArgs::value));
              task.getLeftOutJvmArgs().set(jvmArgs.map(PitestJvmArgs::leftOut));
              // PIT runs the tests on the JVM the `test` task runs them on.
              task.getJavaLauncher().set(t.getJavaLauncher());
              task.getPitestClasspath().from(pitest);
              // This jar carries the changed-lines interceptor and the mutants listener.
              task.getPitestClasspath().from(pluginJar());
              task.getChangedLinesFile().set(changedLines.flatMap(ChangedLinesTask::getOutputFile));
              task.getTestRuntimeClasspath().from(test.getRuntimeClasspath());
              // pitest-junit5-plugin leaves the launcher to the tests, and a Gradle 8 build may
              // rely on the launcher Gradle injects into the test JVM, which PIT never sees. PIT
              // starts the tests with this classpath and only its own plugins from its launch one.
              Configuration testRuntime =
                  project.getConfigurations().getByName(test.getRuntimeClasspathConfigurationName());
              task.getTestRuntimeClasspath()
                  .from(
                      project.files(
                          (Callable<Object>) () -> missingLauncher(project, testRuntime, t)));
              task.getMainClassesDirs().from(main.getOutput().getClassesDirs());
              task.getMainSourceDirs().from((Callable<Set<File>>) () -> sourceDirs(main));
              task.getReportDir()
                  .set(project.getLayout().getBuildDirectory().dir("reports/pitest-diff"));
              task.getRepositoryRoot().set(repositoryRoot);
              task.getTargetTests().set(providers.gradleProperty("cribro.pitestTargetTests"));
              task.getMutators().set(providers.gradleProperty("cribro.pitestMutators").orElse("DEFAULTS"));
              task.getThreads()
                  .set(
                      intProperty(
                          providers,
                          "cribro.pitestThreads",
                          Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
              task.getTimeoutConst().set(intProperty(providers, "cribro.pitestTimeoutConst", 4000));
              task.getMaxFiles().set(intProperty(providers, "cribro.pitestMaxFiles", 10));
              // A change usually lives in one or two files, so one file may take most of the lines.
              task.getMaxLinesPerFile().set(intProperty(providers, "cribro.pitestMaxLinesPerFile", 20));
              task.getMaxLines().set(intProperty(providers, "cribro.pitestMaxLines", 50));
              task.getShowAll().set(flag(providers, "cribro.pitestShowAll"));
              task.onlyIf(
                  "the branch changed lines of a class in the main sources of this module",
                  it -> {
                    try {
                      return ((PitestDiffTask) it).hasTargetClasses();
                    } catch (IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
            });
    return overrides;
  }

  /**
   * Returns the JUnit Platform launcher matching the engine on the test runtime classpath, or
   * nothing where the tests do not run on the JUnit Platform or bring a launcher of their own.
   */
  private static Object missingLauncher(Project project, Configuration testRuntime, Test test) {
    if (!(test.getOptions() instanceof JUnitPlatformOptions)) {
      return List.of();
    }
    String engineVersion = null;
    for (ResolvedComponentResult component :
        testRuntime.getIncoming().getResolutionResult().getAllComponents()) {
      ModuleVersionIdentifier id = component.getModuleVersion();
      if (id == null || !id.getGroup().equals("org.junit.platform")) {
        continue;
      }
      if (id.getName().equals("junit-platform-launcher")) {
        return List.of();
      }
      if (id.getName().equals("junit-platform-engine")) {
        engineVersion = id.getVersion();
      }
    }
    if (engineVersion == null) {
      return List.of();
    }
    return project
        .getConfigurations()
        .detachedConfiguration(
            project
                .getDependencies()
                .create("org.junit.platform:junit-platform-launcher:" + engineVersion));
  }

  /** Returns this plugin's jar, or its classes directory when it runs from a build. */
  private static File pluginJar() {
    try {
      return new File(
          ChangedLinesFile.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException("cannot locate the cribro plugin jar", e);
    }
  }

  /** Compares dotted numeric versions; a non-numeric part, as in a snapshot, counts as 0. */
  static int compareVersions(String a, String b) {
    String[] left = a.split("[.-]");
    String[] right = b.split("[.-]");
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      int diff = Integer.compare(part(left, i), part(right, i));
      if (diff != 0) {
        return diff;
      }
    }
    return 0;
  }

  private static int part(String[] parts, int index) {
    if (index >= parts.length) {
      return 0;
    }
    try {
      return Integer.parseInt(parts[index]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Returns the directory holding {@code .git}, which git prints the paths of a diff against, or
   * {@code start} where no directory above it holds one.
   */
  static File repositoryRoot(File start) {
    File root = Git.findRepositoryRoot(start);
    return root != null ? root : start.getAbsoluteFile();
  }

  private static Provider<Integer> intProperty(
      ProviderFactory providers, String name, int defaultValue) {
    return providers.gradleProperty(name).map(Integer::parseInt).orElse(defaultValue);
  }

  /** Returns the comma-separated dependency coordinates a property holds, or none where unset. */
  static List<String> coordinates(Provider<String> property) {
    List<String> coordinates = new ArrayList<>();
    for (String coordinate : property.getOrElse("").split(",")) {
      if (!coordinate.isBlank()) {
        coordinates.add(coordinate.trim());
      }
    }
    return coordinates;
  }

  /**
   * Returns the directories of the source set that hold sources, which is every directory of
   * {@code allSource} that holds no resources, and those of the {@code kotlin} source directory
   * set that the Kotlin plugin adds to the source set as an extension.
   *
   * <p>{@code java.srcDirs} alone would miss {@code src/main/kotlin}: the Kotlin plugin keeps its
   * sources in a directory set of its own, and a Kotlin file may lie in either.
   */
  static Set<File> sourceDirs(SourceSet sourceSet) {
    Set<File> dirs = new LinkedHashSet<>(sourceSet.getAllSource().getSrcDirs());
    if (sourceSet instanceof ExtensionAware) {
      Object kotlin = ((ExtensionAware) sourceSet).getExtensions().findByName("kotlin");
      if (kotlin instanceof SourceDirectorySet) {
        dirs.addAll(((SourceDirectorySet) kotlin).getSrcDirs());
      }
    }
    dirs.removeAll(sourceSet.getResources().getSrcDirs());
    return dirs;
  }

  /** A flag passed with no value, as in {@code -Pcribro.diffCoverageShowAll}, reads as true. */
  private static Provider<Boolean> flag(ProviderFactory providers, String name) {
    return providers
        .gradleProperty(name)
        .map(it -> it.isEmpty() || Boolean.parseBoolean(it))
        .orElse(false);
  }

  /**
   * Returns the patterns that narrow the test run, including those from {@code --tests}, which
   * Gradle keeps apart from the build script's and exposes only on its internal filter class.
   */
  private static List<String> testFilterPatterns(Test test) {
    List<String> patterns = new ArrayList<>(test.getFilter().getIncludePatterns());
    try {
      Method commandLine = test.getFilter().getClass().getMethod("getCommandLineIncludePatterns");
      Object value = commandLine.invoke(test.getFilter());
      if (value instanceof Collection<?>) {
        for (Object pattern : (Collection<?>) value) {
          patterns.add(String.valueOf(pattern));
        }
      }
    } catch (ReflectiveOperationException e) {
      // A Gradle version without the method keeps --tests patterns among the include patterns.
    }
    return patterns;
  }
}
