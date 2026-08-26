# Jembetter

*A Java window embedder. Just better.*

Reparents one X11 Java application's top-level window into another's, using
the [XEmbed](https://specifications.freedesktop.org/xembed-spec/xembed-spec-latest.html)
protocol for focus/activation/geometry handoff between the two. The two
windows can belong to entirely separate JVM processes — the client connects
to the host over a Unix domain socket to hand off its process id, the host
resolves that to a window id via X11 and reparents it directly.

Built and tested against a real X server (no Xvfb/mocking) on Linux/X11 —
there is no Wayland support. `EmbedHost`/`EmbedPlug` also dispatch to a Win32
(`SetParent`) backend by `os.name`, confirmed against a real Windows machine
for the primitives it's built from — see [Win32 backend
status](docs/win32-status.md) for exactly what was confirmed versus what's a
reasoned-about implementation choice on top.

## Modules

- **`jembetter-core-common`** — platform-independent, JNA-free code shared by
  both sides (the rendezvous handshake, AWT `Canvas`-to-native-handle
  extraction, `os.name` dispatch). Not meant to be depended on directly.
- **`jembetter-core`** — X11 native bindings (via JNA) and the XEmbed protocol
  implementation shared by both sides. Not meant to be depended on directly.
- **`jembetter-core-win32`** — Win32 native bindings (via JNA) mirroring
  `jembetter-core`'s X11 primitives 1:1. Backs `jembetter-host`/`jembetter-client`'s
  Win32 implementations — see [Win32 backend status](docs/win32-status.md).
- **`jembetter-host`** — embedder-side API: `EmbedHost` (quick start, a 1:1
  facade for embedding a single self-spawned client) and `EmbedSocket`
  (advanced — multi-client, socket rendezvous) both host another process's
  window inside your own Swing UI.
- **`jembetter-client`** — client-side API: `EmbedPlug` (quick start) and
  `EmbedClient` (advanced) both make your own process's top-level window
  embeddable by a host.
