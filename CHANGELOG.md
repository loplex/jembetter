# Changelog

Notable changes to Jembetter, newest first.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

No version has been released yet — no `v*` tag exists and
[release.yml](.github/workflows/release.yml) deploys on one. The notes below
are therefore written against the `0.1.0-SNAPSHOT` builds published to this
project's GitHub Packages registry, which is the only thing anyone can be
upgrading from.

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
