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
| `EmbedSocketWin32#setModal` send-only stub (opcode byte written into the `listen` control channel) | `EmbedSocketWin32Test` under Wine only — see "Not yet implemented" for why nothing receives it yet |
| `EmbedPlugWin32#onFocusChanged` via a system-wide `SetWinEventHook(EVENT_OBJECT_FOCUS, ...)` — hook install/unwatch/close only | `Win32FocusWatcherTest`/`EmbedPlugWin32Test` under Wine |

`embed`/`embedOpaque` need no distinction on this backend — both collapse
into the same operation, since there's no `_XEMBED_INFO` to make them
differ.

**Still unconfirmed:** UIPI blocking the click-to-focus hook against a
higher-integrity-level target (the CI runner is itself elevated, so that
direction can't be exercised), `explorer.exe`/`dwm.exe` quirks specific to a
Windows version beyond what the spikes above covered, and real
`EVENT_OBJECT_FOCUS` delivery to `Win32FocusWatcher` — Wine's
`SetWinEventHook` emulation never delivers one (see
[Mechanism notes](#mechanism-notes)), so this is reasoned by analogy with
the already-confirmed click-to-focus hook rather than spiked on a real
machine yet; `Win32FocusWatcherTest`'s and `EmbedPlugWin32Test`'s event-
delivery cases are `@Tag("wine-incompatible")` and will get their first real
run on `windows-latest` CI.

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
- **Real delivery of `EmbedSocketWin32#setModal`'s signal to the client.**
  The send side is implemented (see "Confirmed working" above): a single
  opcode byte written into the same control channel `EmbedSocketWin32#listen`
  keeps open for the life of a client's embed. But nothing on the client side
  reads it yet, so in every case this codebase can currently produce the
  write fails silently against an already-closed peer:
  `EmbedPlugWin32#announce(Path, String)` — the only Win32 client-side class
  today — closes its own end of the handshake channel immediately after
  sending its pid, and there is no Win32 counterpart to `jembetter-client`'s
  X11-only `EmbedClient` at all (only the narrow `EmbedPlugWin32` facade)
  for a receiver to live on. Real end-to-end delivery needs:
  1. A small message-framing protocol over the control channel (today it
     only ever carries the initial 8-byte pid).
  2. A new `EmbedClientWin32` (mirroring `EmbedClient`) that keeps its end of
     the channel open instead of closing it right after the handshake, and
     reads the opcode on a background thread, exposing an
     `onModalityChanged`-style callback — plus wiring that up through
     `EmbedPlug`/`EmbedPlugWin32` if it should also be reachable from the
     narrow facade.

  Deliberately not built now — asked and explicitly scoped down to the
  send-only stub above rather than the full two-module protocol.

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
loop, invisible cross-process. `Win32FocusWatcher` instead mirrors
`Win32ClickWatcher`'s shape with a different hook:
`SetWinEventHook(EVENT_OBJECT_FOCUS, ...)`, a `WINEVENT_OUTOFCONTEXT`
system-wide accessibility hook (no DLL injected anywhere) that receives an
event for every window in the system gaining focus, regardless of which
process or thread caused it — including a host process calling `SetFocus`
on a client's HWND via `Win32Focus.set`'s `AttachThreadInput` path. Since
the event only ever signals a *gain*, a watched window previously reported
focused is inferred to have lost it as soon as a different window's gain
event arrives for it — the same "genuine transitions only" deduplication
`WindowFocusWatcher` does. `EmbedPlugWin32#onFocusChanged` wires this to
watch the client's own window.

## Test wiring

`EmbedHostWin32Test`/`EmbedSocketWin32Test`/`EmbedPlugWin32Test`/
`Win32ReparentWatcherTest`/`Win32ClickWatcherTest`/`Win32FocusWatcherTest`
cover the mechanisms above, `@Tag("windows")` like the rest of this module's
tests. They run on every push, both on real `windows-latest`
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
