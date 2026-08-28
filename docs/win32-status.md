# Win32 backend status

`jembetter-core-win32` has `Win32Reparent`/`Win32WindowGeometry`/`Win32Focus`/
`Win32WindowFinder`, mirroring `jembetter-core`'s X11 primitives 1:1
(`SetParent`+style-flip, `MoveWindow`/`ShowWindow`, `SetFocus`,
`EnumWindows`+`GetWindowThreadProcessId`), plus `Win32ReparentWatcher` and
`Win32ClickWatcher`, which have no 1:1 X11 primitive to mirror since they
stand in for event mechanisms X11 has and Win32 doesn't. Its JUnit tests
are `@Tag("windows")`: excluded from the default `mvn test` fork on Linux,
but run two other ways — on real `windows-latest` via
`.github/workflows/windows-ci.yml`, and under Wine (a downloaded Windows
JDK, forked by the `windows-tests-on-linux` surefire execution wherever
`wine` is installed — see `build-tools/test-jvm-wrapper/`).

Running those tests under Wine had already confirmed the JNA bindings link
against real `user32.dll`/`kernel32.dll` entry points, and that basic
`SetParent`/`MoveWindow`/`EnumWindows` mechanics plausibly work. A one-off
real-machine spike, run against real `windows-latest` CI on 2026-08-26,
then confirmed, on genuine Windows rather than Wine, the following:

1. `SetParent`+style-flip+poll-verify between a real AWT `Canvas` HWND and a
   separate JVM's window — **confirmed working**.
2. `SetFocus`/`SetForegroundWindow`'s foreground-lock restriction from a
   non-foreground process — **confirmed to actually bite** (a silent
   no-op; `SetForegroundWindow` can even return `true` without the
   foreground actually changing). A 2026-08-28 follow-up spike compared
   workarounds head to head and **confirmed** that `AttachThreadInput` to
   the foreground thread + `SetForegroundWindow`/`BringWindowToTop`/`SetFocus`
   does move the foreground, where a plain (even retried) `SetForegroundWindow`
   does not. `Win32Focus.set` verifies the result with `GetForegroundWindow`
   and uses that fallback — see its Javadoc.
3. `ProcessHandle.onExit()` for a foreign (not self-spawned) pid —
   **confirmed reliable**.
4. `AF_UNIX` rendezvous between two JVMs on Windows — **confirmed working**.

Windows-version-specific `explorer.exe`/`dwm.exe` policy quirks beyond what
the spike exercised remain unconfirmed.

`os.name` dispatch now wires these primitives into `EmbedHost`/`EmbedPlug`
(`EmbedHostWin32`/`EmbedPlugWin32`), settling the two design questions that
were still open pending this spike: host-initiated reparent stays symmetric
with X11 (confirmed by question 1 above), and `embedOpaque`/`embed` collapse
into the exact same `SetParent`+poll-verify operation on this backend, since
there's no `_XEMBED_INFO` equivalent to make them differ. `EmbedSocket`/
`EmbedClient` (the advanced, multi-client X11 API) have no Win32 counterpart.

A 2026-08-28 follow-up round then exercised the pieces the first round
hadn't, and both are now **confirmed on real Windows** (that harness has
since been kept as the ongoing `build-tools/win32-real-machine-checks/`,
run on `windows-latest` per its own workflow):

- `Win32Focus.set`'s `AttachThreadInput` fallback (see its own Javadoc) —
  the strategy matrix confirmed it moves the foreground where a plain
  `SetForegroundWindow` (even retried) does not; the plain call's `true`
  return is not trustworthy, so `Win32Focus.set` verifies with
  `GetForegroundWindow` instead.
- `Win32ReparentWatcher`, a poll-based stand-in for X11's event-driven
  `WindowReparentWatcher` that `EmbedPlugWin32` uses to detect being embedded
  and the host detaching, since Win32 has no externally-observable reparent
  event and no save-set mechanism — destroying a parent HWND destroys its
  children outright, unlike X11 reparenting a released child back to the
  root window alive. The spike watched all three transitions (embed, host
  detach, parent-destroy) and the watcher fired correctly for each,
  including the destroy that took the embedded child with it. See
  `Win32ReparentWatcher`'s and `EmbedPlugWin32`'s Javadoc for that asymmetry.

