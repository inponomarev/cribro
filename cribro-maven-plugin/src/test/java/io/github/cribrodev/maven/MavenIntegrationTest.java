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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs a real Maven on small projects in a git repository, with cribro loaded each way a build can
 * load it.
 *
 * <p>Maven resolves cribro from the repository the build publishes to, and everything else from
 * Maven Central.
 */
class MavenIntegrationTest {

  private static final String VERSION = System.getProperty("cribro.it.version");

  /** A stable Kotlin release whose Maven plugin runs on the JDKs CI runs the tests on. */
  private static final String KOTLIN_VERSION = "2.4.21";

  @TempDir Path directory;

  /** A module whose tests cover one branch of the changed method and leave a method unexecuted. */
  private static final String CHANGED_CLASS =
      String.join(
          "\n",
          "package org.apache.commons.lang3;",
          "",
          "public class ArrayUtils {",
          "  public static int abs(int x) {",
          "    if (x < 0) {",
          "      return -x;",
          "    }",
          "    return x;",
          "  }",
          "",
          "  public static int next(int x) {",
          "    return x + 1;",
          "  }",
          "}",
          "");

  private static final String TEST_CLASS =
      String.join(
          "\n",
          "package org.apache.commons.lang3;",
          "",
          "import static org.junit.jupiter.api.Assertions.assertTrue;",
          "",
          "import org.junit.jupiter.api.Test;",
          "",
          "class ArrayUtilsTest {",
          "  @Test",
          "  void absIsNotNegative() {",
          "    assertTrue(ArrayUtils.abs(-3) >= 0);",
          "  }",
          "}",
          "");

  @Test
  void aBuildThatLoadsCribroAsAnExtensionReportsCoverageBeforeItsSummary() throws Exception {
    Path project = singleModule();

    String test = maven(project, 0, "test");

    assertBefore(
        test,
        String.join(
            "\n",
            "  src/main/java/org/apache/commons/lang3/ArrayUtils.java",
            "    2/3 changed lines executed, 1/2 branches covered",
            "    uncovered changed code:",
            "      5: 1 of 2 branches covered: if (x < 0) {",
            "      12: not executed: return x + 1;"),
        "BUILD SUCCESS");
  }

  /**
   * The changed class shares its name with a class of commons-lang3, which PIT runs on, so the
   * mutation report only names its lines where PIT keeps its own classpath away from the tests.
   */
  @Test
  void pitReportsTheMutantsOnTheChangedLinesThatNoTestReaches() throws Exception {
    Path project = singleModule();

    String pit = maven(project, 0, "test-compile", "cribro:pitest-diff");

    // The tests surefire's default includes select, which are the ones PIT ran.
    assertContains(pit, ", tests matching ~(.*\\.)?Test[^.]*,**.*Test,**.*Tests,**.*TestCase");
    assertContains(
        pit,
        String.join(
            "\n",
            "    12: return x + 1;",
            "        no test reaches it: Replaced integer addition with subtraction, in next"));
  }

  @Test
  void theGuideListsTheModulesWithTests() throws Exception {
    Path project = singleModule();

    String guide = maven(project, 0, "-q", "cribro:guide");

    assertTrue(
        guide.startsWith("cribro " + VERSION + "\n\nModules with tests: :single\n"),
        () -> "the head of the guide:\n" + guide);
  }

  /** The build's copy reports; the one -Dmaven.ext.class.path loads stands down. */
  @Test
  void aBuildThatLoadsCribroTwiceReportsOnce() throws Exception {
    Path project = singleModule();

    String both = maven(project, 0, extension(), "test");

    assertEquals(
        1,
        both.split("Diff coverage against", -1).length - 1,
        () -> "reports in a build that loads cribro twice:\n" + both);
  }

