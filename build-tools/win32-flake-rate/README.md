# Win32 flake rate

Repeats a Win32 test run many times on both platforms the `@Tag("windows")`
suite is exercised on — under Wine on Linux and on real `windows-latest` — and
tallies which tests failed in which iteration.

It answers two questions a normal CI run cannot.

**How often does this actually fail?** `linux-ci.yml` and `windows-ci.yml` each
run the suite once. A test that fails one run in three reads as red or green
depending on which run you happened to look at, and neither reading is the
truth. A rate is.

**Is the emulator to blame, or the test?** `@Tag("wine-incompatible")` excludes
a test from the Wine fork (the `win32-tests-under-wine` profile in the root
`pom.xml`), and it is a claim *about Wine*, not about the test. Judging a
failure "Wine-shaped" by eye works when Wine is consistently wrong and fails
when it is intermittently wrong — then an ordinary flaky test and a real Wine
gap look identical from a single log. Comparing rates across the two platforms
separates them.

## Running it

Two routes. The workflow measures both platforms and is the one that can reach
a verdict; the script by hand measures Wine alone, and is the one nothing
caps.

### From CI, via `workflow_dispatch`

The `Win32 flake rate` workflow:

| Input         | Meaning                                                | Default              |
|---------------|--------------------------------------------------------|----------------------|
| `test`        | Surefire `-Dtest` selector; empty runs the whole suite  | *(empty)*            |
| `iterations`  | Iterations per platform                                 | `10`                 |
| `platforms`   | `both`, `wine`, or `windows`                            | `both`               |
| `reuse-forks` | `false` runs one JVM per test class                     | `true`               |

`test` defaults to empty on purpose, so an unsupplied selector measures the
same thing a push does. It once defaulted to a test class name instead, and a
dispatch with the field cleared measured that one class while the verdict
read "Clean on both" — a true statement about one class that reads as a clean
whole suite.

Samples after the first are cheap — only the first iteration compiles.

`reuse-forks: false` is the shared-state question: several tests in one
Surefire fork can leave window, focus or foreground state behind for the ones
that follow, and a failure that disappears under one-JVM-per-class belongs to
test isolation rather than to the code under test. The real-machine checks in
[`win32-real-machine-checks`](../win32-real-machine-checks/README.md) run one
check per process for the same reason.

### Locally, by hand

`probe.sh` is an ordinary script and needs no CI. Run it from the repository
root — it drives `mvn` and writes per-iteration logs to `logs/` there, a path
`.gitignore` already carries for this:

```sh
build-tools/win32-flake-rate/probe.sh "" 40                 # whole suite
build-tools/win32-flake-rate/probe.sh EmbedClientWin32Test 10
```

The same tables land on stdout, and `logs/iteration-N.log` keeps each
iteration's full Maven output. `FORK_TIMEOUT_SECONDS`, `RUN_TIMEOUT_SECONDS`
and `REUSE_FORKS`, documented at the top of the script, are the knobs the
workflow sets for itself rather than exposing as dispatch inputs.

Two differences from the dispatch, both worth knowing before choosing this
route:

