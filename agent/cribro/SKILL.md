---
name: cribro
description: Measure how well the tests of a Gradle or Maven Java or Kotlin project cover the lines the current branch changed. Use after changing the Java or Kotlin sources under src/main of a Gradle or Maven module and adding or changing its tests, before calling the change done; also when asked which changed lines no test executes, whether the new tests would notice a broken line, or for diff coverage or mutation testing of a branch. Runs JaCoCo diff coverage on `test` and PIT on the changed lines, through the build's own cribro plugin or extension, or through an init script or extension jar that leaves the build unchanged.
---

# Diff coverage and diff mutation testing

cribro adds diff coverage to `test` and diff mutation testing to every Java or Kotlin project of a Gradle or Maven build. A build
with a `pom.xml` follows the [Maven](#maven) section; the rest of this section is for Gradle builds. A changed file is
matched to its coverage counters and its mutants by the package it declares and its file name, not by its directory, so
a Kotlin file whose directory does not follow its package is measured all the same.

In a Gradle build, cribro adds a `pitestDiff` task. A build can apply it itself: its `settings.gradle` or
`settings.gradle.kts` names `io.github.cribro-dev.cribro`. Otherwise the init script at
`~/.gradle/cribro/cribro.init.gradle` applies it without changing the build.

Before the first measurement in a build, print the guide. Where the build applies cribro:

```bash
./gradlew -q cribro
```

Otherwise:

```bash
./gradlew --init-script ~/.gradle/cribro/cribro.init.gradle -q cribro
```

The init-script form also works in a build that applies cribro itself. The guide lists the projects that have the
tasks, gives every later command in the form this build needs, and says how to act on each report. It comes from the
plugin that will run, so it matches its version.

The usual sequence:

1. After changing `src/main` and its tests, run the `test` command the guide prints. It ends with the changed lines no
   test executed; write a test for each, or justify leaving it untested.
2. Once the changed lines are covered, run the `pitestDiff` command the guide prints, with
   `-Pcribro.pitestTargetTests=<test classes>`. Each surviving mutant is a changed line whose behavior no test asserts.

Where a Gradle build does not apply cribro and `~/.gradle/cribro/cribro.init.gradle` does not exist, cribro is not
installed: tell the user, and point them at <https://github.com/cribro-dev/cribro#install>.

## Maven

In a Maven build, cribro is an extension. The build loads it itself where `.mvn/extensions.xml` names
`cribro-maven-plugin`; otherwise `~/.m2/cribro/cribro-maven.jar` loads it without changing the build. Print the guide
first. Where the build loads cribro (use `./mvnw` in place of `mvn` where the project has it):

```bash
mvn -q cribro:guide
```

Otherwise:

```bash
mvn -Dmaven.ext.class.path="$HOME"/.m2/cribro/cribro-maven.jar -q cribro:guide
```

Then follow the same sequence with the commands the guide prints: `test` ends with the diff coverage report, and
`cribro:pitest-diff` with `-Dcribro.pitestTargetTests=<test classes>` reports surviving mutants. Where neither the build
nor `~/.m2/cribro/cribro-maven.jar` provides cribro, it is not installed: tell the user, and point them at
<https://github.com/cribro-dev/cribro#maven>.
