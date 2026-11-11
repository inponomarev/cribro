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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cribrodev.PitestRun;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** How cribro:pitest-diff carries surefire's test selection, classpath, and charset to PIT. */
class PitestDiffMojoTest {

  @TempDir Path testClasses;

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "x.Other, com.google.common.base.Strings?est",
        "*StringsTest",
        "**.StringsTest",
        "~.*\\.Strings.*"
      })
  void aGlobMatchesATestClassByItsFullName(String glob) throws IOException {
    testClass("com/google/common/base/StringsTest.class");

    assertTrue(PitestDiffMojo.hasMatchingTest(testClasses, glob, null));
  }

  /** A glob has to match the whole name, package included. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"StringsTest", "com.google.common.base.Strings"})
  void aGlobThatMatchesPartOfTheNameMatchesNoTestClass(String glob) throws IOException {
    testClass("com/google/common/base/StringsTest.class");

    assertFalse(PitestDiffMojo.hasMatchingTest(testClasses, glob, null));
  }

  /** Passes the tests and excludes to PIT as the goal does. */
  private static void select(
      PitestRun pit, String tests, SurefireConfiguration surefire, List<String> notes)
      throws IOException {
    PitestDiffMojo.addTestSelection(
        pit, tests, PitestDiffMojo.excludedTests(surefire, notes), surefire);
  }

  private void testClass(String path) throws IOException {
    Path file = testClasses.resolve(path);
    Files.createDirectories(file.getParent());
    Files.createFile(file);
  }

  /** PIT matches the whole class name, so an exclude must keep surefire's any-package prefix. */
  @Test
  void anExcludeOfAFileInAnyDirectoryMatchesTheClassInAnyPackage() {
    Pattern glob = PitestDiffMojo.pitGlob("**.SlowTest");
    assertTrue(glob.matcher("com.example.SlowTest").matches(), "**.SlowTest, com.example.SlowTest");
    assertTrue(glob.matcher("SlowTest").matches(), "**.SlowTest, SlowTest in the default package");
    assertFalse(
        glob.matcher("com.example.VerySlowTest").matches(), "**.SlowTest, com.example.VerySlowTest");
  }

  @Test
  void surefiresGroupsReachPitAsItsGroups() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.groups = "fast";
    surefire.excludedGroups = "slow,flaky";
    PitestRun pit = pit();

    select(pit, null, surefire, new ArrayList<>());

    List<String> arguments = pit.arguments();
    assertEquals("fast", arguments.get(arguments.indexOf("--includedGroups") + 1));
    assertEquals("slow,flaky", arguments.get(arguments.indexOf("--excludedGroups") + 1));
  }

  private PitestRun pit() {
    return new PitestRun().reportDir(testClasses.resolve("report").toFile()).sourceEncoding("UTF-8");
  }

  private static Artifact artifact(String artifactId, String scope, String type, String classifier) {
    return new DefaultArtifact(
        "org.example", artifactId, "1.0", scope, type, classifier, new DefaultArtifactHandler(type));
  }

  /** Surefire's short patterns match a part against several coordinates, not by position. */
  @Test
  void theTestsClasspathLeavesOutWhatSurefiresFiltersLeaveOut() {
    Artifact lib = artifact("lib", "test", "jar", null);
    Artifact tests = artifact("lib", "test", "test-jar", "tests");
    Artifact runtime = artifact("driver", "runtime", "jar", null);
    Set<Artifact> artifacts = new LinkedHashSet<>(List.of(lib, tests, runtime));

    SurefireConfiguration byClassifier = new SurefireConfiguration();
    byClassifier.classpathDependencyExcludes = List.of("*:*:tests");
    SurefireConfiguration byCoordinates = new SurefireConfiguration();
    byCoordinates.classpathDependencyExcludes = List.of("org.example:lib:test-jar:tests");
    SurefireConfiguration byScope = new SurefireConfiguration();
    byScope.classpathDependencyScopeExclude = "runtime";

    assertAll(
        () -> assertEquals(Set.of(tests), PitestDiffMojo.excluded(artifacts, byClassifier), "*:*:tests"),
        () ->
            assertEquals(
                Set.of(tests),
                PitestDiffMojo.excluded(artifacts, byCoordinates),
                "org.example:lib:test-jar:tests"),
        () -> assertEquals(Set.of(runtime), PitestDiffMojo.excluded(artifacts, byScope), "runtime scope"));
  }

  /** PIT's own options come after the main class, so a system property has to come before it. */
  @Test
  void theCommandStartsPitWithEverySystemPropertyItIsGiven() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("cribro.pitest.changedLines", "/tmp/changed-lines.txt");
    properties.put("file.encoding", "UTF-8");

    List<String> command =
        PitestDiffMojo.command("java", List.of(), properties, List.of("--reportDir", "r"));

    assertEquals(
        List.of(
            "java",
            "-cp",
            "",
            "-Dcribro.pitest.changedLines=/tmp/changed-lines.txt",
            "-Dfile.encoding=UTF-8",
            PitestRun.MAIN_CLASS,
            "--reportDir",
            "r"),
        command);
  }

  @Test
  void pitReadsTheSourcesInTheProjectsSourceEncoding() {
    Properties properties = new Properties();
    properties.setProperty("project.build.sourceEncoding", "ISO-8859-1");

    assertEquals("ISO-8859-1", PitestDiffMojo.sourceEncoding(properties));
  }

  /** Maven's compiler reads the sources in the platform's charset where the project names none. */
  @Test
  void pitReadsTheSourcesInThePlatformsCharsetWithoutASourceEncoding() {
    assertEquals(
        Charset.defaultCharset().name(), PitestDiffMojo.sourceEncoding(new Properties()));
  }

  /** Surefire's default includes leave out a class such as FooIT, which only failsafe runs. */
  @Test
  void pitRunsTheTestsSurefiresDefaultIncludesSelect() throws IOException {
    assertEquals(
        "~(.*\\.)?Test[^.]*,**.*Test,**.*Tests,**.*TestCase",
        PitestDiffMojo.testSelection(null, new SurefireConfiguration(), new ArrayList<>()));
  }

  @Test
  void cribroPitestTargetTestsWinsOverSurefiresIncludes() throws IOException {
    assertEquals(
        "**.NextTest",
        PitestDiffMojo.testSelection("**.NextTest", new SurefireConfiguration(), new ArrayList<>()));
  }

  /** Running the mapped includes alone would leave out the tests the other one selects. */
  @Test
  void anIncludePitCannotExpressLetsPitRunEveryTest() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of("**/*Test.java", "%regex[.*Spec.*]");
    List<String> notes = new ArrayList<>();

    String tests = PitestDiffMojo.testSelection(null, surefire, notes);

    assertAll(
        () -> assertNull(tests, "the tests PIT runs, null for all"),
        () -> assertEquals(1, notes.size(), notes::toString),
        () -> assertTrue(notes.get(0).endsWith(": %regex[.*Spec.*]"), notes.get(0)));
  }

  /** Maven passes an empty list where the POM sets no includes, and surefire then runs its defaults. */
  @Test
  void anEmptyIncludesListSelectsSurefiresDefaults() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of();

    assertEquals(
        "~(.*\\.)?Test[^.]*,**.*Test,**.*Tests,**.*TestCase",
        PitestDiffMojo.testSelection(null, surefire, new ArrayList<>()));
  }

  /** A module whose compiled tests surefire never runs is skipped rather than run with no test. */
  @Test
  void aModuleWithOnlyIntegrationTestsHasNoTestTheDefaultIncludesSelect() throws IOException {
    testClass("com/example/FooIT.class");
    String tests = PitestDiffMojo.testSelection(null, new SurefireConfiguration(), new ArrayList<>());

    assertFalse(PitestDiffMojo.hasMatchingTest(testClasses, tests, null), tests);
  }

  @Test
  void theSelectedTestsReachPitAsItsTargetTests() throws IOException {
    PitestRun pit = pit();

    select(pit, "**.*Test", new SurefireConfiguration(), new ArrayList<>());

    List<String> arguments = pit.arguments();
    assertEquals("**.*Test", arguments.get(arguments.indexOf("--targetTests") + 1));
  }

  @Test
  void noSelectionLetsPitRunEveryTest() throws IOException {
    PitestRun pit = pit();

    select(pit, null, new SurefireConfiguration(), new ArrayList<>());

    List<String> arguments = pit.arguments();
    assertEquals(PitestRun.ALL_TESTS, arguments.get(arguments.indexOf("--targetTests") + 1));
  }

  /** Surefire excludes a file in any directory, and PIT a class in any package. */
  @Test
  void surefiresExcludesReachPitAsClassGlobs() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludes = List.of("**/SlowTest.java", "**/*IT.java", "com/example/Other.java");
    List<String> notes = new ArrayList<>();
    PitestRun pit = pit();

    select(pit, null, surefire, notes);

    List<String> arguments = pit.arguments();
    assertEquals(
        "**.SlowTest,**.*IT",
        arguments.get(arguments.indexOf("--excludedTestClasses") + 1));
    assertEquals(1, notes.size(), notes::toString);
    assertTrue(notes.get(0).endsWith(": com/example/Other.java"), notes.get(0));
  }

  /** Surefire runs the includes the POM lists and those of its includesFile together. */
  @Test
  void theIncludesFileAddsToTheIncludes() throws IOException {
    Path file = testClasses.resolve("includes.txt");
    Files.writeString(file, "# specs\n\n  **/*Spec.java  \n");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of("**/*Test.java");
    surefire.includesFile = file.toFile();

    assertEquals("**.*Test,**.*Spec", PitestDiffMojo.testSelection(null, surefire, new ArrayList<>()));
  }

  /** Surefire's defaults apply only where neither the POM nor the file lists an include. */
  @Test
  void anIncludesFileAloneReplacesSurefiresDefaults() throws IOException {
    Path file = testClasses.resolve("includes.txt");
    Files.writeString(file, "**/*Spec.java\n");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includesFile = file.toFile();

    assertEquals("**.*Spec", PitestDiffMojo.testSelection(null, surefire, new ArrayList<>()));
  }

  /** Surefire fails its own run on an includesFile that does not exist. */
  @Test
  void aMissingIncludesFileFailsTheGoal() {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includesFile = testClasses.resolve("absent.txt").toFile();

    IOException thrown =
        assertThrows(
            IOException.class, () -> PitestDiffMojo.testSelection(null, surefire, new ArrayList<>()));
    assertTrue(thrown.getMessage().startsWith("cannot read surefire's includesFile "), thrown.getMessage());
  }

  @Test
  void theExcludesFileAddsToTheExcludes() throws IOException {
    Path file = testClasses.resolve("excludes.txt");
    Files.writeString(file, "**/*IT.java\n");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludes = List.of("**/SlowTest.java");
    surefire.excludesFile = file.toFile();
    PitestRun pit = pit();

    select(pit, null, surefire, new ArrayList<>());

    List<String> arguments = pit.arguments();
    assertEquals(
        "**.SlowTest,**.*IT", arguments.get(arguments.indexOf("--excludedTestClasses") + 1));
  }

  /** An excludesFile alone replaces surefire's default, which then reaches PIT only where listed. */
  @Test
  void anExcludesFileAloneReplacesSurefiresDefault() throws IOException {
    Path file = testClasses.resolve("excludes.txt");
    Files.writeString(file, "**/*IT.java\n**/*$*\n");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludesFile = file.toFile();
    PitestRun pit = pit();

    select(pit, null, surefire, new ArrayList<>());

    List<String> arguments = pit.arguments();
    assertEquals("**.*IT,~(.*\\.)?[^.]*\\$[^.]*", arguments.get(arguments.indexOf("--excludedTestClasses") + 1));
  }

  /** Without excludes surefire leaves out every nested class, such as a JUnit 4 fixture. */
  @Test
  void noExcludesLeaveOutNestedClassesAsSurefireDoes() throws IOException {
    List<String> notes = new ArrayList<>();
    PitestRun pit = pit();

    select(pit, null, new SurefireConfiguration(), notes);

    List<String> arguments = pit.arguments();
    assertAll(
        () ->
            assertEquals(
                "~(.*\\.)?[^.]*\\$[^.]*",
                arguments.get(arguments.indexOf("--excludedTestClasses") + 1),
                "excluded"),
        () -> assertEquals(List.of(), notes, "notes"));
  }

  /** PIT splits its patterns at commas, and no simple name holds a dot. */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"**/A,B.java", "**/Foo.Bar.java"})
  void aNameWithACommaOrADotIsNotMapped(String exclude) throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludes = List.of(exclude);
    List<String> notes = new ArrayList<>();
    PitestRun pit = pit();

    select(pit, null, surefire, notes);

    assertAll(
        () -> assertFalse(pit.arguments().contains("--excludedTestClasses"), "excludedTestClasses"),
        () -> assertEquals(1, notes.size(), notes::toString),
        () -> assertTrue(notes.get(0).endsWith(": " + exclude), notes.get(0)));
  }

  /** An include PIT cannot express leaves it every test class, as without surefire's includes. */
  @Test
  void anIncludeWithADotLetsPitRunEveryTest() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of("**/*Test.java", "**/Foo.Bar.java");
    List<String> notes = new ArrayList<>();

    String tests = PitestDiffMojo.testSelection(null, surefire, notes);

    assertAll(
        () -> assertNull(tests, "the tests PIT runs, null for all"),
        () -> assertEquals(1, notes.size(), notes::toString),
        () -> assertTrue(notes.get(0).endsWith(": **/Foo.Bar.java"), notes.get(0)));
  }

  /** Surefire leaves out a nested class by the $ in its name, not by one in its package. */
  @Test
  void aDollarInThePackageKeepsATopLevelTest() throws IOException {
    testClass("com/$generated/FooTest.class");
    SurefireConfiguration surefire = new SurefireConfiguration();
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());
    String excluded = PitestDiffMojo.excludedTests(surefire, new ArrayList<>());

    assertTrue(PitestDiffMojo.hasMatchingTest(testClasses, tests, excluded), excluded);
  }

  /** Surefire's * and ? stop at a directory, where PIT's glob would cross a package dot. */
  @ParameterizedTest(name = "{0} matches {1}")
  @CsvSource({
    "**/Foo*.java, com.example.FooTest, true",
    "**/Foo*.java, com.Foopkg.BarTest, false",
    "**/Foo*Test.java, com.example.FooBarTest, true",
    "**/Foo*Test.java, com.Foo.BarTest, false",
    "**/Test?Case.java, com.example.Test1Case, true",
    "**/Test?Case.java, com.Test.Case, false",
    "**/*Test.java, com.example.FooTest, true",
    "**/*Test.java, com.example.FooTests, false",
    "**/Foo-Bar.java, com.example.Foo-Bar, true",
    "**/Foo-Bar.java, com.example.FooxBar, false",
    "**/Foo-*.java, com.example.Foo-Bar, true",
    "**/Foo-*.java, com.example.FooxBar, false",
    "**/C++*.java, com.example.C++Test, true",
    "**/C++*.java, com.example.CTest, false",
  })
  void aWildcardStopsAtThePackageDotAsSurefiresStopsAtADirectory(
      String include, String className, boolean matches) throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of(include);
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());

    assertEquals(matches, PitestDiffMojo.pitGlob(tests).matcher(className).matches(), tests);
  }

  /** PIT drops an excluded class from its target tests, so a module left with none is skipped. */
  @Test
  void aModuleWhoseOnlyTestIsExcludedHasNoTest() throws IOException {
    testClass("com/example/SlowTest.class");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludes = List.of("**/SlowTest.java");
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());
    String excluded = PitestDiffMojo.excludedTests(surefire, new ArrayList<>());

    assertFalse(PitestDiffMojo.hasMatchingTest(testClasses, tests, excluded), excluded);
  }

  @Test
  void aWildcardExcludeReachesPitAsARegularExpression() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludes = List.of("**/Slow*Test.java");
    PitestRun pit = pit();

    select(pit, null, surefire, new ArrayList<>());

    List<String> arguments = pit.arguments();
    assertEquals(
        "~(.*\\.)?Slow[^.]*Test",
        arguments.get(arguments.indexOf("--excludedTestClasses") + 1));
  }

  /** Surefire's default exclude leaves out nested classes, such as the cases of a JUnit 4 suite. */
  @Test
  void aModuleWhoseOnlyTestsAreNestedHasNoTest() throws IOException {
    testClass("com/example/SuiteTest$CaseTest.class");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of("**/*CaseTest.java");
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());
    String excluded = PitestDiffMojo.excludedTests(surefire, new ArrayList<>());

    assertFalse(PitestDiffMojo.hasMatchingTest(testClasses, tests, excluded), excluded);
  }

  @Test
  void anExcludeInTheExcludesFileLeavesAModuleWithNoTest() throws IOException {
    testClass("com/example/SlowTest.class");
    Path file = testClasses.resolve("excludes.txt");
    Files.writeString(file, "**/SlowTest.java\n");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludesFile = file.toFile();
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());
    String excluded = PitestDiffMojo.excludedTests(surefire, new ArrayList<>());

    assertFalse(PitestDiffMojo.hasMatchingTest(testClasses, tests, excluded), excluded);
  }

  @Test
  void aModuleWithATestTheExcludesLeaveHasATest() throws IOException {
    testClass("com/example/SlowTest.class");
    testClass("com/example/FastTest.class");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludes = List.of("**/SlowTest.java");
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());
    String excluded = PitestDiffMojo.excludedTests(surefire, new ArrayList<>());

    assertTrue(PitestDiffMojo.hasMatchingTest(testClasses, tests, excluded), excluded);
  }

  /** Includes PIT cannot apply leave it every class, from which the excludes still take. */
  @Test
  void excludesApplyWhereTheIncludesDoNotMap() throws IOException {
    testClass("com/example/SlowSpec.class");
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.includes = List.of("%regex[.*Spec.*]");
    surefire.excludes = List.of("**/SlowSpec.java");
    String tests = PitestDiffMojo.testSelection(null, surefire, new ArrayList<>());
    String excluded = PitestDiffMojo.excludedTests(surefire, new ArrayList<>());

    assertAll(
        () -> assertNull(tests, "tests"),
        () -> assertFalse(PitestDiffMojo.hasMatchingTest(testClasses, tests, excluded), excluded));
  }

  /** Surefire gives each fork its number, and PIT's minions get that of the first fork. */
  @Test
  void theForkNumberIsOneInTheArgLine() {
    List<String> arguments =
        PitestDiffMojo.argLineArguments(
            "-Dport=80${surefire.forkNumber} -Ddir=t${surefire.threadNumber}",
            new Properties(),
            new ArrayList<>());

    assertEquals(List.of("-Dport=801", "-Ddir=t1"), arguments);
  }

  @Test
  void theForkNumberIsOneInSystemProperties() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.systemPropertyVariables.put("port", "80${surefire.forkNumber}");
    Properties user = new Properties();
    user.setProperty("dir", "t${surefire.threadNumber}");

    Map<String, String> properties =
        PitestDiffMojo.systemProperties(surefire, user, testClasses.toFile(), ".");

    assertAll(
        () -> assertEquals("801", properties.get("port"), "port"),
        () -> assertEquals("t1", properties.get("dir"), "dir"));
  }

  @Test
  void theForkNumberIsOneInTheEnvironmentAndTheWorkingDirectory() {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.environmentVariables.put("PORT", "80${surefire.forkNumber}");
    surefire.workingDirectory = testClasses.resolve("fork-${surefire.forkNumber}").toFile();

    ProcessBuilder builder =
        PitestDiffMojo.pitProcess(
            List.of("java"),
            surefire,
            testClasses.toFile(),
            testClasses.resolve("pit.log").toFile());

    assertAll(
        () -> assertEquals("801", builder.environment().get("PORT"), "PORT"),
        () ->
            assertEquals(
                testClasses.resolve("fork-1").toFile(), builder.directory(), "working directory"));
  }

  /** Surefire passes an empty element as an empty string, and the process environment refuses null. */
  @Test
  void anEmptyEnvironmentVariableReachesPitAsAnEmptyString() {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.environmentVariables.put("JAVA_TOOL_OPTIONS", null);
    surefire.environmentVariables.put("CRIBRO_TEST_VALUE", "1");

    Map<String, String> environment =
        PitestDiffMojo.pitProcess(List.of("java"), surefire, testClasses.toFile(), log())
            .environment();

    assertEquals("", environment.get("JAVA_TOOL_OPTIONS"), "JAVA_TOOL_OPTIONS");
    assertEquals("1", environment.get("CRIBRO_TEST_VALUE"), "CRIBRO_TEST_VALUE");
  }

  @Test
  void anExcludedEnvironmentVariableDoesNotReachPit() {
    String inherited = System.getenv().keySet().iterator().next();
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.excludedEnvironmentVariables = List.of(inherited);

    Map<String, String> environment =
        PitestDiffMojo.pitProcess(List.of("java"), surefire, testClasses.toFile(), log())
            .environment();

    assertFalse(environment.containsKey(inherited), inherited);
  }

  @Test
  void pitRunsInSurefiresWorkingDirectory() {
    SurefireConfiguration surefire = new SurefireConfiguration();
    surefire.workingDirectory = testClasses.resolve("work").toFile();

    ProcessBuilder builder =
        PitestDiffMojo.pitProcess(List.of("java"), surefire, testClasses.toFile(), log());

    assertEquals(surefire.workingDirectory, builder.directory());
  }

  @Test
  void pitRunsInTheModuleDirectoryWithoutAWorkingDirectory() {
    ProcessBuilder builder =
        PitestDiffMojo.pitProcess(
            List.of("java"), new SurefireConfiguration(), testClasses.toFile(), log());

    assertEquals(testClasses.toFile(), builder.directory());
  }

  private File log() {
    return testClasses.resolve("pit.log").toFile();
  }

  /**
   * Each source overrides the ones before it: systemProperties, systemPropertiesFile,
   * systemPropertyVariables, the user's -D properties, and then basedir and localRepository.
   * Property a is set by the first source only, b by the first two, and so on.
   */
  @Test
  void theTestsSystemPropertiesFollowSurefiresPrecedence() throws IOException {
    SurefireConfiguration surefire = precedence();
    Properties user = new Properties();
    user.setProperty("d", "user");
    user.setProperty("basedir", "user");
    user.setProperty("localRepository", "user");
    user.setProperty("cribro.diffBase", "origin/main");

    Map<String, String> properties =
        PitestDiffMojo.systemProperties(surefire, user, testClasses.toFile(), "/m2/repository");

    assertEquals(
        Map.of(
            "a", "systemProperties",
            "b", "systemPropertiesFile",
            "c", "systemPropertyVariables",
            "d", "user",
            "basedir", testClasses.toFile().getAbsolutePath(),
            "localRepository", "/m2/repository"),
        properties);
  }

  @Test
  void theUsersPropertiesStayOutWhereSurefireDoesNotPromoteThem() throws IOException {
    SurefireConfiguration surefire = precedence();
    surefire.promoteUserPropertiesToSystemProperties = false;
    Properties user = new Properties();
    user.setProperty("d", "user");

    Map<String, String> properties =
        PitestDiffMojo.systemProperties(surefire, user, testClasses.toFile(), "/m2/repository");

    assertEquals("systemPropertyVariables", properties.get("d"), "d");
  }

  private SurefireConfiguration precedence() throws IOException {
    SurefireConfiguration surefire = new SurefireConfiguration();
    for (String name : List.of("a", "b", "c", "d")) {
      surefire.systemProperties.setProperty(name, "systemProperties");
    }
    Path file = testClasses.resolve("system.properties");
    Files.writeString(file, "b=systemPropertiesFile\nc=systemPropertiesFile\nd=systemPropertiesFile\n");
    surefire.systemPropertiesFile = file.toFile();
    surefire.systemPropertyVariables.put("c", "systemPropertyVariables");
    surefire.systemPropertyVariables.put("d", "systemPropertyVariables");
    return surefire;
  }

  /** PIT attaches its own coverage, and an argument naming an undefined property would fail it. */
  @Test
  void theArgLineReachesPitWithoutJacocoAgentsOrUndefinedProperties() {
    Properties project = new Properties();
    project.setProperty("heap", "256m");
    List<String> unresolved = new ArrayList<>();

    List<String> arguments =
        PitestDiffMojo.argLineArguments(
            "-javaagent:/repo/jacocoagent.jar=destfile=a.exec -Xmx@{heap} -Dx=1 @{absent}",
            project,
            unresolved);

    assertAll(
        () -> assertEquals(List.of("-Xmx256m", "-Dx=1"), arguments, "arguments"),
        () -> assertEquals(List.of("@{absent}"), unresolved, "unresolved"));
  }

  private MavenProject module(String artifactId) throws IOException {
    Path pom = testClasses.resolve(artifactId).resolve("pom.xml");
    Files.createDirectories(pom.getParent());
    Files.createFile(pom);
    MavenProject project = new MavenProject();
    project.setGroupId("demo");
    project.setArtifactId(artifactId);
    project.setFile(pom.toFile());
    return project;
  }

  /** Maven matches a -pl entry by its artifactId, with or without the groupId, or by its path. */
  @ParameterizedTest
  @ValueSource(strings = {":lib", "demo:lib", "lib", "lib/pom.xml"})
  void aModuleThatMinusPlNamesRunsPitUnderMinusAm(String selector) throws IOException {
    MavenProject lib = module("lib");

    assertFalse(
        PitestDiffMojo.addedByAlsoMake(
            MavenExecutionRequest.REACTOR_MAKE_UPSTREAM,
            List.of(":lib-tests", selector),
            testClasses.toFile(),
            lib));
  }

  @ParameterizedTest
  @ValueSource(strings = {MavenExecutionRequest.REACTOR_MAKE_UPSTREAM, MavenExecutionRequest.REACTOR_MAKE_BOTH})
  void aModuleThatOnlyMinusAmAddedRunsNoPit(String makeBehavior) throws IOException {
    MavenProject lib = module("lib");

    assertTrue(
        PitestDiffMojo.addedByAlsoMake(
            makeBehavior, List.of(":lib-tests", "other"), testClasses.toFile(), lib));
  }

  /** Without -am, -pl builds only the modules it names, and each of them runs PIT. */
  @Test
  void everyModuleRunsPitWithoutMinusAm() throws IOException {
    MavenProject lib = module("lib");

    assertFalse(
        PitestDiffMojo.addedByAlsoMake(
            MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM,
            List.of(":lib-tests"),
            testClasses.toFile(),
            lib));
  }

  @Test
  void everyModuleRunsPitWithoutMinusPl() throws IOException {
    MavenProject lib = module("lib");

    assertFalse(
        PitestDiffMojo.addedByAlsoMake(
            MavenExecutionRequest.REACTOR_MAKE_UPSTREAM, List.of(), testClasses.toFile(), lib));
  }

  /** The dependency's tests already run PIT on its changes, so the dependent leaves them out. */
  @Test
  void pitMutatesOnlyTheReactorDependenciesWithoutTestsOfTheirOwn() throws IOException {
    MavenProject untested = module("untested");
    MavenProject tested = module("tested");
    Path test = testClasses.resolve("tested/src/test/java/TestedTest.java");
    Files.createDirectories(test.getParent());
    Files.createFile(test);
    tested.addTestCompileSourceRoot(test.getParent().toString());
    MavenProject fixtures = module("fixtures");
    MavenProject dependent = module("dependent");
    dependent.setArtifacts(
        new LinkedHashSet<>(
            List.of(
                demoArtifact("untested", null),
                demoArtifact("tested", null),
                demoArtifact("fixtures", "tests"))));

    assertEquals(
        List.of("dependent", "untested"),
        PitestDiffMojo.mutatedProjects(dependent, List.of(untested, tested, fixtures, dependent))
            .stream()
            .map(MavenProject::getArtifactId)
            .collect(Collectors.toList()));
  }

  /** A module with Kotlin-only tests under kotlin-maven-plugin's test-compile sourceDir has tests. */
  @Test
  void aReactorDependencyWithKotlinOnlyTestsIsNotMutatedByItsDependent() throws Exception {
    MavenProject tested = module("tested");
    Path test = testClasses.resolve("tested/src/test/kotlin/TestedTest.kt");
    Files.createDirectories(test.getParent());
    Files.createFile(test);
    tested.getModel().setBuild(new Build());
    Plugin kotlin = new Plugin();
    kotlin.setGroupId("org.jetbrains.kotlin");
    kotlin.setArtifactId("kotlin-maven-plugin");
    PluginExecution execution = new PluginExecution();
    execution.setId("test-compile");
    execution.setGoals(List.of("test-compile"));
    execution.setConfiguration(
        Xpp3DomBuilder.build(
            new java.io.StringReader(
                "<configuration><sourceDirs><sourceDir>"
                    + test.getParent()
                    + "</sourceDir></sourceDirs></configuration>")));
    kotlin.addExecution(execution);
    tested.getBuild().addPlugin(kotlin);
    MavenProject dependent = module("dependent");
    dependent.setArtifacts(new LinkedHashSet<>(List.of(demoArtifact("tested", null))));

    assertEquals(
        List.of("dependent"),
        PitestDiffMojo.mutatedProjects(dependent, List.of(tested, dependent)).stream()
            .map(MavenProject::getArtifactId)
            .collect(Collectors.toList()));
  }

  private static Artifact demoArtifact(String artifactId, String classifier) {
    String type = classifier == null ? "jar" : "test-jar";
    return new DefaultArtifact(
        "demo", artifactId, "1.0", "test", type, classifier, new DefaultArtifactHandler(type));
  }

  /** Stands in for a PIT run that does not end by itself. */
  static final class Sleep {
    public static void main(String[] args) throws InterruptedException {
      Thread.sleep(600_000);
    }
  }

  /** Stands in for a PIT minion that keeps running for a while after it is asked to stop. */
  static final class SlowToStop {
    public static void main(String[] args) throws InterruptedException {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      Thread.sleep(600_000);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }));
      System.out.println("ready");
      Thread.sleep(600_000);
    }
  }

  /** Stands in for PIT: starts a minion and prints its pid once the minion is ready to be stopped. */
  static final class WithMinion {
    public static void main(String[] args) throws Exception {
      Process minion =
          new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), SlowToStop.class.getName())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      new BufferedReader(new InputStreamReader(minion.getInputStream(), UTF_8)).readLine();
      System.out.println(minion.pid());
      Thread.sleep(600_000);
    }
  }

  private static String java() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /** PIT exits on the first signal, and a minion that outlives it would be out of reach. */
  @Test
  void aMinionThatOutlivesTheGracePeriodIsKilled() throws Exception {
    Process process =
        new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), WithMinion.class.getName())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    long pid =
        Long.parseLong(
            new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8)).readLine());
    ProcessHandle minion = ProcessHandle.of(pid).orElseThrow();
    try {
      Thread.currentThread().interrupt();

      assertThrows(
          InterruptedException.class, () -> PitestDiffMojo.waitFor(process, Duration.ofMillis(500)));

      assertTrue(
          minion.onExit().thenApply(handle -> true).completeOnTimeout(false, 10, TimeUnit.SECONDS).get(),
          "the minion is alive 10 s after the interrupted wait");
    } finally {
      minion.destroyForcibly();
      process.destroyForcibly();
    }
  }

  /** A PIT JVM left running keeps writing into the report directory the next run recreates. */
  @Test
  void anInterruptedWaitStopsPit() throws IOException {
    Process process =
        new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), Sleep.class.getName())
            .start();
    Thread.currentThread().interrupt();

    assertThrows(InterruptedException.class, () -> PitestDiffMojo.waitFor(process));

    assertFalse(process.isAlive(), "PIT is alive after the interrupted wait");
  }
}
