# Advanced usage

`EmbedSocket`/`EmbedClient` are what `EmbedHost`/`EmbedPlug` (see the main
[README](../README.md#quick-start)) are composed from — reach for them
directly for anything the facade leaves out: multiple clients on one socket,
a voluntary host-initiated detach/re-embed, focus-next/prev tab-cycling, or
modality signaling.

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
