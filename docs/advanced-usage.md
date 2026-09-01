# Advanced usage

`EmbedSocket`/`EmbedClient` are what `EmbedHost`/`EmbedPlug` (see the main
[README](../README.md#quick-start)) are composed from — reach for them
directly for anything the facade leaves out: several embedded clients at
once (one `EmbedSocket` each), a voluntary host-initiated detach/re-embed,
focus-next/prev tab-cycling, or modality signaling.

## Host side

```java
JFrame frame = new JFrame("My host app");
Canvas placeholder = new Canvas();
frame.add(placeholder, BorderLayout.CENTER); // wherever the embedded window should appear
// ... lay out the rest of your UI, then make the frame visible ...

EmbedSocket socket = new EmbedSocket(frame);
socket.open(placeholder);
socket.onClientEmbedded(() -> System.out.println("Client embedded"));
socket.onClientDetached(() -> System.out.println("Client exited or crashed"));
socket.listen(Path.of("/run/user/1000/my-app.sock"));
```

`open(Canvas)` reparents the embedded window as a genuine X11 child of
`placeholder`'s own native window, same as `EmbedHost.create(Canvas)` (see
the main README) — see its Javadoc for the z-order rationale and the
`--add-opens` JVM flags this needs.

`EmbedSocket` keeps accepting clients on the same socket for as long as it's
open — a client crashing or being voluntarily released via
`socket.detachClient()` doesn't require restarting the host. Call
`socket.close()` to shut it down.

`socket.destroyClient()` is `detachClient()`'s destroying counterpart: it
destroys a still-embedded client's window outright (`XDestroyWindow`)
instead of reparenting it back to root as a live top-level window. Use it
when the embedded client is a private renderer process never meant to
survive independently and that guarantee must hold regardless of call order
— e.g. a caller that can't rely on always killing the client process before
releasing the host. `socket.tryDestroy()` applies the same distinction to
shutdown: a still-embedded client is destroyed via `destroyClient()` rather
than released via `detachClient()`, which is what plain `socket.close()`
still does.

Each `EmbedSocket` holds **one** client at a time by design (its accept loop
blocks until the current client detaches before taking the next). To embed
several clients at once — e.g. one per `Canvas` in a grid — create several
`EmbedSocket`s, each with its own canvas and its own socket path; they run
independently (each on its own X11 connection and background threads), and
closing one releases only its own client.

**No AWT tree to embed into:** `open(x, y, width, height)`/`setBounds(x, y,
width, height)` create the socket as a root-level, override-redirect window
instead, which you're then responsible for keeping positioned yourself (e.g.
a placeholder component's `getLocationOnScreen()` plus a resize/move
listener calling `setBounds`).

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
socket.embedOpaque(clientWindowId, Duration.ofMillis(20), 100);
```

Throws if the reparent is never confirmed within `pollInterval *
maxAttempts`. Death detection (`onClientDetached`) still works here — it's
based on the X server's own `DestroyNotify`, not client cooperation.
`EmbedHost#embedOpaque(long)` (see the main README) wraps this with a
fixed, generous poll budget.

## Client side

```java
JFrame frame = new JFrame("My embeddable app");
frame.setUndecorated(true); // avoid leaving a stray decoration frame behind
// ... build the rest of the window, then make it visible ...
frame.setVisible(true);

EmbedClient client = new EmbedClient();
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
EmbedClient client = new EmbedClient();
client.onEmbedded(embedderWindowId -> System.out.println("Embedded"));
client.announce(); // no host socket path
```

If a process owns more than one top-level window at the point it connects,
both `EmbedSocket` and `EmbedClient` need a `WM_CLASS` (the same value
`xprop WM_CLASS` prints) to disambiguate which one — see
`EmbedSocket#expectClientWindowClass` and `EmbedClient#offer(Path, String)`.

Both sides wait up to 5 seconds by default for a window to appear before
giving up; override that with `EmbedSocket#setWindowLookupTimeout`/
`EmbedClient#setWindowLookupTimeout` if that's too tight (or too loose) for
your setup.
