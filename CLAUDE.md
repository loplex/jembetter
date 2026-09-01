# java-xembeder (Jembetter)

Java window-embedding library: XEmbed on X11, `SetParent` on Win32, via JNA.
`docs/architecture.md` — module layout. `docs/win32-status.md` — Win32
backend status. `docs/testing.md` — how the test suite works.

## Running tests here

`mvn test` (and any `-Dtest=...` run) forks a private Xvfb+openbox X server
per JVM fork (see `docs/testing.md`), plus a Wine-hosted fork for the
`@Tag("windows")` suite whenever Wine is installed — that's real Maven
reactor/plugin output on top of the actual test results, for every module
and execution. For anything beyond a `Tests run:` summary — chasing a build
failure, `-X` debug logging — redirect to a file and grep it rather than
reading the raw output:

    mvn test > /tmp/test.log 2>&1; grep -E "Tests run|ERROR" /tmp/test.log
