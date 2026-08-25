# xembed

Reparents one X11 Java application's top-level window into another's, using
the [XEmbed](https://specifications.freedesktop.org/xembed-spec/xembed-spec-latest.html)
protocol for focus/activation/geometry handoff between the two. The two
windows can belong to entirely separate JVM processes — the client connects
to the host over a Unix domain socket to hand off its process id, the host
resolves that to a window id via X11 and reparents it directly.

Built and tested against a real X server (no Xvfb/mocking) on Linux/X11 only
— there is no Wayland or non-X11 support.

## Modules

- **`xembed-core`** — X11 native bindings (via JNA) and the XEmbed protocol
  implementation shared by both sides. Not meant to be depended on directly.
- **`xembed-host`** — embedder-side API: `EmbedSocket` hosts another
  process's window inside your own Swing UI.
- **`xembed-client`** — client-side API: `EmbedClient` makes your own
  process's top-level window embeddable by a host.
- **`xembed-demo`** — runnable `HostDemo`/`ClientDemo` pair demonstrating the
  whole pipeline end to end; see [Try the demo](#try-the-demo) below.

A host process depends on `xembed-host`; a client process depends on
`xembed-client`. A process that's neither (e.g. the demo module) can depend
on both.

## Requirements

- Java 21+
- An X11 display (a window manager is recommended but not required)
- Maven

## Building

There's no published artifact yet — everything is installed to your local
`~/.m2` repository:

```sh
mvn install
```

This also builds a `-sources.jar` and `-javadoc.jar` for each module
alongside the class jar, so an IDE picking up `xembed-host`/`xembed-client`
from the local repo gets real source/doc lookup.

Then depend on the module(s) you need:

```xml
<dependency>
  <groupId>cz.loplex</groupId>
  <artifactId>xembed-host</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Usage

### Host side

```java
JFrame frame = new JFrame("My host app");
// ... lay out the rest of your UI ...

EmbedSocket socket = new EmbedSocket(frame);
socket.open(x, y, width, height); // wherever the embedded window should appear
socket.onClientEmbedded(() -> System.out.println("Client embedded"));
socket.onClientDetached(() -> System.out.println("Client exited or crashed"));
socket.listen(Path.of("/run/user/1000/my-app.sock"));

// keep the socket positioned over its placeholder as your UI resizes:
placeholder.addComponentListener(new ComponentAdapter() {
    @Override
    public void componentResized(ComponentEvent e) {
        Point p = placeholder.getLocationOnScreen();
        socket.setBounds(p.x, p.y, placeholder.getWidth(), placeholder.getHeight());
    }
});
```

`EmbedSocket` keeps accepting clients on the same socket for as long as it's
open — a client crashing or being voluntarily released via
`socket.detachClient()` doesn't require restarting the host. Call
`socket.close()` to shut it down.

### Client side

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

If a process owns more than one top-level window at the point it connects,
both `EmbedSocket` and `EmbedClient` need a `WM_CLASS` (the same value
`xprop WM_CLASS` prints) to disambiguate which one — see
`EmbedSocket#expectClientWindowClass` and `EmbedClient#offer(Path, String)`.

Both sides wait up to 5 seconds by default for a window to appear before
giving up; override that with `EmbedSocket#setWindowLookupTimeout`/
`EmbedClient#setWindowLookupTimeout` if that's too tight (or too loose) for
your setup.

## Try the demo

```sh
mvn -pl xembed-demo -am install
mvn -pl xembed-demo dependency:build-classpath -Dmdep.outputFile=/tmp/xembed-demo.cp
java -cp "xembed-demo/target/classes:$(cat /tmp/xembed-demo.cp)" cz.loplex.xembed.demo.HostDemo
```

Then, in a second terminal on the same X display:

```sh
java -cp "xembed-demo/target/classes:$(cat /tmp/xembed-demo.cp)" cz.loplex.xembed.demo.ClientDemo
```

The client window should jump into the host's socket area and resize to fill
it. See the Javadoc on `HostDemo`/`ClientDemo` for the rest of the scripted
sequence (live resize, a voluntary host-initiated detach, and what killing
either process does).

## Known limitations

- Only one client can be embedded per `EmbedSocket` at a time (though it can
  be swapped out for another via `detachClient()`).
- No simultaneous-embed testing across multiple `EmbedSocket`s in one
  process.
- No published artifact — `mvn install` to the local repo is the only way to
  consume this today.
