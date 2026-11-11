# Diff coverage and diff mutation testing

The commands in this guide are the ones to run in this build. `<module>` is the module whose tests exercise the change,
one of those listed above, written `:artifactId` as `-pl` selects it. `-am` builds the modules it depends on, the
changed code among them.

## Diff coverage

```bash
{{mvn}} -pl <module> -am test
```

Once the build has run the tests, it prints a block like this before its summary:

```text
Diff coverage against origin/main (95aa87fb8), from the tests of guava-tests -Dtest=ImmutableListTest
  a filtered run leaves the rest of the change looking unexecuted

  guava/src/com/google/common/collect/ImmutableList.java
    6/7 changed lines executed, 3/4 branches covered
    uncovered changed code:
      167: 1 of 2 branches covered: if (len < 0) {
      168: not executed: throw new IllegalArgumentException("len must be non-negative, got " + len);
```

Each uncovered line is a test you have not written yet, or a line you can justify leaving untested. To run some of the
tests, add `-Dtest=<test classes> -Dsurefire.failIfNoSpecifiedTests=false`: under `-am`, surefire otherwise fails the
first module that has none of those classes. The counts reflect only the tests that ran: after a filtered run, run the
module's whole suite before you treat a line as untested. A module's code counts as executed by the tests of every
module in the build, so `-am` matters when the tests live in a module of their own. The whole report is in
`target/cribro/diff-coverage.txt` of each module whose main sources the change touched, which can be a module other than
`<module>`.

If an uncovered line is unreachable or not worth a test, mark it with `// diff-coverage: ignore -- <reason>`. It stays
counted as uncovered and leaves the list of work.

### Unchanged code the change routes new inputs to

A changed `case` label, a changed condition of an `if` or a loop, and a call on a changed line can send new inputs to
lines nobody edited: the statements the label falls through to, the branches the condition guards, the body of a
method of the same file that the call reaches. The block after the total lists those methods and the lines and
branches of them the run did not cover. The change does not owe them a test, but they now run under conditions the old
tests may never have set up.

After a run of the whole suite, old tests execute most of that code under the old conditions, so the block ends with
the `-Dtest` arguments that run only the test methods the change added or edited under the module's own `src`
directory. Run those: what is left uncovered then is what the change's own tests do not exercise in the code it
reaches. Write a test for a gap that the change made reachable, or say why it cannot happen. The block is built from
the changed `.java` files only; a change to a `.kt` file adds no method and no `-Dtest` argument to it.

The report measures the tests of surefire's `default-test` execution. Only `.java` and `.kt` files under the main
source directories count, the `sourceDirs` of `kotlin-maven-plugin` included; Groovy changes are not measured. A file
is matched to its counters by the package it declares and its file name, as its class files record them, not by its
directory; two files of one name in one package under two source roots share one set of counters. A build that skips
the tests prints no report.

## Mutation testing

A test that executes a line can pass whatever the line does. After the changed lines are covered, run:

```bash
{{mvn}} -pl <module> -am test-compile cribro:pitest-diff -Dcribro.pitestTargetTests=<test classes>
```

PIT changes one instruction at a time on the changed lines and runs the tests against each copy. A mutant that
survives marks a changed line whose behavior no test asserts. For each live mutant, decide which case it is:

- the tests miss behavior the change defines: add or strengthen a test next to the tests the report names for that line;
- no caller can observe the difference: the mutant is equivalent, so say so in the pull request;
- you cannot tell: report it as unresolved.

`cribro:pitest-diff` is not part of `test`, and a surviving mutant never fails the build. It costs one run of the target
tests plus a partial run per mutant, so narrow `-Dcribro.pitestTargetTests` to the test classes that exercise the
change (comma-separated, globs allowed). A module mutates its own changed classes and those of the modules it tests
that have no tests of their own. It is skipped when it has no compiled tests, when none of its test classes is among
those surefire's includes and excludes, or `-Dcribro.pitestTargetTests`, select, or when the branch changed no line it
mutates. Under `-am`, only the modules `-pl` names run PIT; each module `-am` adds prints a `Skipped:` line. The whole
report is in `<module>/target/cribro/pitest-diff/survivors.txt`, and PIT's own output in `pit.log`
beside it.

PIT runs the tests with surefire's classpath, JVM, `argLine`, system properties, environment variables, working
directory, groups, includes, and excludes. Where they hold `${surefire.forkNumber}` or `${surefire.threadNumber}`, the tests get 1, the number of surefire's first fork, and PIT's minions running at once share it. PIT leaves out a test that fails in its first run, silently. PIT generates no mutant on a line
that calls a logging library. When the report says no test reached the changed lines, read `pit.log` for the failing
tests: a test that needs a database or a service the machine lacks is the usual cause.

