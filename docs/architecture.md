# Architecture

## Modules

- **`jembetter-core-common`** — platform-independent, JNA-free code shared by
  both sides (the rendezvous handshake, AWT `Canvas`-to-native-handle
  extraction, `os.name` dispatch). Not meant to be depended on directly.
- **`jembetter-core-x11`** — X11 native bindings (via JNA) and the XEmbed protocol
  implementation shared by both sides. Not meant to be depended on directly.
- **`jembetter-core-win32`** — Win32 native bindings (via JNA) mirroring
  `jembetter-core-x11`'s primitives 1:1. Backs `jembetter-host`/`jembetter-client`'s
  Win32 implementations — see [Win32 backend status](win32-status.md).
- **`jembetter-host`** — embedder-side API: `EmbedHost` (quick start, a 1:1
  facade for embedding a single self-spawned client) and `EmbedSocket`
  (advanced — multi-client, socket rendezvous) both host another process's
  window inside your own Swing UI. Both are interfaces, dispatched by
  `os.name` to `EmbedHostX11`/`EmbedHostWin32` (resp. `EmbedSocketX11`/
  `EmbedSocketWin32`).
- **`jembetter-client`** — client-side API: `EmbedPlug` (quick start) and
  `EmbedClient` (advanced) both make your own process's top-level window
  embeddable by a host — interfaces, dispatched to `EmbedPlugX11`/
  `EmbedPlugWin32` (resp. `EmbedClientX11`/`EmbedClientWin32`).
- **`jembetter-demo`** — runnable demo pairs demonstrating the whole pipeline
  end to end, one per API layer; see [Try the demo](../README.md#try-the-demo)
  in the main README.

A host process depends on `jembetter-host`; a client process depends on
`jembetter-client`. A process that's neither (e.g. the demo module) can depend
on both.

## Module dependencies

```mermaid
graph BT
    common["jembetter-core-common"]
    core["jembetter-core-x11"]
    win32["jembetter-core-win32"]
    host["jembetter-host"]
    client["jembetter-client"]
    demo["jembetter-demo"]

    core --> common
    win32 --> common
    host --> core
    host --> win32
    client --> core
    client --> win32
    demo --> host
    demo --> client
```

Both public API layers are backend-portable interfaces: the narrow facades
`EmbedHost`/`EmbedPlug` and the advanced `EmbedSocket`/`EmbedClient` each
dispatch by `os.name` at runtime (via `Platform.isWindows()`) to an
`*X11`/`*Win32` implementation. A caller needing an X11-only capability
(override-redirect `open`, focus-cycling, `destroyClient`, …) downcasts to
`EmbedSocketX11`/`EmbedClientX11` explicitly.

Only the `jembetter-core-x11`/`jembetter-core-win32` primitive layer stays
un-abstracted: the two sit side by side rather than behind a shared type,
mirroring each other's primitives 1:1 (`WindowFinder`↔`Win32WindowFinder`,
`Reparenting`↔`Win32Reparent`, …) — see [Win32 backend
status](win32-status.md) for why (some primitives, like `Win32ClickWatcher`,
have no X11 equivalent to mirror at all).