  /** Writes a module that loads cribro from .mvn/extensions.xml, with a change to its tested class. */
  private Path singleModule() throws IOException, InterruptedException {
    Path project = directory.resolve("single");
    write(project.resolve(".mvn/extensions.xml"), extensionsXml());
    write(project.resolve("pom.xml"), pom("single", "", "<argLine>-Xmx256m</argLine>"));
    write(project.resolve("src/main/java/org/apache/commons/lang3/ArrayUtils.java"), "package org.apache.commons.lang3;\n\npublic class ArrayUtils {\n  public static int abs(int x) {\n    return x;\n  }\n}\n");
    write(project.resolve("src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java"), TEST_CLASS.replace("abs(-3) >= 0", "abs(3) >= 0"));
    commitAsBase(project);
    write(project.resolve("src/main/java/org/apache/commons/lang3/ArrayUtils.java"), CHANGED_CLASS);
    write(project.resolve("src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java"), TEST_CLASS);
    return project;
  }

  /**
   * A Kotlin module keeps its sources where kotlin-maven-plugin's {@code sourceDirs} say, and a
   * Kotlin file may declare a package its directory does not follow; the changed file here lies
   * at the root of {@code src/main/kotlin}. Its one test covers one branch of {@code max} and never
   * calls {@code next}.
   */
  @Test
  void aKotlinModuleIsMeasuredByThePackageEachFileDeclares() throws Exception {
    Path project = kotlinModule();

    String test = maven(project, 0, "test");
    String pit = maven(project, 0, "test-compile", "cribro:pitest-diff");

    assertAll(
        () ->
            assertBefore(
                test,
                String.join(
                    "\n",
                    "  src/main/kotlin/Calc.kt",
                    "    2/4 changed lines executed, 1/2 branches covered",
                    "    uncovered changed code:",
                    "      4: 1 of 2 branches covered: if (a > b) {",
                    "      7: not executed: return b",
                    "      11: not executed: return a + 1"),
                "BUILD SUCCESS"),
        () -> assertContains(pit, "  src/main/kotlin/Calc.kt\n"),
        () -> assertContains(pit, "survived: changed conditional boundary, in max"),
        () ->
            assertContains(
                pit, "no test reaches it: Replaced integer addition with subtraction, in next"));
  }

  /** Writes a Kotlin module that loads cribro from .mvn/extensions.xml, with a change to Calc.kt. */
  private Path kotlinModule() throws IOException, InterruptedException {
    Path project = directory.resolve("kotlin");
    write(project.resolve(".mvn/extensions.xml"), extensionsXml());
    write(project.resolve("pom.xml"), kotlinPom());
    write(project.resolve("src/main/kotlin/Calc.kt"), "package demo.math\n\nfun max(a: Int, b: Int): Int {\n    return a\n}\n");
    write(
        project.resolve("src/test/kotlin/demo/math/CalcTest.kt"),
        String.join(
            "\n",
            "package demo.math",
            "",
            "import org.junit.jupiter.api.Assertions.assertEquals",
            "import org.junit.jupiter.api.Test",
            "",
            "class CalcTest {",
            "    @Test",
            "    fun maxOfTwoAndOneIsTwo() {",
            "        assertEquals(2, max(2, 1))",
            "    }",
            "}",
            ""));
    commitAsBase(project);
    write(
        project.resolve("src/main/kotlin/Calc.kt"),
        String.join(
            "\n",
            "package demo.math",
            "",
            "fun max(a: Int, b: Int): Int {",
            "    if (a > b) {",
            "        return a",
            "    }",
            "    return b",
            "}",
            "",
            "fun next(a: Int): Int {",
            "    return a + 1",
            "}",
            ""));
    return project;
  }

  private static String kotlinPom() {
    return String.join(
        "\n",
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
        "  <modelVersion>4.0.0</modelVersion>",
        "  <groupId>demo</groupId><artifactId>kotlin</artifactId><version>1.0</version>",
        "  <properties>",
        "    <kotlin.version>" + KOTLIN_VERSION + "</kotlin.version>",
        "    <kotlin.compiler.jvmTarget>11</kotlin.compiler.jvmTarget>",
        "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
        "  </properties>",
        "  <dependencies>",
        "    <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId>",
        "      <version>${kotlin.version}</version></dependency>",
        "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>",
        "      <version>5.11.4</version><scope>test</scope></dependency>",
        "  </dependencies>",
        "  <build><plugins>",
        "    <plugin><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId>",
        "      <version>${kotlin.version}</version>",
        "      <executions>",
        "        <execution><id>compile</id><phase>compile</phase><goals><goal>compile</goal></goals>",
        "          <configuration><sourceDirs><sourceDir>${project.basedir}/src/main/kotlin</sourceDir>",
        "          </sourceDirs></configuration></execution>",
        "        <execution><id>test-compile</id><phase>test-compile</phase>",
        "          <goals><goal>test-compile</goal></goals>",
        "          <configuration><sourceDirs><sourceDir>${project.basedir}/src/test/kotlin</sourceDir>",
        "          </sourceDirs></configuration></execution>",
        "      </executions></plugin>",
        "    <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version></plugin>",
        "  </plugins></build>",
        "</project>",
        "");
  }

