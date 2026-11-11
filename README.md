# cribro

Reports which lines a branch changed that the tests do not execute (JaCoCo) and which changed lines the tests do not
check (PIT), for a Gradle or Maven build with Java or Kotlin sources. The build can apply cribro itself, or cribro can
be loaded into a build that knows nothing about it. For Maven, see [Maven](#maven). The output is plain text sized for
an AI agent; there is no HTML report of its own.

On [pgjdbc/pgjdbc#4036](https://github.com/pgjdbc/pgjdbc/pull/4036), `test` ends with a block like this one, cut here
to one file:

```text
Diff coverage against origin/HEAD (a340cb2b0), from :postgresql:test --tests *SRVLookupTest
  a filtered run leaves the rest of the change looking unexecuted
  ordered by uncovered changed lines, then uncovered branches

  pgjdbc/src/main/java/org/postgresql/Driver.java
    12/16 changed lines executed, 12/22 branches covered
    uncovered changed code:
      254: not executed; 0 of 4 branches covered: if (!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:postgresql+srv:")) {
      731: not executed: String srvHost = PGProperty.SRV_HOST.getOrDefault(props);
```

The code started in NullAway's `buildSrc` ([uber/NullAway#1833](https://github.com/uber/NullAway/pull/1833) and
[uber/NullAway#1975](https://github.com/uber/NullAway/pull/1975)), written by the same author.

## Requirements

A Gradle build needs:

- Gradle 8.0 or later, running on JDK 11 or later (Gradle 9 itself needs JDK 17). Nothing older than 8.14 has been
  tried.
- Tests that run on JDK 11 or later, since PIT loads this plugin's classes into the test JVM.
- Java or Kotlin sources under the main source set of the `java` plugin, which the Kotlin JVM plugin applies;
  `src/main/kotlin` counts. Groovy sources, Kotlin Multiplatform, and Android builds are not measured.
- JUnit 4, or JUnit Jupiter or Vintage on the JUnit Platform, for `pitestDiff`.

A Maven build has its own list, in [Requirements for Maven](#requirements-for-maven). To build cribro itself, for
either build tool, you need a JDK 25 where Gradle finds it, for example as `JAVA_HOME`. The build does not download one.

A changed file is matched to its JaCoCo counters and its PIT mutants by the package it declares and its name, which is
what its class files record, so a Kotlin file whose directory does not follow its package is still measured. The block
on unchanged code the change routes new inputs to is built from the changed `.java` files only; a change to a `.kt`
file adds nothing to it. PIT drops the mutants of the Kotlin compiler-generated code it knows about, such as null
checks on parameters; the rest are reported as live mutants on a changed line whose text gives no hint. Arcmutate's
[Kotlin plugin](https://docs.arcmutate.com/docs/kotlin.html) filters more of them: `-Pcribro.pitestPlugins=com.arcmutate:pitest-kotlin-plugin:<version>`
in Gradle, or `-Dcribro.pitestPlugins=...` in Maven, puts it on PIT's classpath, and it needs an Arcmutate licence
file, `arcmutate-licence.txt`, at the repository root; its page gives the terms.

Tried on pgjdbc (Gradle 9.4.1 and 9.7.1, JUnit Jupiter), NullAway (Gradle 9.8.1, JUnit 4), and a one-class build on
Gradle 8.14.3 whose tests have no `junit-platform-launcher` dependency. CI runs `test pitestDiff` through the init
script on a one-class build with both Gradle 8.14.3 and its tests on JDK 11.

## Install

| Build tool | Request | Section | What changes |
| --- | --- | --- | --- |
| Gradle | Set up cribro in a project, so everyone who builds it gets the tasks | [In the build](#in-the-build) | The project's `settings.gradle(.kts)` and `AGENTS.md` |
| Gradle | Install cribro for your own use, in projects you do not want to modify | [Without changing the build](#without-changing-the-build) | `~/.gradle/cribro/` and `~/.claude/skills/cribro/`; no project file |
| Maven | Set up cribro in a project, so everyone who builds it gets the extension | [Install for Maven](#install-for-maven), step 2, in the build | The project's `.mvn/extensions.xml` and `AGENTS.md` |
| Maven | Install cribro for your own use, in projects you do not want to modify | [Install for Maven](#install-for-maven), step 2, without changing the build | `~/.m2/cribro/` and `~/.claude/skills/cribro/`; no project file |

For Gradle, both ways add the same tasks. Where the build applies the plugin itself, the init script leaves it alone
and the build's version runs, so the two ways can coexist.

### In the build

<details>
<summary>From source: a build of this repository</summary>

A build from source has the version `1.0.0-SNAPSHOT`, which resolves only from the local Maven repository of a
machine that ran step 1. A clone of the project on any other machine, CI included, fails to resolve the plugin, so keep
this change out of a repository that others build.

1. Clone this repository into a directory outside the project, `<cribro-dir>` below, and publish the plugin to the
   local Maven repository:

   ```bash
   git clone https://github.com/cribro-dev/cribro.git <cribro-dir>
   ```

   ```bash
   cd <cribro-dir> && ./gradlew --quiet publishToMavenLocal
   ```

2. Return to the project's root directory, and add the plugin to its `settings.gradle.kts`. It adds the tasks to
   every project of the build that applies the `java` plugin, which the Kotlin JVM plugin applies too:

   ```kotlin
   pluginManagement {
       repositories {
           mavenLocal()
           gradlePluginPortal()
       }
   }

   plugins {
       id("io.github.cribro-dev.cribro") version "1.0.0-SNAPSHOT"
   }
   ```

   Where the file already has a `pluginManagement` or a `plugins` block, add the lines to it instead of writing a
   second one. A `repositories` block replaces Gradle's default of the Plugin Portal, so keep `gradlePluginPortal()` in
   it. In `settings.gradle`, the `pluginManagement` block reads the same, and the plugin line is
   `id 'io.github.cribro-dev.cribro' version '1.0.0-SNAPSHOT'`.

3. Check that the build applies it, from the project's root directory. The command prints `cribro 1.0.0-SNAPSHOT`
   and the projects that have the tasks:

   ```bash
   ./gradlew -q cribro
   ```

4. Tell the agents that work on the project. `./gradlew -q cribro` prints the guide for the version the build runs, so
   a note in `AGENTS.md`, or in whichever file the project's agents read, needs only to name that command:

   ```markdown
   After changing the Java or Kotlin sources under `src/main` of a module, run `./gradlew <project>:test`, where
   `<project>` is that module's Gradle project path, such as `:app`, and act on the diff coverage report it
   prints. `./gradlew -q cribro` prints how to read the reports and how to run mutation testing.
   ```

</details>

[What the plugin does to a build](#what-the-plugin-does-to-a-build) lists the settings it overrides in each project.

### Without changing the build

The init script applies cribro to a build that knows nothing about it, such as a clone of pgjdbc. Gradle runs it only
in a command that names it with `--init-script`.

<details>
<summary>From source: a build of this repository</summary>

1. Clone this repository into a directory outside any project, `<cribro-dir>` below, and build the init script and
   the plugin jar into `cribro-gradle-plugin/build/dist`:

   ```bash
   git clone https://github.com/cribro-dev/cribro.git <cribro-dir>
   ```

   ```bash
   cd <cribro-dir> && ./gradlew --quiet dist
   ```

2. Copy them into `~/.gradle/cribro/`. Run this from `<cribro-dir>`:

   ```bash
   mkdir -p ~/.gradle/cribro && cp cribro-gradle-plugin/build/dist/* ~/.gradle/cribro/
   ```

3. Install the agent skill, which tells the agent when to run cribro and how to reach the guide through the init
   script. `agent/cribro/SKILL.md` is a plain skill file; for Claude Code, run this from `<cribro-dir>`:

   ```bash
   mkdir -p ~/.claude/skills/cribro && cp agent/cribro/SKILL.md ~/.claude/skills/cribro/
   ```

4. Check it from the root directory of the project you want to measure. The command prints
   `cribro 1.0.0-SNAPSHOT` and the projects that have the tasks:

   ```bash
   ./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle -q cribro
   ```

After `git pull` in `<cribro-dir>`, run `./gradlew --quiet dist` and steps 2 and 3 again. An init script built
before cribro became a settings plugin applies the plugin a second time to a build that applies it itself, and that
build fails.

</details>

The init script loads `cribro.jar` from its own directory, or resolves its version from the Portal, also in a build
that applies cribro itself. Without a `cribro.jar` beside it, a script built from a released version resolves that
version from the Gradle Plugin Portal, and a script built from a `-SNAPSHOT` version fails with:

```text
No cribro.jar beside <script>, and cribro <version> is a development build that the Gradle Plugin Portal does not host. Copy cribro-gradle-plugin/build/dist/cribro.jar of the cribro build into <directory>.
```

The plugin jar has no runtime dependencies: PIT is resolved from the target build's repositories when `pitestDiff`
runs.

## Use

This section is for Gradle; a Maven build follows [Use with Maven](#use-with-maven).

`./gradlew -q cribro` prints the guide to the other tasks: the projects that have them, the commands written the way
this build runs them, the properties, and how to act on each report.

```bash
./gradlew -q cribro
```

With the init script:

```bash
./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle -q cribro
```

The two commands an agent runs most are these, written for a build that applies the plugin; with the init script,
add `--init-script ~/.gradle/cribro/cribro.init.gradle` to each:

```bash
./gradlew <project>:test
```

```bash
./gradlew <project>:pitestDiff -Pcribro.pitestTargetTests=<tests>
```

`<project>` is a Gradle project path such as `:app`, which can differ from the directory: pgjdbc's `pgjdbc/` directory
is `:postgresql`. The guide lists the paths. For the root project, the task path is `:test`.

## Tell an agent about it

An agent learns about cribro from the `AGENTS.md` note of a build that applies it, step 4 of
[In the build](#in-the-build) or of [Install for Maven](#install-for-maven), or from the skill installed in step 3 of
[Without changing the build](#without-changing-the-build). The skill covers Maven builds too.

## What the plugin does to a build

The plugin registers `cribro` on the root project, and adds a `cribroProjectElements` configuration to every project;
`cribro` resolves that configuration of each project to list the projects that have the tasks. Applied as a settings
plugin or through the init script, cribro works with Isolated Projects, tried on Gradle 9.8.2. On Gradle 8, Isolated
Projects fails the `cribro` task where a subproject has the same group and name as the root project. For every project
with the `java` plugin, it:

- applies `jacoco`, enables it on the `test` task, and has `test` finalized by `jacocoTestReport` with the XML report,
  then by `diffCoverage`. These override the build's own settings after its scripts run, so a build that disables
  JaCoCo still gets the report. Where the build does not apply `jacoco` itself, the other `Test` tasks run without it;
- replaces the JaCoCo version only where it is Gradle's bundled default and older than 0.8.16;
- registers `changedLines` and `pitestDiff`, and a `cribroPitest` configuration with `pitest-command-line`, plus
  `pitest-junit5-plugin` when `test` uses the JUnit Platform, plus `junit-platform-launcher` when the tests do not
  bring one;
- runs PIT in a separate JVM from the `test` task's launcher, with its JVM arguments and system properties.

## Maven

For a Maven build, cribro is a Maven extension and plugin, `io.github.cribro-dev:cribro-maven-plugin`. Loaded as an
extension, it attaches JaCoCo to the tests of surefire's `default-test` execution and prints the diff coverage report
before Maven's build summary. Its goal `cribro:pitest-diff` runs PIT on the changed lines.

### Requirements for Maven

- Maven 3.9 running on JDK 11 or later; tried on 3.9.16. Maven 4 has not been tried.
- CI tests the extension on JDK 17 and later. It is compiled for JDK 11, but no test runs Maven on JDK 11.
- Tests run by surefire's `default-test` execution, in a forked JVM.
- Java sources under the compile source roots, or Kotlin sources under those roots or the `sourceDirs` of
  `kotlin-maven-plugin`.
- For `cribro:pitest-diff`: tests on JUnit 4, or on JUnit Jupiter or Vintage on the JUnit Platform, in a JVM of JDK 11
  or later.

Tried on Guava, whose tests live in `guava-tests` and test the code of `guava`, without changing its build.

### Install for Maven

A build from source has the version `1.0.0-SNAPSHOT`, which resolves only from the local Maven repository of a
machine that ran step 1 below.

1. Clone this repository into a directory outside the project, `<cribro-dir>` below, publish the plugin to the local
   Maven repository, and build the extension jar into `cribro-maven-plugin/build/dist`:

   ```bash
   git clone https://github.com/cribro-dev/cribro.git <cribro-dir>
   ```

   ```bash
   cd <cribro-dir> && ./gradlew --quiet publishToMavenLocal :cribro-maven-plugin:extensionJar
   ```

2. Choose how the build loads cribro:

   - In the build: add `.mvn/extensions.xml` to the project's root directory. Where the file exists, add the
     `<extension>` element to it.

     ```xml
     <extensions>
       <extension>
         <groupId>io.github.cribro-dev</groupId>
         <artifactId>cribro-maven-plugin</artifactId>
         <version>1.0.0-SNAPSHOT</version>
       </extension>
     </extensions>
     ```

   - Without changing the build: copy the extension jar into `~/.m2/cribro/`, and pass it to each Maven command with
     `-Dmaven.ext.class.path`. Run this from `<cribro-dir>`:

     ```bash
     mkdir -p ~/.m2/cribro && cp cribro-maven-plugin/build/dist/cribro-maven.jar ~/.m2/cribro/
     ```

     The jar alone measures diff coverage. `cribro:pitest-diff` and `cribro:guide` also need the plugin in a
     repository Maven reads, which step 1 put in the local one.

3. Check it from the project's root directory. The command prints `cribro 1.0.0-SNAPSHOT` and the modules with tests:

   ```bash
   mvn -q cribro:guide
   ```

   Without changing the build:

   ```bash
   mvn -Dmaven.ext.class.path="$HOME"/.m2/cribro/cribro-maven.jar -q cribro:guide
   ```

4. Tell the agents that work on the project. In the build, `mvn -q cribro:guide` prints the guide for the version the
   build loads, so a note in `AGENTS.md`, or in whichever file the project's agents read, needs only to name it:

   ```markdown
   After changing the Java or Kotlin sources under `src/main` of a module, run `mvn -pl <module> -am test`, where
   `<module>` is the module whose tests exercise the change, such as `:app`, and act on the diff coverage report it
   prints.
   `mvn -q cribro:guide` prints how to read the reports and how to run mutation testing.
   ```

   Without changing the build, install the agent skill as step 3 of
   [Without changing the build](#without-changing-the-build) does; it covers Maven builds.

After `git pull` in `<cribro-dir>`, run step 1 again, and for the jar install the copy in step 2, so that the extension
jar and the plugin in the local Maven repository are the same version.

Where a build loads cribro both ways, the build's copy acts and the other stands down.

### Use with Maven

`cribro:guide` prints the commands written for this build, the properties, and how to act on each report. The two
commands an agent runs most are these; without changing the build, add
`-Dmaven.ext.class.path="$HOME"/.m2/cribro/cribro-maven.jar` to each:

```bash
mvn -pl <module> -am test
```

```bash
mvn -pl <module> -am test-compile cribro:pitest-diff -Dcribro.pitestTargetTests=<tests>
```

`<module>` is the module whose tests exercise the change, written `:artifactId`. The tests of every module in the
build count, so `-am` adds the modules the tests depend on, and their changed code is measured too. Properties are
the Gradle ones with `-D` in place of `-P`.

### What the extension does to a build

Loaded as an extension, cribro:

- adds a JaCoCo agent to the `argLine` of surefire's `default-test` execution just before it runs, whatever the POM
  sets there. Where the `argLine` already attaches a JaCoCo agent, as `jacoco-maven-plugin`'s `prepare-agent` does,
  cribro reads that agent's file instead;
- writes `target/cribro/jacoco.xml` and `target/cribro/diff-coverage.txt` in each module the change reached;
- declares `cribro-maven-plugin` in every module that does not declare it, so that the `cribro:` prefix resolves.

`cribro:pitest-diff` runs PIT in a separate JVM, with the classpath, JVM, `argLine`, system properties, environment
variables, working directory, groups, includes, and excludes of surefire's `default-test` execution.

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