- **It measures Wine only.** `probe.ps1` is the real-Windows half and wants a
  Windows machine. Without both halves there is no cross-platform comparison,
  so a local run can establish that a test is flaky but cannot on its own
  justify `@Tag("wine-incompatible")` — see [Reading the
  result](#reading-the-result) for why that verdict needs the other platform.
- **Nothing caps it.** Both measuring jobs carry `timeout-minutes: 90`, and a
  whole-suite Wine run of 40 iterations has been observed to need most of
  that. Wine costs roughly four times as much per iteration as real Windows
  does, so the Wine job is the one that runs out of room first. A local run
  has no ceiling, which makes it the route for an iteration count the job
  cannot fit.

## Reading the result

The per-test table gives each failing test's rate. The per-iteration table adds
exit code, wall-clock duration and how many tests ran, each worth reading
before hunting for a logic bug: failures that cluster in slow iterations point
at load sensitivity instead, and a tests column of zero means the run measured
nothing at all.

That last one is the failure mode to know about. A `-Dtest` selector matching
no class exits 0 under `-Dsurefire.failIfNoSpecifiedTests=false`, so every
iteration passes, no test is tallied, and the verdict would otherwise read
"clean on both" - the strongest result the tool can print, over a run that
tested nothing. Each half now marks such an iteration `<no tests ran>`,
annotates the job, and reports its test count to the verdict, which refuses to
conclude anything when either platform's count is zero. Full logs are attached as artifacts, and so is any
`hs_err_pid*.log` a crashed fork left behind: the probe moves those into
the same place after each iteration, named for the iteration that produced
them.

The verdict job then compares the platforms:

- **Clean on real Windows, not under Wine** — a genuine Wine limitation, and
  what justifies `@Tag("wine-incompatible")` on the tests the tables name.
  Write down *what* Wine does differently next to the tag, not merely that it
  failed.
- **Not clean on real Windows either** — the test or the code under it is at
  fault whatever Wine reports. The job fails on this, so the tag cannot end up
  looking justified by a run that did not justify it.
- **Clean on both** — nothing to exclude.
- **Nothing was measured** — one of the probes ran no tests, so none of the
  above applies. Fix the selector and run it again.

## Hangs

A hung fork must cost one iteration, not the measurement. Both halves therefore
cap a run twice, and on current evidence the outer cap is the one that does the
work on **both** platforms.

`-Dsurefire.timeout` (`FORK_TIMEOUT_SECONDS`, 120 s) is the inner cap: Surefire
kills a fork that stops making progress and records it as a failure, so Maven
finishes the iteration normally and the loop continues. The tally shows it as
`<fork timeout>`.

`RUN_TIMEOUT_SECONDS` (600 s) is the outer cap — the probe kills Maven itself
and records `<killed at Ns>`. It exists because the inner cap has now been
observed to miss a wedged fork on each platform:

- **Under Wine**, measured 2026-09-13: with `-Dsurefire.timeout=90`, a hung
  `EmbedClientWin32Test` iteration ran on until the outer cap ended it at 420 s.
  Surefire launches that fork through `build-tools/test-jvm-wrapper/bin/java`,
  which spawns an X server and then `wine`, so the process Surefire can signal
  is not the one that is stuck.
- **On real `windows-latest`**, observed 2026-09-13 in CI: a `EmbedClientWin32Test`
  fork outlived `-Dsurefire.timeout=120` and the job sat on its first iteration
  until `timeout-minutes`. Here the fork *is* Surefire's own plain fork, so the
  reason is not the wrapper — it is unexplained, and the outer cap is what keeps
  it from costing a whole job.

So budget `RUN_TIMEOUT_SECONDS`, not `FORK_TIMEOUT_SECONDS`, when working out
what a hung iteration costs and how much `timeout-minutes` a job needs.

Enough consecutive hangs still exhaust a job's own `timeout-minutes`. That is
fine — hanging every iteration is itself the answer, and it shows up in the
first few rows.

## Files

| File        | Role                                                            |
|-------------|-----------------------------------------------------------------|
| `probe.sh`  | Linux/Wine half — run, classify, tally, report                   |
| `probe.ps1` | Real-Windows half, kept deliberately symmetrical with `probe.sh` |

Both take `<-Dtest selector, or empty> <iterations>`, honour
`FORK_TIMEOUT_SECONDS` and `REUSE_FORKS`, write per-iteration lines plus two
summary tables, and exit 0 regardless of what failed — the tally is the result,
so a red iteration must not redden the step. Read the tables, not the exit code.
They run by hand outside CI too, which is the quickest way to check a change to
either one.

## Two pwsh traps

Both cost a full measurement before being pinned down, and `probe.ps1` guards
against them:

- GitHub's `pwsh` wrapper sets `$ErrorActionPreference = 'Stop'`. In a tallying
  loop a failing iteration is data, not an error, and on pwsh 7.3+ merging a
  native command's stderr under `Stop` can abort the step outright.
- Passed as a bare token, PowerShell splits `-Dsurefire.timeout=120` at the dot
  and Maven receives `-Dsurefire` plus `.timeout=120`, rejecting it as an
  unknown lifecycle phase. Arguments are splatted from an array instead, whose
  elements pass through verbatim.