  /**
   * The tests live in a module of their own and attach JaCoCo through jacoco-maven-plugin, as
   * Guava's and many other builds do, and the build is not changed to load cribro.
   *
   * <p>The base comes from the parent POM; lib-tests sets one that does not resolve, which the
   * build reports as an error wherever a module's POM is read for it.
   */
  @Test
  void anUnchangedBuildReportsCodeThatAnotherModuleTests() throws Exception {
    Path root = multiModule();

    String test = maven(root, 0, extension(), "test");

    assertBefore(test, "Diff coverage against base (", "BUILD SUCCESS");
    assertBefore(
        test,
        String.join(
            "\n",
            "  lib/src/main/java/org/apache/commons/lang3/ArrayUtils.java",
            "    0/1 changed lines executed",
            "    uncovered changed code:",
            "      12: not executed: return x + 2;"),
        "BUILD SUCCESS");
    assertAll(
        reportExists(root.resolve("lib/target/cribro/diff-coverage.txt")),
        reportExists(root.resolve("lib/target/cribro/jacoco.xml")));
  }

  @Test
  void aBaseOnTheCommandLineWinsOverThePom() throws Exception {
    Path root = multiModule();

    String test = maven(root, 0, extension(), "test", "-Dcribro.diffBase=origin/main");

    assertBefore(test, "Diff coverage against origin/main (", "BUILD SUCCESS");
  }

  /** A report left from an earlier run would describe tests this run did not run. */
  @Test
  void aRunThatSkipsTheTestsRemovesTheReports() throws Exception {
    Path root = multiModule();
    Path report = root.resolve("lib/target/cribro/diff-coverage.txt");
    Path xml = root.resolve("lib/target/cribro/jacoco.xml");
    maven(root, 0, extension(), "test");
    assertAll(reportExists(report), reportExists(xml));

    String skipped = maven(root, 0, extension(), "test", "-DskipTests");

    assertFalse(skipped.contains("Diff coverage"), skipped);
    assertAll(
        () -> assertFalse(Files.exists(report), () -> report + " after a run that skipped the tests"),
        () -> assertFalse(Files.exists(xml), () -> xml + " after a run that skipped the tests"));
  }

  /**
   * lib has no tests, so lib-tests mutates its changed line, and kills both mutants only with lib's
   * classes and the system property surefire sets.
   */
  @Test
  void pitRunsTheTestsOfAnotherModuleWithSurefiresSystemProperties() throws Exception {
    Path root = multiModule();
    write(
        root.resolve("lib-tests/src/test/java/org/apache/commons/lang3/NextTest.java"),
        String.join(
            "\n",
            "package org.apache.commons.lang3;",
            "",
            "import static org.junit.jupiter.api.Assertions.assertEquals;",
            "",
            "import org.junit.jupiter.api.Test;",
            "",
            "class NextTest {",
            "  @Test",
            "  void nextOfOneIsTheConfiguredValue() {",
            "    assertEquals(Integer.getInteger(\"next.of.one\"), ArrayUtils.next(1));",
            "  }",
            "}",
            ""));

    String pit =
        maven(
            root,
            0,
            extension(),
            // Not the path lib-tests: on Windows, Maven 3.9 matches a path against the canonical
            // module directory, and the temporary directory may have an 8.3 short name.
            "-pl",
            ":lib-tests",
            "-am",
            "test-compile",
            "cribro:pitest-diff",
            "-Dcribro.pitestTargetTests=**.NextTest");

    assertContains(
        pit,
        "Skipped: -am builds lib only for the modules -pl names; add it to -pl to run PIT on its"
            + " tests.");
    assertContains(pit, "2 mutants: 2 killed");
    Path survivors = root.resolve("lib-tests/target/cribro/pitest-diff/survivors.txt");
    assertTrue(Files.isRegularFile(survivors), () -> survivors + " after the run");
  }

