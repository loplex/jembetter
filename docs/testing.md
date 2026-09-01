# Running tests

Most of the test suite drives a real X server, so those tests are gated with
`@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")` and are
skipped whenever none is available.

By default, `mvn test` never touches whatever `DISPLAY` you already have —
tests reparent/focus/move real windows, not something to unleash on your
live desktop session. Instead, for each test JVM it forks, it launches its
own private Xvfb + openbox pair, via `build-tools/test-jvm-wrapper/bin/java`
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
session. `build-tools/test-jvm-wrapper/bin/java` launches `Xephyr` the same
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

## Win32 tests

`jembetter-core-win32`'s and the Win32-backend tests elsewhere are tagged
`@Tag("windows")` and forked into a Windows JDK under Wine (the
`windows-tests-on-linux` Surefire execution) rather than gated on `DISPLAY` —
see [Win32 backend status](win32-status.md) for what that confirms. A few of
them are additionally tagged `@Tag("wine-incompatible")` where Wine's
simulation doesn't replicate the real behaviour closely enough (foreground
lock, some reparent-watcher transitions); those are excluded from the Wine
fork and covered instead by
[`build-tools/win32-real-machine-checks`](../build-tools/win32-real-machine-checks/README.md),
a set of standalone checks run by hand against a real Windows machine.

Every fork above — plain Linux or Wine-hosted — spawns its own Xvfb and
openbox via `build-tools/test-jvm-wrapper/spawn-xserver.sh`. Their own
boot/shutdown chatter (keysym warnings, GPU probing, ...) is captured to a
temp file rather than relayed straight through, and only dumped to the
console if that fork itself fails to start; a fork that starts fine reports
its actual test results exactly as before, unaffected. If you do need the
raw, unfiltered build output for something else, redirect `mvn test` to a
file and grep it instead of scrolling past it — a multi-module run reforks
Xvfb/openbox once per module/execution, so the boilerplate this avoids adds
up quickly across a whole reactor build.
