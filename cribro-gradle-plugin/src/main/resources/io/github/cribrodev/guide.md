# Diff coverage and diff mutation testing

The commands in this guide are the ones to run in this build. `<project>` is one of the project paths listed above,
which can differ from the directory: pgjdbc's `pgjdbc/` directory is `:postgresql`, so `<project>:test` is
`:postgresql:test`. For the root project `:`, write `:test`.

## Diff coverage

```bash
{{gradlew}} <project>:test
```

`test` ends with a block like this:

```text
Diff coverage against origin/master (95aa87fb8), from :postgresql:test --tests *PgBufferedOutputStreamTest
  a filtered run leaves the rest of the change looking unexecuted

  pgjdbc/src/main/java/org/postgresql/util/internal/PgBufferedOutputStream.java
    6/7 changed lines executed, 3/4 branches covered
    uncovered changed code:
      167: 1 of 2 branches covered: if (len < 0) {
      168: not executed: throw new IllegalArgumentException("len must be non-negative, got " + len);
```

Each uncovered line is a test you have not written yet, or a line you can justify leaving untested. The counts reflect
only the tests that ran: after a run filtered with `--tests`, run the module's whole suite before you treat a line as
untested. The whole report is in `<module>/build/reports/diff-coverage/test.txt`.

If an uncovered line is unreachable or not worth a test, mark it with `// diff-coverage: ignore -- <reason>`. It stays
counted as uncovered and leaves the list of work.

### Unchanged code the change routes new inputs to

A changed `case` label, a changed condition of an `if` or a loop, and a call on a changed line can send new inputs to
lines nobody edited: the statements the label falls through to, the branches the condition guards, the body of a method
of the same file that the call reaches. The block after the total lists those methods and the lines and branches of them
the run did not cover. The change does not owe them a test, but they now run under conditions the old tests may never
have set up.

After a run of the whole suite, old tests execute most of that code under the old conditions, so the block ends with
the `--tests` arguments that run only the test methods the change added or edited. Run those: what is left
uncovered then is what the change's own tests do not exercise in the code it reaches, such as a literal in a mode the
new test never sets. Write a test for a gap that the change made reachable, or say why it cannot happen. The block is
built from the changed `.java` files only; a change to a `.kt` file adds no method and no `--tests` argument to it.

`{{gradlew}} <project>:diffCoverage` prints the report again from the last test run without running the tests. Only
`.java` and `.kt` files under the main source set count, `src/main/kotlin` included; Groovy changes are not measured.
A file is matched to its counters by the package it declares and its file name, as its class files record them, not
by its directory; two files of one name in one package under two source roots share one set of counters.

## Mutation testing

A test that executes a line can pass whatever the line does. After the changed lines are covered, run:

```bash
{{gradlew}} <project>:pitestDiff -Pcribro.pitestTargetTests=<test classes>
```

PIT changes one instruction at a time on the changed lines and runs the tests against each copy. A mutant that
survives marks a changed line whose behavior no test asserts. For each live mutant, decide which case it is:

- the tests miss behavior the change defines: add or strengthen a test next to the tests the report names for that line;
- no caller can observe the difference: the mutant is equivalent, so say so in the pull request;
- you cannot tell: report it as unresolved.

`pitestDiff` is not part of `test`, and a surviving mutant never fails the build. It costs one run of the target tests
plus a partial run per mutant, so narrow `-Pcribro.pitestTargetTests` to the test classes that exercise the change
(comma-separated, globs allowed). The task is skipped when the branch changed no line in the module's main sources.
The whole report is in `<module>/build/reports/pitest-diff/survivors.txt`, and PIT's own output in `pit.log` beside it.

PIT leaves out a test that fails in its first run, silently. PIT generates no mutant on a line that calls a logging
library. When the report says no test reached the changed lines, read `pit.log` for the failing tests: a test that
needs a database or a service the machine lacks is the usual cause.

## Which lines count as changed

The changed lines are the diff between the working tree and the merge base of `HEAD` with the base ref, untracked
`.java` and `.kt` files included. The base is `-Pcribro.diffBase=<ref>` when given, else the upstream branch, else
`origin/HEAD`, `origin/main`, or `origin/master`. An upstream that is this branch pushed to a fork is skipped. For a
stacked change, pass the parent branch: `-Pcribro.diffBase=<parent>`.

## Properties

| Property | Default | Effect |
| --- | --- | --- |
| `-Pcribro.diffBase=<ref>` | upstream, then `origin/HEAD`, `origin/main`, `origin/master` | The base of both reports |
| `-Pcribro.diffCoverageEnabled=false` | on | Runs `test` without JaCoCo and skips the report |
| `-Pcribro.diffCoverageShowAll` | off | Prints the whole coverage report to the console |
| `-Pcribro.diffCoverageMaxFiles=<n>`, `-Pcribro.diffCoverageMaxLinesPerFile=<n>`, `-Pcribro.diffCoverageMaxLines=<n>` | `10`, `10`, `50` | How much of the coverage report the console shows |
| `-Pcribro.pitestTargetTests=<globs>` | every test (`~.*`) | Tests allowed to kill a mutant |
| `-Pcribro.pitestMutators=<group>` | `DEFAULTS` | PIT mutator group: `DEFAULTS`, `STRONGER`, or `ALL` |
| `-Pcribro.pitestThreads=<n>` | half the cores | Mutants analyzed in parallel |
| `-Pcribro.pitestTimeoutConst=<ms>` | `4000` | Time PIT adds to a test's normal run before it calls a mutant a timeout |
| `-Pcribro.pitestShowAll` | off | Prints the whole mutation report to the console |
| `-Pcribro.pitestMaxFiles=<n>`, `-Pcribro.pitestMaxLinesPerFile=<n>`, `-Pcribro.pitestMaxLines=<n>` | `10`, `20`, `50` | How much of the mutation report the console shows |
| `-Pcribro.pitestVersion=<v>` | `{{pitestVersion}}` | PIT version |
| `-Pcribro.pitestPlugins=<coordinates>` | none | Further PIT plugins on PIT's classpath, comma-separated, such as `com.arcmutate:pitest-kotlin-plugin:1.5.2` |
| `-Pcribro.jacocoVersion=<v>` | the build's, else `{{jacocoVersion}}` | JaCoCo version for the test JVM |

The tests PIT runs need JDK 11 or later.

A mutant is matched to a changed file as the coverage report matches it, by the package its class declares and the
file name the class records. Kotlin's compiler emits code the source does not show, such as null checks on parameters,
and PIT drops the mutants of the cases it knows. The rest are reported as live mutants on a changed line whose text
gives no hint; Arcmutate's Kotlin plugin (https://docs.arcmutate.com/docs/kotlin.html) filters more of them: `-Pcribro.pitestPlugins` adds
it, and it needs an Arcmutate licence file, `arcmutate-licence.txt`, at the repository root.

## Limits

- PIT runs only the tests of the module whose code it mutates. A mutant that only another module's tests kill is
  reported as surviving.
- PIT splits JVM arguments at commas and whitespace. The task leaves such arguments of the test task out and names them
  in its output; a test that depends on one may fail under PIT and drop out.
- PIT runs JUnit Jupiter tests one at a time, whatever the build sets for parallel execution.
- PIT runs only the `test` task's tests, not those of other `Test` tasks such as integration tests.
- Do not run two Gradle builds in the same project at once.
