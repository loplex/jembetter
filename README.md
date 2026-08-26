# Jembetter

*A Java window embedder. Just better.*

Reparents one X11 Java application's top-level window into another's, using
the [XEmbed](https://specifications.freedesktop.org/xembed-spec/xembed-spec-latest.html)
protocol for focus/activation/geometry handoff between the two. The two
windows can belong to entirely separate JVM processes — the client connects
to the host over a Unix domain socket to hand off its process id, the host
resolves that to a window id via X11 and reparents it directly.

Built and tested against a real X server (no Xvfb/mocking) on Linux/X11 —
there is no Wayland support. `xembed-core-win32` is a Win32 (`SetParent`)
primitives skeleton for a future Windows backend; it isn't wired into
`EmbedHost`/`EmbedPlug`/`EmbedSocket`/`EmbedClient` yet and is unverified
against a real Windows machine — see [Win32 backend
status](#win32-backend-status) below.

## Modules

- **`xembed-core-common`** — platform-independent, JNA-free code shared by
  both sides (the rendezvous handshake, AWT `Canvas`-to-native-handle
  extraction). Not meant to be depended on directly.
- **`xembed-core`** — X11 native bindings (via JNA) and the XEmbed protocol
  implementation shared by both sides. Not meant to be depended on directly.
- **`xembed-core-win32`** — Win32 native bindings (via JNA) mirroring
  `xembed-core`'s X11 primitives 1:1. Not depended on by `xembed-host`/
  `xembed-client` yet — see [Win32 backend status](#win32-backend-status).
- **`xembed-host`** — embedder-side API: `EmbedHost` (quick start, a 1:1
  facade for embedding a single self-spawned client) and `EmbedSocket`
  (advanced — multi-client, socket rendezvous) both host another process's
  window inside your own Swing UI.
- **`xembed-client`** — client-side API: `EmbedPlug` (quick start) and
  `EmbedClient` (advanced) both make your own process's top-level window
  embeddable by a host.
- **`xembed-demo`** — runnable demo pairs demonstrating the whole pipeline
  end to end, one per API layer; see [Try the demo](#try-the-demo) below.

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

## Quick start

`EmbedHost`/`EmbedPlug` are a simplified 1:1 facade for the common case: a
host embedding exactly one client for the lifetime of a single process it
usually spawned itself — e.g. a JVM the host launches and reparents into a
placeholder `Canvas` in its own UI. No multi-client re-use of one socket, no
voluntary host-initiated detach, no focus-next/prev tab-cycling, no
modality. Reach for `EmbedSocket`/`EmbedClient` directly (see
[Advanced usage](#advanced-usage) below) when any of those are needed.

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
// readiness signal of your own (see xembed-demo's HostFacadeDemo) ...
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
— see [Toolkit-opaque embedding](#toolkit-opaque-embedding) below for why
that's needed at all. Call `host.close()` to release the socket and its X11
window.

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

`EmbedSocket`/`EmbedClient` are what `EmbedHost`/`EmbedPlug` above are
composed from — reach for them directly for anything the facade leaves out:
multiple clients on one socket, a voluntary host-initiated detach/re-embed,
focus-next/prev tab-cycling, or modality signaling.

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
`placeholder`'s own native window, same as `EmbedHost.create(Canvas)` above
— see its Javadoc for the z-order rationale and the `--add-opens` JVM flags
this needs.

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

### Toolkit-opaque embedding

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
`EmbedHost#embedOpaque(long)` above wraps this with a fixed, generous poll
budget.

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

For the `EmbedHost`/`EmbedPlug` facade instead, run `HostFacadeDemo` on its
own — it spawns `ClientFacadeDemo` itself as a child process (the pattern
the facade actually targets) and embeds it via the known-handle path, with
no second terminal needed:

```sh
mvn -pl xembed-demo exec:java@host-facade
# ...or:
java -cp "xembed-demo/target/classes:$(< xembed-demo/target/cp.txt)" cz.loplex.xembed.demo.HostFacadeDemo
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

## Win32 backend status

`xembed-core-win32` has `Win32Reparent`/`Win32WindowGeometry`/`Win32Focus`/
`Win32WindowFinder`, mirroring `xembed-core`'s X11 primitives 1:1
(`SetParent`+style-flip, `MoveWindow`/`ShowWindow`, `SetFocus`,
`EnumWindows`+`GetWindowThreadProcessId`). **Unverified against a real
Windows machine.** Its JUnit tests are gated with
`@EnabledOnOs(OS.WINDOWS)`, so they're skipped (not run, not failed) by this
repo's own `mvn test` on Linux.

What has actually been checked so far: `.mvn/win32-wine-smoketest/run.sh`
downloads a real Windows JDK and runs those same JUnit tests under Wine
(Wine's `winex11.drv` renders Win32 windows as real X11 windows on Linux,
so a Windows PE JVM process genuinely calls into Wine's `user32.dll`/
`kernel32.dll` implementations, not just type-checks against JNA's Java-side
declarations). That confirms the JNA bindings link and that basic
`SetParent`/`MoveWindow`/`EnumWindows` mechanics plausibly work. It does
**not** confirm Windows' foreground-lock restriction on `SetFocus` (Wine
doesn't faithfully replicate that policy — see `Win32Focus`'s Javadoc),
any real Windows-version-specific behavior, or `ProcessHandle.onExit()`'s
reliability for a foreign pid on Windows. Answering those needs a real
Windows machine, not this Wine-based smoke test.

No `os.name` dispatch wires these primitives into `EmbedHost`/`EmbedPlug`/
`EmbedSocket`/`EmbedClient` yet — that's intentionally deferred until a real
Windows-machine spike settles the open design questions (host-initiated
reparent symmetry, `embedOpaque`'s always-on behavior on this backend).

## Known limitations

- Only one client can be embedded per `EmbedSocket` at a time (though it can
  be swapped out for another via `detachClient()`).
- No simultaneous-embed testing across multiple `EmbedSocket`s in one
  process.
- No published artifact — `mvn install` to the local repo is the only way to
  consume this today.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
