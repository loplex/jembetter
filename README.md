# Jembetter

*A Java window embedder. Just better.*

Reparents one Java application's top-level window into another's. The two
windows can belong to entirely separate JVM processes — the client connects
to the host over a Unix domain socket to hand off its process id, the host
resolves that to a native window handle and reparents it directly.

Two backends, dispatched by `os.name`: on X11 the reparent goes through the
[XEmbed](https://specifications.freedesktop.org/xembed-spec/xembed-spec-latest.html)
protocol, which carries the focus/activation/geometry handoff between the
two windows; on Windows it goes through `SetParent`. Both public API pairs
— `EmbedHost`/`EmbedPlug` and `EmbedSocket`/`EmbedClient` — are
backend-portable interfaces; a handful of X11-only extras stay on the
concrete `*X11` classes (see [X11-only
extras](docs/advanced-usage.md#x11-only-extras)).

The X11 backend is built and tested against a real X server (no
Xvfb/mocking) on Linux/X11 — there is no Wayland support. The Win32 backend
is confirmed against a real Windows machine for the primitives it's built
from — see [Win32 backend status](docs/win32-status.md) for exactly what was
confirmed versus what's a reasoned-about implementation choice on top.

## Modules

Six modules: `jembetter-core-common` and `jembetter-core-x11`/`jembetter-core-win32`
hold the shared/native-binding plumbing (not meant to be depended on
directly), `jembetter-host`/`jembetter-client` are the public APIs — a host
process depends on `jembetter-host`, a client process on `jembetter-client` —
and `jembetter-demo` (depends on both) has the runnable examples. Full
breakdown and the module dependency diagram: [Architecture](docs/architecture.md).

## Requirements

- Java 21+
- Linux/X11 with a display (a window manager is recommended but not
  required), or Windows
- Maven

## Building

`mvn install` builds everything into your local `~/.m2` repository:

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

To consume a build without cloning this repository, see
[Publishing and consuming](#publishing-and-consuming) below.

## Publishing and consuming

Releases go to the GitHub Packages Maven registry of this project's GitHub
repository:

    https://maven.pkg.github.com/loplex/jembetter

Everything except `jembetter-demo` is published there (the demo module is
hand-launched verification apps, not something an embedding application
depends on).

### Consuming

GitHub Packages requires an authenticated token **even to download a public
package**, so unlike Maven Central this needs one-time setup on the consuming
machine. Create a GitHub personal access token (classic) with the
`read:packages` scope, then add both halves to your `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>

  <profiles>
    <profile>
      <id>github-loplex</id>
      <repositories>
        <repository>
          <!-- Must match the <server> id above - that's what pairs the
               repository up with its credentials. -->
          <id>github</id>
          <url>https://maven.pkg.github.com/loplex/*</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>

  <activeProfiles>
    <activeProfile>github-loplex</activeProfile>
  </activeProfiles>
</settings>
```

The trailing `/*` is GitHub's owner-wide wildcard: it resolves against every
one of that owner's repositories, so this entry doesn't have to be repeated
per project.

The repository belongs in `settings.xml` rather than in the consuming
project's `pom.xml` on purpose. A `<repositories>` block in a POM is
inherited by everything that depends on that POM downstream, which would make
Maven try this registry for unrelated dependencies too — and without a token
that fails with an opaque 401 instead of a clean "not found". `settings.xml`
is per-machine and never propagates.

Two things that commonly break resolution here:

- A `<mirror>` with `<mirrorOf>*</mirrorOf>` in the same `settings.xml`
  intercepts this repository as well. Use `external:*` or an explicit
  `<mirrorOf>` list instead, or add `!github` to it.
- Fine-grained personal access tokens have a patchy history with the Maven
  registry; a classic token is the reliable choice.

### Publishing

Tagging a commit `v*` runs the `Publish to GitHub Packages` workflow, which
deploys from CI using the built-in `GITHUB_TOKEN` — no secret to configure.
Cutting a release is therefore:

```sh
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
git commit -am "Release 0.1.0"
git tag v0.1.0
git push origin main --tags

mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "Back to snapshot development"
```

Release versions on GitHub Packages are immutable: a given version uploads
exactly once, and a re-upload is rejected with HTTP 409. A botched release has
to be superseded by a new version, not replaced. `-SNAPSHOT` versions are
exempt and can be re-deployed freely.

To deploy by hand instead, put a `<server>` entry as shown above — with a
token carrying `write:packages` — in your `~/.m2/settings.xml` and run
`mvn -DskipTests deploy`.

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

`EmbedHost.create(Canvas)` reparents the embedded window as a genuine native
child of the placeholder's own window — a real X11 child on the X11 backend,
a `SetParent` child on Win32 — so normal stacking (and, on X11, the window
manager) treats it as part of your host window and a heavyweight
popup/tooltip/modal dialog from your own UI correctly renders above it. It
also tracks the placeholder's resizes automatically; no `ComponentListener`
of your own required. This needs the JVM started with `--add-opens
java.desktop/java.awt=ALL-UNNAMED` plus the AWT toolkit package for the
platform — `--add-opens java.desktop/sun.awt.X11=ALL-UNNAMED` on X11,
`--add-opens java.desktop/sun.awt.windows=ALL-UNNAMED` on Windows (see
`.mvn/jvm.config` in this repo for the flags `mvn exec:java` picks up
automatically when running the demo).

`host.embed(rendezvousSocketPath)` is also available for a client that isn't
self-spawned — it opens a Unix domain socket, accepts exactly one client
connection there, embeds it, and returns (unlike `EmbedSocket#listen`, it
doesn't keep accepting further clients afterward). `host.embedOpaque(id)`
handles a toolkit-opaque client the same way `EmbedSocket#embedOpaque` does
— see [Toolkit-opaque embedding](docs/advanced-usage.md#toolkit-opaque-embedding)
for why that's needed at all. Call `host.close()` to release the socket and
the native window it holds.

`host.tryDestroy()` is a destroying close: it destroys a still-embedded
client's window instead of gracefully releasing it the way plain
`host.close()` does. Use it when the embedded client is a private renderer
process never meant to survive independently — e.g. one this host spawned
purely to embed — and that must hold regardless of whether the client
process has already been killed by the time it runs. On the X11 backend
this is unconditional (`EmbedSocket#destroyClient()`, `XDestroyWindow`); on
Win32 it's best-effort (`WM_CLOSE`, since `DestroyWindow` itself can't be
called across processes) — the name is a reminder, not a promise; see
`EmbedHost#tryDestroy()`'s Javadoc.

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

`jembetter-demo` ships two demo pairs. `HostDemo`/`ClientDemo` are built on
`EmbedSocket`/`EmbedClient` (the advanced, multi-client API — see
[Advanced usage](docs/advanced-usage.md)); `HostFacadeDemo`/`ClientFacadeDemo`
are built on `EmbedHost`/`EmbedPlug` (the simplified 1:1 facade). Both APIs
dispatch by `os.name` to either backend, so both pairs run on Linux and on
Windows.

```sh
mvn -pl jembetter-demo -am install

# Run via Maven
mvn -pl jembetter-demo exec:java@host

# ...or the classpath straight from the local Maven repo (target/cp.txt is (re)generated by the
# `install` above - see jembetter-demo/pom.xml's maven-dependency-plugin build-classpath execution)
java -cp "jembetter-demo/target/classes:$(< jembetter-demo/target/cp.txt)" cz.loplex.jembetter.demo.HostDemo
```

Then, in a second terminal on the same display:

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
mvn -pl jembetter-demo exec:exec@host-facade
# ...or:
java -cp "jembetter-demo/target/classes:$(< jembetter-demo/target/cp.txt)" cz.loplex.jembetter.demo.HostFacadeDemo
```

See the Javadoc on `HostFacadeDemo`/`ClientFacadeDemo` for its scripted
sequence — live resize and crash detection, but no voluntary detach (out of
scope for the facade; see `HostDemo` above for that).

## Running tests

`mvn test` drives real X11 windows, but never against whatever `DISPLAY` you
already have — each forked test JVM gets its own private, disposable Xvfb +
openbox pair instead. See [Running tests](docs/testing.md) for how that's
wired up, how to watch the tests run in a visible `Xephyr` window instead,
and how the Win32 backend's tests are covered.

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
- Artifacts are published only to GitHub Packages, which requires an
  authenticated token even for public reads — see
  [Publishing and consuming](#publishing-and-consuming).

## License

Apache License 2.0 — see [LICENSE](LICENSE).