  /** app comes after lib-tests in the reactor, so a failure in lib-tests stops the build before it. */
  @Test
  void aFailedTestNamesItsModuleAndTheModulesTheBuildStoppedBefore() throws Exception {
    Path root = multiModule("app");
    write(
        root.resolve("lib-tests/src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java"),
        TEST_CLASS.replace("ArrayUtils.abs(-3) >= 0", "ArrayUtils.next(1) == 4"));

    String failed = maven(root, 1, extension(), "test");

    assertBefore(
        failed,
        "Diff coverage: tests failed in lib-tests, so the counts include only the tests that ran"
            + " before the failure.",
        "BUILD FAILURE");
    assertBefore(failed, "Total: 1/1 changed lines executed.", "BUILD FAILURE");
    // The parent has no tests, so it is not among the modules the build stopped before.
    assertBefore(
        failed, "Diff coverage: the build stopped before the tests of app ran.", "BUILD FAILURE");
  }

  /**
   * Writes a parent of lib, which holds the changed class, and lib-tests, which tests it, followed by
   * the {@code extra} modules, each with a passing test of its own.
   */
  private Path multiModule(String... extra) throws IOException, InterruptedException {
    Path root = directory.resolve("multi");
    List<String> modules = new ArrayList<>(List.of("lib", "lib-tests"));
    modules.addAll(List.of(extra));
    write(
        root.resolve("pom.xml"),
        String.join(
            "\n",
            "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
            "  <modelVersion>4.0.0</modelVersion>",
            "  <groupId>demo</groupId><artifactId>parent</artifactId><version>1.0</version>",
            "  <packaging>pom</packaging>",
            "  <properties><cribro.diffBase>base</cribro.diffBase></properties>",
            "  <modules><module>" + String.join("</module><module>", modules) + "</module></modules>",
            "</project>",
            ""));
    write(root.resolve("lib/pom.xml"), pom("lib", "", ""));
    String jacoco =
        String.join(
            "\n",
            "<plugin><groupId>org.jacoco</groupId><artifactId>jacoco-maven-plugin</artifactId>",
            "  <version>0.8.12</version>",
            "  <executions><execution><goals><goal>prepare-agent</goal></goals></execution></executions>",
            "</plugin>");
    write(
        root.resolve("lib-tests/pom.xml"),
        pom(
                "lib-tests",
                "<dependency><groupId>demo</groupId><artifactId>lib</artifactId><version>1.0</version></dependency>",
                "<argLine>@{argLine} -Xmx256m</argLine>"
                    + "<systemPropertyVariables><next.of.one>3</next.of.one></systemPropertyVariables>",
                jacoco)
            .replace("<properties>", "<properties><cribro.diffBase>absent</cribro.diffBase>"));
    for (String module : extra) {
      write(root.resolve(module + "/pom.xml"), pom(module, "", ""));
      write(
          root.resolve(module + "/src/test/java/demo/PassingTest.java"),
          "package demo;\n\nclass PassingTest {\n  @org.junit.jupiter.api.Test\n  void passes() {}\n}\n");
    }
    Path source = root.resolve("lib/src/main/java/org/apache/commons/lang3/ArrayUtils.java");
    write(source, CHANGED_CLASS);
    write(root.resolve("lib-tests/src/test/java/org/apache/commons/lang3/ArrayUtilsTest.java"), TEST_CLASS);
    commitAsBase(root);
    run(root, 0, "git", "tag", "base");
    write(source, CHANGED_CLASS.replace("return x + 1;", "return x + 2;"));
    return root;
  }

  private static String extension() {
    return "-Dmaven.ext.class.path=" + System.getProperty("cribro.it.extensionJar");
  }

