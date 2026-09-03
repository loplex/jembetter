# Win32 backend status

`jembetter-core-win32` mirrors `jembetter-core-x11`'s primitives 1:1 via
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
| Voluntary host-initiated detach (`Win32Reparent#release`, `EmbedSocketWin32#detachClient`) | `EmbedSocketWin32Test` under Wine only |
| Multi-client reuse of one socket (`EmbedSocketWin32#listen`, accept-loop re-embed after a detach) | `EmbedSocketWin32Test` under Wine only |
| `EmbedSocketWin32#setModal` opcode delivery, end-to-end (`EmbedSocketWin32#listen`'s control channel → `EmbedClientWin32#onModalityChanged`) | `EmbedSocketWin32Test` (send side) + `EmbedClientWin32Test` (receive side) under Wine only |
| `EmbedPlugWin32#onFocusChanged` via `Win32FocusWatcher`'s `GetGUIThreadInfo` poll loop, same-process and cross-process | `Win32FocusWatcherTest`/`EmbedPlugWin32Test` under Wine + real `windows-latest`, 2026-09-02 follow-up |
| `EmbedClientWin32`'s own reparent/focus/resize watching (`announce`/`offer`, `onEmbedded`/`onHostDetached`/`onFocusChanged`/`onResized`) and `requestFocus()` (a new `FocusRequestOpcode` marker byte on the same control channel `setModal` already uses, the other direction — read by a new `EmbedSocketWin32` control-channel reader) | `EmbedClientWin32Test`/`EmbedSocketWin32Test`/`Win32ConfigureWatcherTest` under Wine only |

`embed`/`embedOpaque` need no distinction on this backend — both collapse
into the same operation, since there's no `_XEMBED_INFO` to make them
differ.

**Still unconfirmed:** UIPI blocking the click-to-focus hook against a
higher-integrity-level target (the CI runner is itself elevated, so that
direction can't be exercised), and `explorer.exe`/`dwm.exe` quirks specific
to a Windows version beyond what the spikes above covered.

`Win32FocusWatcher`'s poll-based mechanism (see
[Mechanism notes](#mechanism-notes)) replaced an earlier
`SetWinEventHook(EVENT_OBJECT_FOCUS, ...)` design that a real-machine check
caught never actually delivering — full story in Mechanism notes below.

## Not yet implemented (no OS-level blocker)

`EmbedSocket`/`EmbedClient` (the advanced, multi-client X11 API — see
[Advanced usage](advanced-usage.md)) have no full Win32 counterpart yet. A
new `EmbedSocketWin32` (`jembetter-host`) has started growing toward one —
see its Javadoc for exactly what it covers so far. None of the remaining
gaps are blocked by a Windows API restriction:

- **Focus-next/prev tab-cycling** between multiple embedded clients.
  Deliberately not chased on Win32 either: X11's own `EmbedSocket#onFocusNext`/
  `onFocusPrev` are receiver-only code with no sender anywhere in this
  codebase — `EmbedClient`/`EmbedPlug` never send `XEMBED_FOCUS_NEXT`/`PREV`,
  only a genuinely XEmbed-aware external toolkit (e.g. GTK) would — so
  there's no working X11 shape to mirror in the first place, and building a
  Win32-only version nothing calls either would be new dead code, not parity.
- **`onModalityChanged` is not reachable from the narrow `EmbedPlug` facade.**
  `EmbedPlugWin32#announce(Path, String)` still closes its handshake channel
  immediately after sending the pid, unaffected by `EmbedClientWin32`'s
  existence — a caller wanting modality delivery through `EmbedPlug` has to
  use `EmbedClientWin32` directly instead (or alongside it) rather than
  through `EmbedPlug#announce(Path, String)`. Wiring it into the narrow
  facade would mean changing `announce(Path, String)`'s own channel-lifetime
  behavior, which today is tested and documented the other way; deliberately
  scoped down rather than done as a side effect of adding the receiver.

## Will never match X11

Both rooted in the same Win32 rule: only the thread that owns a window may
destroy it, and a `WS_CHILD` window dies unconditionally with its parent.

- **Forced destroy stays best-effort.** `XDestroyWindow` works from any X11
  connection, against any window. `DestroyWindow` doesn't cross processes —
  a direct cross-process call silently returns `FALSE` and leaves the window
  intact. `Win32Window#destroy` posts `WM_CLOSE` instead, which only closes
  the window if its own handler doesn't override that default.
  `EmbedHost#tryDestroy()`/`EmbedSocket#destroyClient()`'s unconditional X11
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

**Client-side focus notification.** X11's `EmbedClient` reads real,
server-generated `FocusIn`/`FocusOut` off its own X11 connection — any
connection selecting for them on a window receives them, regardless of
which connection actually changed the focus (see `WindowFocusWatcher`'s
Javadoc). An embedded child HWND's `WM_SETFOCUS`/`WM_KILLFOCUS` have no
Win32 equivalent: they're delivered only inside that window's own message
loop, invisible cross-process.

A first `Win32FocusWatcher` (2026-09-01) tried mirroring `Win32ClickWatcher`'s
shape with a different hook: `SetWinEventHook(EVENT_OBJECT_FOCUS, ...)`, a
`WINEVENT_OUTOFCONTEXT` system-wide accessibility hook (no DLL injected
anywhere), on the assumption that it would receive an event for every window
in the system gaining focus regardless of which process or thread caused it.
A 2026-09-02 real-machine run of `FocusWatcherCheck` (`build-tools/
win32-real-machine-checks`) disproved that: it called `Win32Focus.set` on a
watched window, confirmed via `GetGUIThreadInfo` that the window genuinely
now held focus, and the hook's callback still never fired — on real
`windows-latest`, not just under Wine. `EVENT_OBJECT_FOCUS` turns out to be
an Active Accessibility notification a window's own message handling has to
raise itself via `NotifyWinEvent`; standard common controls (edit, button,
...) do that internally on `WM_SETFOCUS`, but a plain top-level window
(whether a bare `STATIC` HWND or a real AWT/Swing peer, this class's actual
target) never does, so the hook had nothing to ever receive.

`Win32FocusWatcher` was rewritten to poll `GetGUIThreadInfo` directly
instead — the same call the check used to catch the bug, and the same
poll-based shape `Win32ReparentWatcher` already uses for its own "no
observable event" gap (see above). Only genuine transitions are reported,
the same deduplication `WindowFocusWatcher` does: a watched window never
before seen as focused doesn't get a spurious initial "lost" callback, and a
repeat poll of an unchanged state fires nothing. `EmbedPlugWin32#onFocusChanged`
wires this to watch the client's own window; a host process's `SetFocus` on
that window via `Win32Focus.set`'s `AttachThreadInput` path is visible to
`GetGUIThreadInfo` the same as any other focus change, cross-process,
without needing its own `AttachThreadInput` bridge. A follow-up real-machine
run of `FocusWatcherCheck` against this replacement confirmed all three
cases (same-process gain, cross-process gain, cross-process loss) on real
`windows-latest`.

## Test wiring

`EmbedHostWin32Test`/`EmbedSocketWin32Test`/`EmbedPlugWin32Test`/
`EmbedClientWin32Test`/`Win32ReparentWatcherTest`/`Win32ClickWatcherTest`/
`Win32FocusWatcherTest` cover the mechanisms above, `@Tag("windows")` like
the rest of this module's tests. They run on every push, both on real `windows-latest`
(`.github/workflows/windows-ci.yml`) and under Wine
(`.github/workflows/linux-ci.yml`).

What Wine can't replicate closely enough — foreground-lock policy,
cross-process reparent/DWM behaviour, `WH_MOUSE_LL` under an
injected-input burst — is covered instead by
[`build-tools/win32-real-machine-checks`](../build-tools/win32-real-machine-checks/README.md),
standalone checks run by hand against a real Windows machine.
`SetWinEventHook`/`EVENT_OBJECT_FOCUS` delivery has no check there yet
either — same "still unconfirmed" gap noted above, not just a Wine-vs-CI
split.

`maven-surefire-plugin`'s `<jvm>` wrapper
(`build-tools/test-jvm-wrapper/bin/java`) only applies under an
`os.family=unix`-activated Maven profile — it's a bash script, and Windows
can't launch it as the forked test JVM's executable at all. Plain `mvn test`
uses the default fork there instead.
