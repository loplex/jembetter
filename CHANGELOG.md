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

- A JVM crash (`SIGSEGV` inside Xlib) when an X11 host's owner window gained
  or lost focus at the moment its `EmbedSocket` was being closed. The focus
  callback could be waiting for the lock that guards every native call at the
  instant the display connection was freed underneath it, and then made its
  call against freed memory — a process-level crash rather than an exception
  the host could catch. Native calls that can race a teardown now go through
  `X11Display.ifOpen`, which checks the connection is still open and makes
  the call as one indivisible step, and closing a connection twice is now a
  no-op instead of a double free.

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
