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

- **`xembed-core-common`** — platform-independent, JNA-free code shared by
  both sides (the rendezvous handshake, AWT `Canvas`-to-native-handle
  extraction). Not meant to be depended on directly.
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
`placeholder`'s own native window — normal X11 stacking (and the window
manager) then treats it as part of your host window, so a heavyweight
popup/tooltip/modal dialog from your own UI correctly renders above it. It
also tracks `placeholder`'s resizes automatically; no `ComponentListener` of
your own required. This needs the JVM started with `--add-opens
java.desktop/java.awt=ALL-UNNAMED --add-opens
java.desktop/sun.awt.X11=ALL-UNNAMED` (see `.mvn/jvm.config` in this repo for
the flags `mvn exec:java` picks up automatically when running the demo).

`EmbedSocket` keeps accepting clients on the same socket for as long as it's
open — a client crashing or being voluntarily released via
`socket.detachClient()` doesn't require restarting the host. Call
`socket.close()` to shut it down.

**Advanced usage:** if you have no AWT tree to hang a `Canvas` off of,
`open(x, y, width, height)`/`setBounds(x, y, width, height)` create the
socket as a root-level, override-redirect window instead, which you're then
responsible for keeping positioned yourself (e.g. a placeholder component's
`getLocationOnScreen()` plus a resize/move listener calling `setBounds`).

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

# Run via Maven
mvn -pl xembed-demo exec:java@host

# ...or the classpath straight from the local Maven repo (target/cp.txt is (re)generated by the
# `install` above - see xembed-demo/pom.xml's maven-dependency-plugin build-classpath execution)
java -cp "xembed-demo/target/classes:$(< xembed-demo/target/cp.txt)" cz.loplex.xembed.demo.HostDemo
```

Then, in a second terminal on the same X display:

```sh
mvn -pl xembed-demo exec:java@client
# ...or:
java -cp "xembed-demo/target/classes:$(< xembed-demo/target/cp.txt)" cz.loplex.xembed.demo.ClientDemo
```

The client window should jump into the host's socket area and resize to fill
it. See the Javadoc on `HostDemo`/`ClientDemo` for the rest of the scripted
sequence (live resize, a voluntary host-initiated detach, and what killing
either process does).

## Running tests

Most of the test suite drives a real X server, so those tests are gated with
`@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")` and are
skipped whenever none is available.

By default, `mvn test` never touches whatever `DISPLAY` you already have —
tests reparent/focus/move real windows, not something to unleash on your
live desktop session. Instead, for each test JVM it forks, it launches its
own private Xvfb + openbox pair, via `.mvn/xserver-jvm-wrapper/bin/java`
(wired up as `maven-surefire-plugin`'s `<jvm>` in the root `pom.xml`) and
points that JVM's `DISPLAY` at it, regardless of your own — then tears
both back down once the JVM exits, so nothing outlives the build. openbox
isn't optional: `WindowFinder` (used by both `EmbedSocket` and
`EmbedClient` to locate a window by pid) matches `_NET_WM_PID` against the
*window manager's* `_NET_CLIENT_LIST`, which only a real EWMH window
manager publishes. Override the `test.xserver` Maven property (defined in
the root `pom.xml`, defaults to `Xvfb`) to change this: `-Dtest.xserver=`
(empty) opts out and runs headless/DISPLAY-unset (or, if you export
`DISPLAY` yourself before running Maven, against that) instead — leaving
it pointed at a server that isn't actually installed is a hard build
failure rather than a silent skip, so that a missing `Xvfb`/`openbox`
can't turn into DISPLAY-gated tests quietly not running.

To watch the X11-touching tests run instead of just trusting them, run
`mvn test -Dtest.xserver=Xephyr` from a terminal on a real desktop
session. `.mvn/xserver-jvm-wrapper/bin/java` launches `Xephyr` the same
way either way — without a `-display` of its own — so it nests into
whatever `DISPLAY` it inherited (your desktop's), opening a visible window
per test JVM fork that you can watch windows get created/moved/reparented
in live, instead of on a headless, invisible Xvfb.

## Known limitations

- Only one client can be embedded per `EmbedSocket` at a time (though it can
  be swapped out for another via `detachClient()`).
- No simultaneous-embed testing across multiple `EmbedSocket`s in one
  process.
- No published artifact — `mvn install` to the local repo is the only way to
  consume this today.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
