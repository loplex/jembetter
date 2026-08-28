# Win32 real-machine checks

Real-Windows regression checks for the `jembetter-core-win32` primitives, in
the situations the reactor's `@Tag("windows")` unit suite **can't** cover:

- behaviour under Windows' **foreground-lock** policy (needs a second process
  holding the foreground; Wine doesn't replicate the policy);
- **cross-process** reparent watching and the parent-destroy-kills-child
  asymmetry, against the real DWM;
- the `WH_MOUSE_LL` hook under a **burst** of injected input (real
  `LowLevelHooksTimeout`, real added latency).

They live outside the Maven reactor deliberately: they manipulate global
window focus/foreground state and inject system-wide input, which doesn't
belong interleaved with the reactor's other GUI tests, and several can't run
under the Wine fork at all. Plain `.java` files compiled and run directly
against `jembetter-core-common`/`jembetter-core-win32`'s already-`mvn
compile`d classes plus the `jna`/`jna-platform` jars from the local Maven
repo.

## The checks

| Class | Checks | Verdict |
| --- | --- | --- |
| `FocusFallbackCheck` | From a non-foreground process, does the production `Win32Focus.set` move the foreground? Runs a strategy matrix and names what works if it doesn't. | **Gated** PASS/FAIL (PASS only if `Win32Focus.set` works unaided) |
| `ReparentWatcherCheck` | `Win32ReparentWatcher` across embed / host-detach / parent-destroy, with a separate-JVM client window. | **Gated** PASS/FAIL (three transitions) |
| `ClickWatcherCaveatsCheck` | `Win32ClickWatcher`: hook survival under a click burst (gated); added mouse latency + UIPI (observational). | hook-survival **gated**; latency + UIPI observational |
| `ForegroundLockCheck` | Prints the raw foreground-lock behaviour (what `SetForegroundWindow` returns vs. does, `AllowSetForegroundWindow`, …). | **Observational** — no verdict |

Helper processes: `ForegroundStealerMain` (grabs the foreground so the checks
have a non-foreground state to test against), `ChildWindowMain` (a
separate-JVM client window), `CheckWindows` (STATIC-class windows + SendInput
synthesis, copied from `jembetter-core-win32`'s `Win32TestWindows`).

## Running it

On a real Windows machine with a JDK 21+ (`JAVA_HOME`, or `java`/`javac` on
`PATH`) and this repo checked out:

```powershell
mvn install   # at least once, so jna/jna-platform are in ~/.m2
.\build-tools\win32-real-machine-checks\run.ps1
```

The script's exit code is gated on `FocusFallbackCheck`,
`ReparentWatcherCheck` and `ClickWatcherCaveatsCheck`'s hook-survival part.
`ForegroundLockCheck` and the latency/UIPI lines are observational — read
them yourself.

`.github/workflows/win32-real-machine-checks.yml` runs this on
`windows-latest`: on `workflow_dispatch`, on pushes to `main`/`develop` that
touch `jembetter-core-win32`/`-common` main code or this directory, and
weekly on a cron (to catch drift in the runner image / JDK).

## History

Grew out of a 2026-08 throwaway spike (`Question1`–`Question7`) that answered
the open Win32-backend design questions on real `windows-latest` — findings
folded into `docs/win32-status.md` and the `jembetter-core-win32` Javadoc.
The four questions worth keeping as ongoing regression checks were kept and
renamed; `SetParent` (question 1), foreign-pid `onExit` (3) and `AF_UNIX`
(4) were dropped because `EmbedHostWin32Test` / `PidHandshakeTest` already
cover them on real Windows via `windows-ci.yml`.

The one caveat still genuinely unverified is UIPI against a higher-integrity
target — the CI runner process is itself elevated, so it can't exercise the
blocking direction.
