# Advanced usage

`EmbedSocket`/`EmbedClient` are what `EmbedHost`/`EmbedPlug` (see the main
[README](../README.md#quick-start)) are composed from — reach for them
directly for anything the facade leaves out: several embedded clients at
once (one `EmbedSocket` each), a voluntary host-initiated detach/re-embed,
focus-next/prev tab-cycling, or modality / host-activation signaling to the
client.

Like the facades, both are backend-portable interfaces — `EmbedSocket.create(canvas)`
/ `EmbedClient.create()` dispatch by `os.name` to an `*X11`/`*Win32`
implementation. A handful of X11-only capabilities live on the concrete
`EmbedSocketX11` / `EmbedClientX11` (see [X11-only extras](#x11-only-extras)
below); a caller that needs one downcasts to it explicitly.

## Host side

```java
JFrame frame = new JFrame("My host app");
Canvas placeholder = new Canvas();
frame.add(placeholder, BorderLayout.CENTER); // wherever the embedded window should appear
// ... lay out the rest of your UI, then make the frame visible ...

EmbedSocket socket = EmbedSocket.create(placeholder);
socket.onClientEmbedded(() -> System.out.println("Client embedded"));
socket.onClientDetached(() -> System.out.println("Client exited or crashed"));
socket.listen(Path.of("/run/user/1000/my-app.sock"));
```

`EmbedSocket.create(placeholder)` reparents the embedded window as a genuine
child of `placeholder`'s own native window (a real X11 child on the X11
backend, a Win32 `SetParent` child on Windows), same as
`EmbedHost.create(Canvas)` (see the main README) — see
`EmbedSocketX11#open(Canvas)`'s Javadoc for the z-order rationale and the
`--add-opens` JVM flags this needs.

`EmbedSocket` keeps accepting clients on the same socket for as long as it's
open — a client crashing or being voluntarily released via
`socket.detachClient()` doesn't require restarting the host. Call
`socket.close()` to shut it down.

`socket.tryDestroy()` is `close()`'s destroying counterpart: a still-embedded
client's window is destroyed rather than released back as a live top-level
window. Use it when the embedded client is a private renderer process never
meant to survive independently and that guarantee must hold regardless of
call order — e.g. a caller that can't rely on always killing the client
process before releasing the host. The guarantee's strength differs by
backend (unconditional `XDestroyWindow` on X11, a best-effort `WM_CLOSE` on
Win32 — the name is a reminder, not a promise); `EmbedSocketX11#destroyClient()`
is the same distinction applied to a live client rather than to shutdown,
and is X11-only.

Each `EmbedSocket` holds **one** client at a time by design (its accept loop
blocks until the current client detaches before taking the next). To embed
several clients at once — e.g. one per `Canvas` in a grid — create several
`EmbedSocket`s, each with its own canvas and its own socket path; they run
independently (each on its own X11 connection and background threads), and
closing one releases only its own client.

**No AWT tree to embed into (X11 only):** downcast to `EmbedSocketX11` and
use `open(x, y, width, height)`/`setBounds(x, y, width, height)` to create
the socket as a root-level, override-redirect window instead, which you're
then responsible for keeping positioned yourself (e.g. a placeholder
component's `getLocationOnScreen()` plus a resize/move listener calling
`setBounds`).

**Known-handle embedding, no socket:** if the host already knows the
client's pid directly — e.g. it spawned the client process itself — the
Unix domain socket rendezvous is unnecessary overhead. Skip `listen()` and
call `socket.embed(clientPid)` once the client has published `_XEMBED_INFO`
(see `EmbedClient#announce` below):

```java
Process clientProcess = new ProcessBuilder(...).start();
socket.embed(clientProcess.pid());
```

## Toolkit-opaque embedding

Some toolkits (e.g. JavaFX's Glass layer) own their native X11 connection
the same way AWT does, so this library can't read events targeted at their
windows and can't rely on them ever writing `_XEMBED_INFO` or reacting to
`EMBEDDED_NOTIFY`. `embedOpaque` handles this send-best-effort/poll-verify:
it writes `_XEMBED_INFO` on the client's behalf and confirms the reparent
actually happened via `XQueryTree` instead of trusting the client's
cooperation:

```java
socket.embedOpaque(clientWindowId); // interface: fixed, generous poll budget
((EmbedSocketX11) socket).embedOpaque(clientWindowId, Duration.ofMillis(20), 100); // tuned
```

Throws if the reparent is never confirmed within `pollInterval *
maxAttempts`. Death detection (`onClientDetached`) still works here — it's
based on the X server's own `DestroyNotify`, not client cooperation. The
no-argument `embedOpaque(long)` is on the `EmbedSocket` interface; the tuning
overload is `EmbedSocketX11`-only. `EmbedHost#embedOpaque(long)` (see the
main README) is the same fixed-budget call on the narrow facade.

## Modality and host-window activation

Two host&rarr;client signals are purely semantic — a modal dialog shadowing
the embedded area, and the host's own top-level window gaining or losing
activation — with no real X11 event on the client's window to carry them.
XEmbed defines `MODALITY_ON`/`OFF` and `WINDOW_ACTIVATE`/`DEACTIVATE`
ClientMessages for exactly this, but those only ever reach the connection
that created the client's window (AWT's own), the same restriction behind
`onEmbedded` not using `EMBEDDED_NOTIFY`.

So `EmbedSocket#listen` keeps the rendezvous socket open past the pid
handshake as a **control channel** and relays both on it as small frames.
The client reads them when embedded via `EmbedClient#offer` against a
`listen` socket:

```java
socket.setModal(true);  // host side: shadowed by a modal dialog now
```

```java
client.onModalityChanged(modal -> ...);      // shadowed by a host modal dialog
client.onActivationChanged(active -> ...);    // host window (de)activated
```

`onActivationChanged` is host-level activation, distinct from
`onFocusChanged`'s own input focus — it stays meaningful while the embedded
client itself holds focus within an active host. Both fire only on the
`offer` &harr; `listen` pairing: the one-shot `embed(long)`/`embed(Path)`/
`embedOpaque` paths and `announce()` open no channel, so neither callback
fires there (and `setModal` still sends the XEmbed ClientMessage as a
courtesy to a genuinely XEmbed-aware external toolkit regardless).

## Client side

```java
JFrame frame = new JFrame("My embeddable app");
frame.setUndecorated(true); // avoid leaving a stray decoration frame behind
// ... build the rest of the window, then make it visible ...
frame.setVisible(true);

EmbedClient client = EmbedClient.create();
client.onEmbedded(embedderWindowId -> System.out.println("Embedded"));
client.onHostDetached(() -> System.out.println("Host exited or crashed"));
client.offer(Path.of("/run/user/1000/my-app.sock"));
```

`client.requestFocus()` asks the host for input focus once embedded. Call
`client.close()` when your process is done watching for host death (not
needed on process exit).

`client.onFocusChanged(focused -> ...)` reports when the embedded window
gains (`true`) or loses (`false`) input focus. XEmbed's host&rarr;client
`FOCUS_IN`/`FOCUS_OUT` ClientMessages can't reach a client whose window AWT
created (same reason `onEmbedded` doesn't use `EMBEDDED_NOTIFY`), but the
real `FocusIn`/`FocusOut` the host's `XSetInputFocus` generates on the
window can — that's what this reads. A cooperative AWT/Swing client doesn't
need it (its own toolkit already tracks focus); it's for toolkit-opaque
clients driving their own focus rendering. Also on `EmbedPlug`, X11 backend
only — the Win32 backend has no equivalent signal and never fires it.

**Known-handle embedding, no socket:** the client-side counterpart of
`EmbedSocket#embed(long)` above — `announce(wmClass)` does everything
`offer` does (resolve this process's own window, publish `_XEMBED_INFO`,
start watching for the embed/host-death) except dial a host socket, for
when the host already knows this process's pid directly:

```java
EmbedClient client = EmbedClient.create();
client.onEmbedded(embedderWindowId -> System.out.println("Embedded"));
client.announce(); // no host socket path
```

If a process owns more than one top-level window at the point it connects,
`EmbedClient` needs a `WM_CLASS` (the same value `xprop WM_CLASS` prints) to
disambiguate which one — see `EmbedClient#offer(Path, String)` (and, on the
host side, the X11-only `EmbedSocketX11#expectClientWindowClass`). Win32 has
no `WM_CLASS` equivalent: `announce(String)` there throws
`UnsupportedOperationException` for a non-null argument, and the process must
own exactly one window.

Both sides wait up to 5 seconds by default for a window to appear before
giving up; override that with `EmbedSocket#setWindowLookupTimeout`/
`EmbedClient#setWindowLookupTimeout` if that's too tight (or too loose) for
your setup.

## X11-only extras

A few advanced capabilities have no Win32 counterpart and stay on the
concrete X11 classes — downcast the value `EmbedSocket.create` /
`EmbedClient.create` returned:

```java
EmbedSocketX11 socket = (EmbedSocketX11) EmbedSocket.create(placeholder);
```

- **`EmbedSocketX11`** — override-redirect `open(x, y, width, height)` /
  `setBounds` / `resize` (see [above](#host-side)), the
  `embedOpaque(long, Duration, int)` tuning overload, `onFocusNext` /
  `onFocusPrev` focus-cycling callbacks, `destroyClient()` (unconditional
  `XDestroyWindow`), and `expectClientWindowClass`.
- **`EmbedClientX11`** — `onActivationChanged` (host-window activation;
  Win32's host has no sender to pair with it) and `watchOwnWindow(long)`
  (a toolkit-opaque client handing its own window handle over directly).
