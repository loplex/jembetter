# Win32 backend status

`jembetter-core-win32` has `Win32Reparent`/`Win32WindowGeometry`/`Win32Focus`/
`Win32WindowFinder`, mirroring `jembetter-core`'s X11 primitives 1:1
(`SetParent`+style-flip, `MoveWindow`/`ShowWindow`, `SetFocus`,
`EnumWindows`+`GetWindowThreadProcessId`). Its JUnit tests are gated with
`@EnabledOnOs(OS.WINDOWS)`, so they're skipped (not run, not failed) by this
repo's own `mvn test` on Linux.

`.mvn/win32-wine-smoketest/run.sh` had already confirmed the JNA bindings
link against real `user32.dll`/`kernel32.dll` entry points under Wine, and
that basic `SetParent`/`MoveWindow`/`EnumWindows` mechanics plausibly work.
A one-off real-machine spike, run against real `windows-latest` CI on
2026-08-26, then confirmed, on genuine Windows rather than Wine, the
following:

1. `SetParent`+style-flip+poll-verify between a real AWT `Canvas` HWND and a
   separate JVM's window — **confirmed working**.
2. `SetFocus`/`SetForegroundWindow`'s foreground-lock restriction from a
   non-foreground process — **confirmed to actually bite** (a silent
   no-op; `SetForegroundWindow` can even return `true` without the
   foreground actually changing). `Win32Focus.set` now falls back to
   `AttachThreadInput` to work around it — see `Win32Focus`'s Javadoc for
   what the spike itself confirmed versus what's an implementation choice
   made afterward.
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

Two things this wiring adds that the spike itself didn't exercise, both
reasoned about from documented Win32 semantics rather than confirmed live —
worth a dedicated real-machine check if either ever needs to be verified
rather than reasoned about:

- `Win32Focus.set`'s `AttachThreadInput` fallback (see its own Javadoc) —
  the spike only tried the `AllowSetForegroundWindow` workaround.
- `Win32ReparentWatcher`, a poll-based stand-in for X11's event-driven
  `WindowReparentWatcher` that `EmbedPlugWin32` uses to detect being embedded
  and the host detaching, since Win32 has no externally-observable reparent
  event and no save-set mechanism — destroying a parent HWND destroys its
  children outright, unlike X11 reparenting a released child back to the
  root window alive. See `Win32ReparentWatcher`'s and `EmbedPlugWin32`'s
  Javadoc for that asymmetry.

`EmbedHostWin32Test`/`EmbedPlugWin32Test`/`Win32ReparentWatcherTest` cover
this wiring, gated `@EnabledOnOs(OS.WINDOWS)` like the rest of this module's
tests, and run on every push via `.github/workflows/windows-ci.yml`, a
persistent `windows-latest` job. `.github/workflows/linux-ci.yml` runs the
same reactor's tests the other way, against a real Xvfb + openbox pair.

`maven-surefire-plugin`'s `<jvm>` wrapper (see `.mvn/xserver-jvm-wrapper/bin/java`,
in the repo root) only applies under an `os.family=unix`-activated Maven
profile: it's a bash script, and Windows can't launch it as the forked test
JVM's executable at all, so plain `mvn test` needs the default fork there
instead.