  private static Executable reportExists(Path report) {
    return () -> assertTrue(Files.isRegularFile(report), () -> report + " after the run");
  }

  private static String pom(String artifactId, String dependencies, String surefire, String... plugins) {
    return String.join(
        "\n",
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">",
        "  <modelVersion>4.0.0</modelVersion>",
        "  <groupId>demo</groupId><artifactId>" + artifactId + "</artifactId><version>1.0</version>",
        "  <properties>",
        "    <maven.compiler.release>11</maven.compiler.release>",
        "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
        "  </properties>",
        "  <dependencies>",
        "    " + dependencies,
        "    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>",
        "      <version>5.11.4</version><scope>test</scope></dependency>",
        "  </dependencies>",
        "  <build><plugins>",
        "    <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>",
        "    <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.2</version>",
        "      <configuration>" + surefire + "</configuration></plugin>",
        "    " + String.join("\n    ", plugins),
        "  </plugins></build>",
        "</project>",
        "");
  }

  private static String extensionsXml() {
    return String.join(
        "\n",
        "<extensions><extension>",
        "  <groupId>io.github.cribro-dev</groupId><artifactId>cribro-maven-plugin</artifactId>",
        "  <version>" + VERSION + "</version>",
        "</extension></extensions>",
        "");
  }

  private void commitAsBase(Path project) throws IOException, InterruptedException {
    run(project, 0, "git", "init", "-q", "-b", "main");
    run(project, 0, "git", "add", "-A");
    run(project, 0, "git", "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "base");
    run(project, 0, "git", "update-ref", "refs/remotes/origin/main", "HEAD");
  }

  /** Runs Maven in the project and returns its output, after checking its exit code. */
  private String maven(Path project, int exitCode, String... arguments)
      throws IOException, InterruptedException {
    Path settings = directory.resolve("settings.xml");
    if (!Files.exists(settings)) {
      String url = new File(System.getProperty("cribro.it.repository")).toURI().toString();
      String repository =
          "<id>cribro</id><url>" + url + "</url><snapshots><enabled>true</enabled></snapshots>";
      write(
          settings,
          String.join(
              "\n",
              "<settings><profiles><profile><id>cribro</id>",
              "  <repositories><repository>" + repository + "</repository></repositories>",
              "  <pluginRepositories><pluginRepository>" + repository
                  + "</pluginRepository></pluginRepositories>",
              "</profile></profiles><activeProfiles><activeProfile>cribro</activeProfile>",
              "</activeProfiles></settings>",
              ""));
    }
    String home = System.getProperty("cribro.it.mavenHome");
    boolean windows = File.separatorChar == '\\';
    List<String> command = new ArrayList<>();
    command.add(new File(home, windows ? "bin/mvn.cmd" : "bin/mvn").getAbsolutePath());
    command.addAll(
        List.of(
            "-B",
            "-U",
            "-ntp",
            "-s",
            settings.toString(),
            "-Dmaven.repo.local=" + System.getProperty("cribro.it.localRepository")));
    command.addAll(Arrays.asList(arguments));
    return run(project, exitCode, command.toArray(new String[0]));
  }

  private String run(Path workingDirectory, int exitCode, String... command)
      throws IOException, InterruptedException {
    Path log = Files.createTempFile(directory, "out", ".log");
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().remove("MAVEN_OPTS");
    builder.environment().remove("MAVEN_ARGS");
    Process process = builder.start();
    process.getOutputStream().close();
    int actual = process.waitFor();
    String output = Files.readString(log, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertEquals(exitCode, actual, () -> String.join(" ", command) + "\n" + output);
    return output;
  }

  private static void assertContains(String output, String expected) {
    assertTrue(output.contains(expected), () -> "expected:\n" + expected + "\nin:\n" + output);
  }

  /** Checks that the text appears, and before the first line that contains {@code later}. */
  private static void assertBefore(String output, String expected, String later) {
    assertContains(output, expected);
    assertContains(output, later);
    assertTrue(
        output.indexOf(expected) < output.indexOf(later),
        () -> expected + "\nprinted after " + later + " in:\n" + output);
  }

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }
}
