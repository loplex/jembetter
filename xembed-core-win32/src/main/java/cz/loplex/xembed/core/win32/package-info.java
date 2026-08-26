/**
 * Win32 native bindings (via JNA's bundled {@code user32}/{@code kernel32}
 * declarations) mirroring {@code xembed-core}'s X11 primitives 1:1:
 * {@link cz.loplex.xembed.core.win32.Win32Reparent} for {@code Reparenting},
 * {@link cz.loplex.xembed.core.win32.Win32WindowGeometry} for {@code
 * WindowGeometry}, {@link cz.loplex.xembed.core.win32.Win32Focus} for {@code
 * InputFocus}, {@link cz.loplex.xembed.core.win32.Win32WindowFinder} for
 * {@code WindowFinder}.
 *
 * <p><b>Confirmed against a real Windows machine (2026-08-26).</b> A
 * Wine-based smoke test (see {@code .mvn/win32-wine-smoketest} in this
 * repo) had already confirmed these JNA calls reach real {@code
 * user32.dll}/{@code kernel32.dll} entry points and that basic {@code
 * SetParent}/{@code MoveWindow}/{@code EnumWindows} mechanics plausibly
 * work. A one-off real-machine spike, run against real {@code
 * windows-latest} CI (not Wine), then confirmed, on genuine Windows, the
 * following:
 *
 * <ol>
 *   <li>{@code SetParent}+style-flip+poll-verify between a real AWT
 *       {@code Canvas} HWND and a separate JVM's window — confirmed
 *       working.</li>
 *   <li>{@code SetFocus}/{@code SetForegroundWindow}'s foreground-lock
 *       restriction from a non-foreground process — confirmed to actually
 *       bite (a silent no-op, {@code SetForegroundWindow} can return
 *       {@code true} without the foreground changing); see
 *       {@link cz.loplex.xembed.core.win32.Win32Focus}'s Javadoc for the
 *       workaround this led to.</li>
 *   <li>{@link java.lang.ProcessHandle#onExit()} for a foreign (not
 *       self-spawned) pid — confirmed reliable.</li>
 *   <li>{@code AF_UNIX} rendezvous between two JVMs — confirmed working.</li>
 * </ol>
 *
 * Windows-version-specific {@code explorer.exe}/{@code dwm.exe} policy
 * quirks beyond what the spike exercised remain unconfirmed. No {@code
 * os.name} dispatch wires these primitives into {@code EmbedHost}/{@code
 * EmbedPlug}/{@code EmbedSocket}/{@code EmbedClient} yet; that remains a
 * separate follow-up now that the spike's findings settle the open design
 * questions (host-initiated reparent stays symmetric with X11, confirmed by
 * question 1 above; {@code embedOpaque}'s always-on behavior on this
 * backend is unaffected by any of the four questions).
 */
package cz.loplex.xembed.core.win32;