**Click-to-focus (implemented, Wine-tested, caveats partly real-machine
spiked).** X11's `EmbedSocket` returns input focus to the embedded client
automatically on a real click back into the embedded area (a passive
`XGrabButton` that intercepts the press before the client's own toolkit
sees it, then replays it — see `EmbedSocket#open(Canvas)`'s Javadoc).
`EmbedHostWin32` now has an equivalent, `Win32ClickWatcher`: since ordinary
subclassing (`SetWindowSubclass`) can't reach across into a genuinely
separate process's HWND the way this backend embeds one (it installs a
callback the *target thread* would have to execute, only within the
subclassing process's own address space), it instead installs a low-level
mouse hook (`SetWindowsHookEx(WH_MOUSE_LL, ...)`, which runs in the
*hooking* process, unlike a non-low-level hook, so it needs no DLL injected
into the embedded process): watches every `WM_LBUTTONDOWN` system-wide,
checks whether it lands inside the embedded HWND's rect, and calls
`Win32Focus.set` if so — structurally an observe-and-react mechanism rather
than X11's intercept-and-replay one (nothing to replay; a low-level hook
never blocks the click it observes).

`Win32ClickWatcherTest` (with real injected clicks via `SendInput`), run
under Wine by the `windows-tests-on-linux` execution, confirms the hook
installs, the `LowLevelMouseProc`/`MSLLHOOKSTRUCT` marshaling works, the
message pump dispatches, the hit-test is correct, and `close()` unhooks
cleanly. Wine can't replicate the documented `SetWindowsHookEx` caveats, so
the 2026-08-28 follow-up spike checked them on real Windows: under a burst
of injected clicks the dispatch-thread offload kept the hook proc under
`LowLevelHooksTimeout` (every click reached the callback), and the added
system-wide mouse latency measured at a few microseconds per event —
negligible on that runner. UIPI blocking the hook against a
higher-integrity-level target is still unspiked (the CI runner process is
itself elevated, so the blocking direction can't be exercised) — see
`EmbedHostWin32`'s and `Win32ClickWatcher`'s own Javadoc.

`EmbedHostWin32Test`/`EmbedPlugWin32Test`/`Win32ReparentWatcherTest`/
`Win32ClickWatcherTest` cover this wiring, `@Tag("windows")` like the rest
of this module's tests, and run on every push via
`.github/workflows/windows-ci.yml`, a persistent `windows-latest` job.
`.github/workflows/linux-ci.yml` runs the same reactor's tests against a
real Xvfb + openbox pair, and the `@Tag("windows")` ones additionally under
Wine.

**Destroying-close (`EmbedHost#close(boolean)`/`EmbedHostWin32`, implemented,
Wine-tested, real-machine unspiked).** `Win32Window#destroy` was originally
written as a direct `DestroyWindow` call, mirroring X11's `RawWindow#destroy`
1:1 the way the rest of this module's primitives do — but unlike
`XDestroyWindow`, which any X11 connection can issue against any window,
Win32's `DestroyWindow` can only be called by the thread that created the
window. A direct cross-process call against the embedded client's HWND
silently returns `FALSE` and leaves the window intact; caught by
`EmbedHostWin32Test`'s own coverage under Wine before this ever reached real
Windows CI. `Win32Window#destroy` now posts `WM_CLOSE` instead, which is
cross-process-safe (delivered via the target's own message queue) and
results in `DestroyWindow` running correctly on the window's own thread —
but only when the target's own `WM_CLOSE` handling doesn't override the
default of destroying itself, so unlike the X11 backend's unconditional
`XDestroyWindow`, this is best-effort, not guaranteed. See `Win32Window`'s
own Javadoc.

`maven-surefire-plugin`'s `<jvm>` wrapper (see `build-tools/test-jvm-wrapper/bin/java`,
in the repo root) only applies under an `os.family=unix`-activated Maven
profile: it's a bash script, and Windows can't launch it as the forked test
JVM's executable at all, so plain `mvn test` needs the default fork there
instead.
