# Changelog

Notable changes to Jembetter, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No version has been released yet — no `v*` tag exists and
[release.yml](.github/workflows/release.yml) deploys on one. The notes below
describe what changed since the `0.1.0-SNAPSHOT` builds published to this
project's GitHub Packages registry, which is what a reader is most likely
upgrading from.

Those changes are carried by the `0.2.0-SNAPSHOT` builds published to the same
registry on 2026-09-15, which are the first to record in their manifests the
commit they were built from — see "Which commit a jar came from" in
[README.md](README.md#which-commit-a-jar-came-from).

### Breaking: `watchOwnWindow(long)` is now on the `EmbedClient` interface

It used to be declared only on the concrete `EmbedClientX11` and
`EmbedClientWin32` classes. Both backends implement it, so it moved up to
[`EmbedClient`](jembetter-client/src/main/java/cz/loplex/jembetter/client/EmbedClient.java)
itself.

Who this breaks, and who it does not:

- **Implementing `EmbedClient` yourself** — source-incompatible. The interface
  has a method your class does not, and it will not compile until you add it.
- **Calling it through `EmbedClient`** — this is the point of the change. It
  used to need a cast to a `*X11`/`*Win32` class or a call to their
  constructors directly.
- **Calling it on `EmbedClientX11`/`EmbedClientWin32`** — unaffected.

`onActivationChanged` is now the only member left on `EmbedClientX11` that the
interface does not carry. The full list of what stays backend-specific is in
[X11-only extras](docs/advanced-usage.md#x11-only-extras).

### Breaking: an `EmbedSocket` holds one client at a time, and says so

`embed(long)`, `embed(Path)` and `embedOpaque(long)` now throw
`IllegalStateException` when a client is already embedded. They used to
overwrite the tracked window and leave the first client reparented inside the
socket with nothing watching it — `detachClient()` and `close()` released only
the second, so on X11 the first was left to the save-set and reappeared on the
desktop at teardown, and on Win32 it went down with the host window.

Replacing a client deliberately has its own name now:

```java
socket.swapClient(clientPid);        // detachClient() + embed(clientPid)
socket.swapClientOpaque(windowId);   // detachClient() + embedOpaque(windowId)
```

`EmbedSocketX11#open` likewise rejects a second call rather than leaking the
first socket window and its watcher thread.

Who this breaks, and who it does not:

- **Implementing `EmbedSocket` yourself** — source-incompatible, for the same
  reason as the change above: two methods were added to the interface.
- **Embedding once per socket, or re-embedding after a detach** — unaffected.
  That includes `listen()`'s own accept loop, which detaches before it accepts
  the next client.
- **Calling `embed` a second time to swap clients** — that call now throws.
  Change it to `swapClient`, or call `detachClient()` first.

### Added

- A Win32 backend, alongside the existing X11 one, dispatched on `os.name` by
  `EmbedHost.create`/`EmbedSocket.create` and
  `EmbedPlug.create`/`EmbedClient.create`. What it was confirmed to do on a
  real Windows machine, and what is a reasoned implementation choice on top,
  is in [Win32 backend status](docs/win32-status.md).
- `watchOwnWindow(long)` on the Win32 backend, for a client whose window is
  created by its own toolkit and whose handle reaches the host out-of-band —
  no pid lookup, no socket handshake. It is also the only way to re-arm a
  watch on an already-embedded window, which is a `WS_CHILD` and so no longer
  enumerable as a top-level window.

- Teardown now says when it gives up on one of its own background threads.
  Every `close()` in this library waits a bounded time for the threads it
  started and then proceeds regardless; that wait's result used to be
  discarded at all eleven call sites, so a thread still running after its
  owner closed was invisible from outside the process. It is now logged, and
  `EmbedSocket`, `EmbedClient`, `EmbedHost` and `EmbedPlug` gained a
  `closedCleanly()` that answers the same question — a `default` method, so
  nothing implementing those interfaces has to change.

  `close()` deliberately still does not throw for this. Those threads are
  daemons, a caller cannot kill one, and every native call they could still
  make is already guarded against a closed connection — so failing an
  application's shutdown because the window system was slow for a second
  would manufacture a problem rather than report one. The shape is
  `ExecutorService.awaitTermination`'s: best-effort teardown, with whether it
  finished as a separate question.

- [Advanced usage](docs/advanced-usage.md) now documents the threading
  contract: which methods may be called from which thread, which thread each
  callback is delivered on (none of them AWT's), and what a callback that
  blocks costs at teardown. Several of those answers existed only as
  behaviour before.

### Changed

- Every public method on `EmbedHost`, `EmbedSocket`, `EmbedPlug` and
  `EmbedClient` now rejects a null argument with a `NullPointerException`
  naming the parameter. There were none before, so a null went on to fail
  somewhere else entirely: a null `setWindowLookupTimeout` only surfaced from
  `Duration.toMillis()` inside a later `embed()`, and a null callback from the
  background thread that eventually read the field, where a callback failure
  is caught and logged as "a misbehaving callback" — which it was not. The
  `wmClass` arguments are the exception, and keep their documented meaning:
  null says this process owns a single top-level window.

- `EmbedSocketX11.resize`, `setBounds`, `listen`, `embed` and `embedOpaque`
  now throw `IllegalStateException` when the socket has been closed. They
  used to fall through the "open() must be called first" guard — a closed
  socket keeps its window id — and, with native calls skipped against a
  closed connection, did nothing at all and reported nothing. The
  `ComponentListener` `open(Canvas)` attaches is unaffected: a resize
  notification queued before the socket closed is dropped, not thrown on
  AWT's event thread.

- Everything this library reports about a failure now goes through slf4j,
  which it already depended on, instead of `printStackTrace(System.err)`.
  There were 14 of those, in every watcher and both accept loops, against a
  single class that used the logger. A host embedding this had no way to
  route, level or silence any of it. What is logged and what is swallowed is
  unchanged — a callback that throws still must not take a watcher thread
  down.

- Constructing an `EmbedSocket` or `EmbedClient` no longer leaks what it had
  already built when a later step fails. Each builds several resources — up
  to four X11 connections and three threads — and a failure part-way through
  left the earlier ones running with nothing able to close them, since the
  caller never receives an object. `X11Display.open` fails exactly when the
  X server refuses another connection, so retrying made the situation worse
  each time.

- `WM_CLASS` is decoded as ISO 8859-1 rather than UTF-8, which is what
  ICCCM ties to its `STRING` type. A class name containing a non-ASCII
  Latin-1 byte used to decode to U+FFFD, so a host filtering on it matched
  nothing and reported the client as never having published a window —
  pointing at entirely the wrong thing. Properties of any other type,
  `UTF8_STRING` included, still read as UTF-8.

- `setWindowLookupTimeout` now also governs how long an embed waits for the
  reparent into the socket to be confirmed, on both backends. That wait had
  its own fixed two-second budget and no way to change it, next to a window
  lookup that got five seconds and a setter — the wrong one of the two to
  hardcode, since it is the one waiting on a round trip to the window
  system. `embedOpaque(long, Duration, int)` still takes an explicit budget.
- `WindowRelease` tells "no window manager is running" apart from "the
  window manager has not let go yet". The first needs no wait at all and
  never did; the second means something is wrong and is now logged instead
  of passing as the same silent non-event.

- A rendezvous socket is narrowed to its owner as soon as it is bound, so no
  other user's process can connect to it. A client's whole handshake is the
  process id it announces, and nothing proves the process on the other end is
  that pid, so who can reach the socket is what the guarantee rests on —
  which `EmbedSocket#listen` now says out loud. Where the filesystem has no
  POSIX modes (any Windows host) the socket's directory is the only lever,
  and the caller's choice of path is the whole story.

### Fixed

- A JVM crash (`SIGSEGV` inside Xlib) when an X11 display connection was
  closed while another thread was still using it. A native call against a
  freed connection takes the process down rather than throwing something a
  host could catch.

  The case that produced the crash report was a host's owner window gaining
  or losing focus at the instant its `EmbedSocket` was closed, but the same
  race was reachable from every background watcher this library runs: each
  closes its connection after a bounded join, so its event loop could still
  be running when the connection was freed underneath it.

  Every native call in `jembetter-core-x11` now goes through an open-checked
  accessor on `X11Display`, which tests the connection and makes the call as
  one indivisible step. Commands (moving a window, setting focus) are
  skipped once the connection is gone; queries, which have no honest value
  to fall back on, throw `IllegalStateException` instead.
- Closing an X11 `EmbedSocket`, or an `X11Display` itself, twice is now a
  no-op rather than a second teardown of the same resources. A socket opened
  on a `Canvas` can be closed by its own `HierarchyListener` and by its
  caller at the same moment.
- An X11 or Win32 `EmbedSocket` opened on a `Canvas` now takes its own AWT
  listeners back off that canvas when it is closed. They used to stay
  attached for the canvas's whole life, firing resize and displayability
  callbacks into a socket that was already torn down, and keeping that
  socket reachable so it could never be collected.
- Closing an `EmbedSocket` from a different thread than the one that opened
  it no longer risks leaving its background watcher, its server channel or
  its accept thread running. Those fields were not safely published, so the
  closing thread could read them as still-unset — reachable in practice
  because a `Canvas`-attached socket is closed by AWT's own event thread.
- Two threads calling `listen()` on the same `EmbedSocket` at the same time
  could both get past its "already listening" check and bind two server
  channels to one path; a close landing in the middle of a `listen()` could
  miss the channel and thread it was about to create. Both transitions now
  happen under one lock. A `listen()` whose `bind` fails no longer leaves the
  opened channel behind either, and a `close()` whose server channel refuses
  to close now finishes the rest of the teardown instead of throwing out of
  it.

### Published artifacts

Five modules, all under the `cz.loplex` group id:

| Artifact | Depend on it when |
|---|---|
| `jembetter-host` | your process hosts another application's window |
| `jembetter-client` | your process hands its window to a host |
| `jembetter-core-common` | never directly — shared plumbing, pulled in transitively |
| `jembetter-core-x11` | never directly — X11 bindings, pulled in transitively |
| `jembetter-core-win32` | never directly — Win32 bindings, pulled in transitively |

`jembetter-demo` is built by the reactor but not deployed; it holds the
runnable examples. See [Publishing and consuming](README.md#publishing-and-consuming)
for the registry coordinates.