- **`jembetter-demo`** — runnable demo pairs demonstrating the whole pipeline
  end to end, one per API layer; see [Try the demo](#try-the-demo) below.

A host process depends on `jembetter-host`; a client process depends on
`jembetter-client`. A process that's neither (e.g. the demo module) can depend
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
alongside the class jar, so an IDE picking up `jembetter-host`/`jembetter-client`
from the local repo gets real source/doc lookup.

Then depend on the module(s) you need:

```xml
<dependency>
  <groupId>cz.loplex</groupId>
  <artifactId>jembetter-host</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Quick start

`EmbedHost`/`EmbedPlug` are a simplified 1:1 facade for the common case: a
host embedding exactly one client for the lifetime of a single process it
usually spawned itself — e.g. a JVM the host launches and reparents into a
placeholder `Canvas` in its own UI. No multi-client re-use of one socket, no
voluntary host-initiated detach, no focus-next/prev tab-cycling, no
modality. Reach for `EmbedSocket`/`EmbedClient` directly (see
[Advanced usage](docs/advanced-usage.md)) when any of those are needed.

### Host side

```java
JFrame frame = new JFrame("My host app");
Canvas placeholder = new Canvas();
frame.add(placeholder, BorderLayout.CENTER); // wherever the embedded window should appear
// ... lay out the rest of your UI, then make the frame visible ...

EmbedHost host = EmbedHost.create(placeholder);
host.onDetached(() -> System.out.println("Client exited or crashed"));

Process clientProcess = new ProcessBuilder(...).start();
// ... wait for the client to finish its own announce() call, e.g. via a
// readiness signal of your own (see jembetter-demo's HostFacadeDemo) ...
host.embed(clientProcess.pid());
```

`EmbedHost.create(Canvas)` reparents the embedded window as a genuine X11
child of the placeholder's own native window — normal X11 stacking (and the
window manager) then treats it as part of your host window, so a
heavyweight popup/tooltip/modal dialog from your own UI correctly renders
above it — and tracks the placeholder's resizes automatically; no
`ComponentListener` of your own required. This needs the JVM started with
`--add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens
java.desktop/sun.awt.X11=ALL-UNNAMED` (see `.mvn/jvm.config` in this repo for
the flags `mvn exec:java` picks up automatically when running the demo).

`host.embed(rendezvousSocketPath)` is also available for a client that isn't
self-spawned — it opens a Unix domain socket, accepts exactly one client
connection there, embeds it, and returns (unlike `EmbedSocket#listen`, it
doesn't keep accepting further clients afterward). `host.embedOpaque(id)`
handles a toolkit-opaque client the same way `EmbedSocket#embedOpaque` does
— see [Toolkit-opaque embedding](docs/advanced-usage.md#toolkit-opaque-embedding)
for why that's needed at all. Call `host.close()` to release the socket and
its X11 window.

### Client side

```java
JFrame frame = new JFrame("My embeddable app");
frame.setUndecorated(true); // avoid leaving a stray decoration frame behind
// ... build the rest of the window, then make it visible ...
frame.setVisible(true);

EmbedPlug plug = EmbedPlug.create();
plug.onEmbedded(embedderWindowId -> System.out.println("Embedded"));
plug.onHostDetached(() -> System.out.println("Host exited or crashed"));
plug.announce(null); // host already knows this process's pid; null = single top-level window
```

`plug.announce(hostSocketPath, wmClass)` is the counterpart of
`host.embed(rendezvousSocketPath)` above, for a host that doesn't already
know this process's pid. Call `plug.close()` when your process is done
watching for host death (not needed on process exit).

## Advanced usage

`EmbedSocket`/`EmbedClient` — multiple clients on one socket, a voluntary
host-initiated detach/re-embed, focus-next/prev tab-cycling, modality
signaling, and toolkit-opaque (e.g. JavaFX) embedding — are covered in
[docs/advanced-usage.md](docs/advanced-usage.md).

## Try the demo

```sh
mvn -pl jembetter-demo -am install

# Run via Maven
mvn -pl jembetter-demo exec:java@host

# ...or the classpath straight from the local Maven repo (target/cp.txt is (re)generated by the
# `install` above - see jembetter-demo/pom.xml's maven-dependency-plugin build-classpath execution)
java -cp "jembetter-demo/target/classes:$(< jembetter-demo/target/cp.txt)" cz.loplex.jembetter.demo.HostDemo
```

Then, in a second terminal on the same X display:

```sh
mvn -pl jembetter-demo exec:java@client
# ...or:
java -cp "jembetter-demo/target/classes:$(< jembetter-demo/target/cp.txt)" cz.loplex.jembetter.demo.ClientDemo
```

The client window should jump into the host's socket area and resize to fill
it. See the Javadoc on `HostDemo`/`ClientDemo` for the rest of the scripted
sequence (live resize, a voluntary host-initiated detach, and what killing
either process does).

For the `EmbedHost`/`EmbedPlug` facade instead, run `HostFacadeDemo` on its
own — it spawns `ClientFacadeDemo` itself as a child process (the pattern
the facade actually targets) and embeds it via the known-handle path, with
no second terminal needed:

```sh
mvn -pl jembetter-demo exec:java@host-facade
# ...or:
java -cp "jembetter-demo/target/classes:$(< jembetter-demo/target/cp.txt)" cz.loplex.jembetter.demo.HostFacadeDemo
```

See the Javadoc on `HostFacadeDemo`/`ClientFacadeDemo` for its scripted
sequence — live resize and crash detection, but no voluntary detach (out of
scope for the facade; see `HostDemo` above for that).

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

That said, the real test suite is tuned for speed, not for watching: most
windows are 10-100px and every step happens in well under a second, so on
a 1280x1024 Xephyr screen you'll mostly see a blink in the top-left corner.
For an actual guided look, run the one test written for that purpose —
`jembetter-host`'s `VisualEmbedDemoTest` — which is tagged `visual` and
excluded from every normal run (`test.excludedGroups` in the root `pom.xml`)
because it narrates itself with `Thread.sleep` between steps:

```
mvn test -pl jembetter-host -Dtest.xserver=Xephyr -Dgroups=visual -Dtest.excludedGroups= -Dtest=VisualEmbedDemoTest
```

It opens a human-sized host window and a separate client process, then
walks through embedding, a host-driven resize, and a simulated client
crash — each step printed to the console before it happens — so you can
actually watch the reparent/resize/detach mechanics play out in the Xephyr
window.

## Win32 backend status

`EmbedHost`/`EmbedPlug`'s Win32 (`SetParent`) backend — what's confirmed
against a real Windows machine versus reasoned-about on top, and how its
tests/CI are wired up — is covered in
[docs/win32-status.md](docs/win32-status.md).

## Known limitations

- Only one client can be embedded per `EmbedSocket` at a time (though it can
  be swapped out for another via `detachClient()`).
- No simultaneous-embed testing across multiple `EmbedSocket`s in one
  process.
- No published artifact — `mvn install` to the local repo is the only way to
  consume this today.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
