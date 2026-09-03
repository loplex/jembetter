# Win32 real-machine checks

Real-Windows regression checks for the `jembetter-core-win32` primitives and
the `jembetter-host`/`jembetter-client` classes built on them, in the
situations the reactor's `@Tag("windows")` unit suite **can't** cover:

- behaviour under Windows' **foreground-lock** policy (needs a second process
  holding the foreground; Wine doesn't replicate the policy);
- **cross-process** reparent watching and the parent-destroy-kills-child
  asymmetry, against the real DWM;
- the `WH_MOUSE_LL` hook under a **burst** of injected input (real
  `LowLevelHooksTimeout`, real added latency);
- `EmbedSocketWin32` (host) and `EmbedClientWin32` (client) run **against
  each other** — the two modules don't depend on one another, so the reactor
  can only test each against a hand-rolled stand-in for the other, and only
  under Wine;
- click-to-focus **through the `EmbedHost` facade** against a real embedded
  client, with a real injected click.

They live outside the Maven reactor deliberately: they manipulate global
window focus/foreground state and inject system-wide input, which doesn't
belong interleaved with the reactor's other GUI tests, several can't run
under the Wine fork at all, and two of them (`SocketClientWin32Check`,
`ClickToFocusWin32Check`) cross the `jembetter-host`/`jembetter-client`
module boundary in a way no single reactor module can. Plain `.java` files
compiled and run directly against `jembetter-host`/`jembetter-client` (and
their `jembetter-core-*` deps) already-`mvn compile`d classes plus the
`jna`/`jna-platform`/`slf4j-api` jars from the local Maven repo.

## The checks

| Class | Checks | Verdict |
| --- | --- | --- |
| `FocusFallbackCheck` | From a non-foreground process, does the production `Win32Focus.set` move the foreground? Runs a strategy matrix and names what works if it doesn't. | **Gated** PASS/FAIL (PASS only if `Win32Focus.set` works unaided) |
| `FocusWatcherCheck` | Does `Win32FocusWatcher` actually receive `EVENT_OBJECT_FOCUS`, same-process and for a separate-JVM client window (the `EmbedPlugWin32` shape)? | **Gated** PASS/FAIL (same-process gain + cross-process gain/loss) |
| `ReparentWatcherCheck` | `Win32ReparentWatcher` across embed / host-detach / parent-destroy, with a separate-JVM client window. | **Gated** PASS/FAIL (three transitions) |
| `ClickWatcherCaveatsCheck` | `Win32ClickWatcher`: hook survival under a click burst (gated); added mouse latency + UIPI (observational). | hook-survival **gated**; latency + UIPI observational |
| `SocketClientWin32Check` | `EmbedSocketWin32`↔`EmbedClientWin32` end to end: embed, `setModal` both ways → `onModalityChanged`, client `requestFocus()` → host grants it, host-canvas resize → client `onResized`, `detachClient()` → `onHostDetached`, re-embed a second client on the same socket. | **Gated** PASS/FAIL (six transitions) |
| `ClickToFocusWin32Check` | Click-to-focus through `EmbedHost.create` against a real embedded client: a real `SendInput` click into the embedded rect returns keyboard focus to the client via `Win32ClickWatcher`. | **Gated** PASS/FAIL |
| `ForegroundLockCheck` | Prints the raw foreground-lock behaviour (what `SetForegroundWindow` returns vs. does, `AllowSetForegroundWindow`, …). | **Observational** — no verdict |

Helper processes: `ForegroundStealerMain` (grabs the foreground so the checks
have a non-foreground state to test against), `ChildWindowMain` (a
separate-JVM client window, used by `ReparentWatcherCheck`,
`FocusWatcherCheck` and `ClickToFocusWin32Check`), `EmbedClientProcessMain`
(a separate-JVM client running a real `EmbedClientWin32`, used by
`SocketClientWin32Check`), `CheckWindows` (STATIC-class windows + SendInput
synthesis + a `GetGUIThreadInfo` focus probe, copied from
`jembetter-core-win32`'s `Win32TestWindows`).

## Running it

On a real Windows machine with a JDK 21+ (`JAVA_HOME`, or `java`/`javac` on
`PATH`) and this repo checked out:

```powershell
mvn install   # at least once, so jna/jna-platform are in ~/.m2
.\build-tools\win32-real-machine-checks\run.ps1
```

The script's exit code is gated on `FocusFallbackCheck`, `FocusWatcherCheck`,
`ReparentWatcherCheck`, `ClickWatcherCaveatsCheck`'s hook-survival part,
`SocketClientWin32Check` and `ClickToFocusWin32Check`. `ForegroundLockCheck`
and the latency/UIPI lines are observational — read them yourself.

`.github/workflows/win32-real-machine-checks.yml` runs this on
`windows-latest`: on `workflow_dispatch`, on pushes to `main`/`develop` that
touch `jembetter-core-win32`/`-common`/`-host`/`-client` main code or this
directory, and weekly on a cron (to catch drift in the runner image / JDK).

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
