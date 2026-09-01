# Win32 backend status

`jembetter-core-win32` mirrors `jembetter-core`'s X11 primitives 1:1 via
JNA's bundled `user32`/`kernel32` declarations (`Win32Reparent`,
`Win32WindowGeometry`, `Win32Focus`, `Win32WindowFinder`). Two more,
`Win32ReparentWatcher` and `Win32ClickWatcher`, have no 1:1 X11 primitive to
mirror — they stand in for event mechanisms X11 has and Win32 doesn't (see
[Mechanism notes](#mechanism-notes)).

Its tests are `@Tag("windows")`: excluded from the default `mvn test` fork
on Linux, but run both on real `windows-latest`
(`.github/workflows/windows-ci.yml`) and under Wine (a downloaded Windows
JDK, forked by the `windows-tests-on-linux` Surefire execution — see
`build-tools/test-jvm-wrapper/`).

## Confirmed working

| Primitive / behaviour | Confirmed by |
| --- | --- |
| `SetParent`+style-flip+poll-verify, embedding a real AWT `Canvas` HWND | Real `windows-latest`, 2026-08-26 spike |
| `SetForegroundWindow` foreground-lock actually bites (can return `true` without moving the foreground) | Real `windows-latest`, 2026-08-26 spike |
| `Win32Focus.set`'s `AttachThreadInput` fallback moves the foreground when a plain call doesn't | Real `windows-latest`, 2026-08-28 follow-up |
| `ProcessHandle.onExit()` for a foreign (not self-spawned) pid | Real `windows-latest`, 2026-08-26 spike |
| `AF_UNIX` rendezvous between two JVMs | Real `windows-latest`, 2026-08-26 spike |
| `Win32ReparentWatcher`: all 3 transitions (embed, host-detach, parent-destroy) | Real `windows-latest`, 2026-08-28 follow-up |
| Click-to-focus hook install, hit-test, clean unhook | `Win32ClickWatcherTest` under Wine |
| Click-to-focus survives an injected-click burst; latency a few µs/event | Real `windows-latest`, 2026-08-28 follow-up |
| Destroying-close (`WM_CLOSE` instead of cross-process `DestroyWindow`) | `EmbedHostWin32Test` under Wine only |

`embed`/`embedOpaque` need no distinction on this backend — both collapse
into the same operation, since there's no `_XEMBED_INFO` to make them
differ.

**Still unconfirmed:** UIPI blocking the click-to-focus hook against a
higher-integrity-level target (the CI runner is itself elevated, so that
direction can't be exercised), and `explorer.exe`/`dwm.exe` quirks specific
to a Windows version beyond what the spikes above covered.

## Not yet implemented (no OS-level blocker)

`EmbedSocket`/`EmbedClient` (the advanced, multi-client X11 API — see
[Advanced usage](advanced-usage.md)) have no Win32 counterpart at all yet.
None of this is blocked by a Windows API restriction:

- **Multi-client reuse of one socket** — `EmbedSocket#listen` keeps
  accepting a new client after a detach. Plain bookkeeping over
  `Win32Reparent`.
- **Voluntary host-initiated detach** — `Win32Reparent#release` (the reverse
  of `reparent`) already exists as a primitive, but nothing wires it into a
  Win32 `EmbedSocket`/`detachClient` equivalent yet, and it's still
  unconfirmed on a real machine (only the embed direction was spiked).
- **Focus-next/prev tab-cycling** between multiple embedded clients.
- **Modality signaling** — X11 does this over `_XEMBED_INFO`/XEmbed
  `ClientMessage`s, which Win32 has nothing like. The `AF_UNIX` channel
  already used for the pid handshake could carry an equivalent, though.
- **`onFocusChanged` for toolkit-opaque clients** — X11's `EmbedClient`
  reads real `FocusIn`/`FocusOut` off its own X11 connection. A Win32
  equivalent needs a global hook in `Win32ClickWatcher`'s family (e.g.
  `SetWinEventHook`/`EVENT_OBJECT_FOCUS`), not yet built.

## Will never match X11

Both rooted in the same Win32 rule: only the thread that owns a window may
destroy it, and a `WS_CHILD` window dies unconditionally with its parent.

- **Forced destroy stays best-effort.** `XDestroyWindow` works from any X11
  connection, against any window. `DestroyWindow` doesn't cross processes —
  a direct cross-process call silently returns `FALSE` and leaves the window
  intact. `Win32Window#destroy` posts `WM_CLOSE` instead, which only closes
  the window if its own handler doesn't override that default.
  `EmbedHost#close(true)`/`EmbedSocket#destroyClient()`'s unconditional X11
  guarantee has no Win32 equivalent — structurally can't become one.
- **The embedded window never survives a host crash.** X11 has a save-set: a
  released child is reparented back to root and stays alive. Win32 has
  nothing like it — a parent HWND's destruction takes its children with it,
  unconditionally. `Win32ReparentWatcher` still detects this (and
  `onHostDetached` still fires correctly), but the specific embedded HWND is
  already gone by then. A client that wants to keep running visibly has to
  build a new top-level window, not recover the old one.

## Mechanism notes

**Click-to-focus.** X11's `EmbedSocket` returns focus to the embedded
client on a real click back into the embedded area — a passive `XGrabButton`
intercepts the press before the client's own toolkit sees it, then replays
it (see `EmbedSocket#open(Canvas)`'s Javadoc).

Win32 can't do the same trick: ordinary subclassing (`SetWindowSubclass`)
can't reach into a genuinely separate process's HWND, since its callback
would have to run on the *target* thread. `Win32ClickWatcher` instead
installs a low-level mouse hook (`SetWindowsHookEx(WH_MOUSE_LL, ...)`, which
runs in the *hooking* process — no DLL injected into the embedded process).
It watches every `WM_LBUTTONDOWN` system-wide, hit-tests it against the
embedded HWND's rect, and calls `Win32Focus.set` on a hit. That makes it
observe-and-react rather than X11's intercept-and-replay — a low-level hook
never blocks the click it observes, so there's nothing to replay.

**Foreground-lock fallback.** A plain `SetForegroundWindow` can return
`true` without the foreground actually moving, so `Win32Focus.set` verifies
with `GetForegroundWindow` instead of trusting the return value. On
failure, it falls back to `AttachThreadInput` to the foreground thread, then
`SetForegroundWindow`/`BringWindowToTop`/`SetFocus` — the one strategy the
2026-08-28 spike confirmed actually moves the foreground from a
non-foreground process. See `Win32Focus`'s Javadoc.

**Reparent watching is poll-based.** X11's `WindowReparentWatcher` is
event-driven; `Win32ReparentWatcher` isn't, because Win32 has no
externally-observable reparent event to wait on instead.

## Test wiring

`EmbedHostWin32Test`/`EmbedPlugWin32Test`/`Win32ReparentWatcherTest`/
`Win32ClickWatcherTest` cover the mechanisms above, `@Tag("windows")` like
the rest of this module's tests. They run on every push, both on real
`windows-latest` (`.github/workflows/windows-ci.yml`) and under Wine
(`.github/workflows/linux-ci.yml`).

What Wine can't replicate closely enough — foreground-lock policy,
cross-process reparent/DWM behaviour, `WH_MOUSE_LL` under an
injected-input burst — is covered instead by
[`build-tools/win32-real-machine-checks`](../build-tools/win32-real-machine-checks/README.md),
standalone checks run by hand against a real Windows machine.

`maven-surefire-plugin`'s `<jvm>` wrapper
(`build-tools/test-jvm-wrapper/bin/java`) only applies under an
`os.family=unix`-activated Maven profile — it's a bash script, and Windows
can't launch it as the forked test JVM's executable at all. Plain `mvn test`
uses the default fork there instead.