## Which lines count as changed

The changed lines are the diff between the working tree and the merge base of `HEAD` with the base ref, untracked
`.java` and `.kt` files included. The base is `-Dcribro.diffBase=<ref>` when given, else the upstream branch, else
`origin/HEAD`, `origin/main`, or `origin/master`. An upstream that is this branch pushed to a fork is skipped. For a
stacked change, pass the parent branch: `-Dcribro.diffBase=<parent>`.

## Properties

Pass a property with `-D`, or set it in `.mvn/maven.config` or in the `<properties>` of the POM, the top-level one for
`cribro.diffBase`; the command line wins.

| Property | Default | Effect |
| --- | --- | --- |
| `-Dcribro.diffBase=<ref>` | upstream, then `origin/HEAD`, `origin/main`, `origin/master` | The base of both reports |
| `-Dcribro.diffCoverageEnabled=false` | on | Runs the tests without JaCoCo and skips the report |
| `-Dcribro.diffCoverageShowAll` | off | Prints the whole coverage report to the console |
| `-Dcribro.diffCoverageMaxFiles=<n>`, `-Dcribro.diffCoverageMaxLinesPerFile=<n>`, `-Dcribro.diffCoverageMaxLines=<n>` | `10`, `10`, `50` | How much of the coverage report the console shows |
| `-Dcribro.pitestTargetTests=<globs>` | surefire's includes | Tests allowed to kill a mutant |
| `-Dcribro.pitestMutators=<group>` | `DEFAULTS` | PIT mutator group: `DEFAULTS`, `STRONGER`, or `ALL` |
| `-Dcribro.pitestThreads=<n>` | half the cores | Mutants analyzed in parallel |
| `-Dcribro.pitestTimeoutConst=<ms>` | `4000` | Time PIT adds to a test's normal run before it calls a mutant a timeout |
| `-Dcribro.pitestShowAll` | off | Prints the whole mutation report to the console |
| `-Dcribro.pitestMaxFiles=<n>`, `-Dcribro.pitestMaxLinesPerFile=<n>`, `-Dcribro.pitestMaxLines=<n>` | `10`, `20`, `50` | How much of the mutation report the console shows |
| `-Dcribro.pitestVersion=<v>` | `{{pitestVersion}}` | PIT version |
| `-Dcribro.pitestJunit5PluginVersion=<v>` | `{{pitestJunit5PluginVersion}}` | Version of PIT's JUnit Platform plugin, added where the tests run on the JUnit Platform |
| `-Dcribro.pitestPlugins=<coordinates>` | none | Further PIT plugins on PIT's classpath, comma-separated, such as `com.arcmutate:pitest-kotlin-plugin:1.5.2` |
| `-Dcribro.jacocoVersion=<v>` | `{{jacocoVersion}}` | JaCoCo agent version for the test JVM, where the build attaches none of its own |

The tests PIT runs need JDK 11 or later.

A mutant is matched to a changed file as the coverage report matches it, by the package its class declares and the
file name the class records. Kotlin's compiler emits code the source does not show, such as null checks on parameters,
and PIT drops the mutants of the cases it knows. The rest are reported as live mutants on a changed line whose text
gives no hint; Arcmutate's Kotlin plugin (https://docs.arcmutate.com/docs/kotlin.html) filters more of them: `-Dcribro.pitestPlugins` adds
it, and it needs an Arcmutate licence file, `arcmutate-licence.txt`, at the repository root.

## Limits

- Where the build attaches a JaCoCo agent of its own, as `jacoco-maven-plugin` does through `argLine`, the report
  reads that agent's file.
- Only surefire's `default-test` execution is measured; failsafe and other surefire executions are not.
- A module whose tests run in Maven's own JVM (`forkCount=0`) records no coverage, and the report says so.
- PIT splits JVM arguments at commas and whitespace. The goal leaves such arguments out and names them in its output;
  a test that depends on one may fail under PIT and drop out.
- PIT runs JUnit Jupiter tests one at a time, whatever surefire sets for parallel execution.
- `cribro:pitest-diff` has no overall time limit, and surefire's `forkedProcessTimeoutInSeconds` does not bound it. A
  test that hangs before any mutant runs keeps PIT waiting until the build is stopped; `cribro.pitestTimeoutConst`
  bounds only the runs against mutants.
