/**
 * Win32 native bindings (via JNA's bundled {@code user32}/{@code kernel32}
 * declarations) mirroring {@code jembetter-core}'s X11 primitives 1:1:
 * {@link cz.loplex.jembetter.core.win32.Win32Reparent} for {@code Reparenting},
 * {@link cz.loplex.jembetter.core.win32.Win32WindowGeometry} for {@code
 * WindowGeometry}, {@link cz.loplex.jembetter.core.win32.Win32Focus} for {@code
 * InputFocus}, {@link cz.loplex.jembetter.core.win32.Win32WindowFinder} for
 * {@code WindowFinder}, and {@link
 * cz.loplex.jembetter.core.win32.Win32ReparentWatcher} for {@code
 * WindowReparentWatcher} (poll-based here — see its own Javadoc for why).
 * {@link cz.loplex.jembetter.core.win32.Win32ClickWatcher} has no X11
 * primitive to mirror: it stands in for {@code EmbedSocket}'s passive
 * {@code XGrabButton} click-to-focus with a low-level mouse hook instead,
 * since Win32 has no intercept-and-replay equivalent. Unlike the rest of
 * this list, {@code Win32ClickWatcher} was driven out entirely under
 * {@code .mvn/win32-wine-smoketest} rather than against the primitives the
 * real-machine spike below already covers — see its own Javadoc for what
 * that run does and doesn't confirm.
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
 *       {@link cz.loplex.jembetter.core.win32.Win32Focus}'s Javadoc for the
 *       workaround this led to.</li>
 *   <li>{@link java.lang.ProcessHandle#onExit()} for a foreign (not
 *       self-spawned) pid — confirmed reliable.</li>
 *   <li>{@code AF_UNIX} rendezvous between two JVMs — confirmed working.</li>
 * </ol>
 *
 * Windows-version-specific {@code explorer.exe}/{@code dwm.exe} policy
 * quirks beyond what the spike exercised remain unconfirmed.
 *
 * <p>{@code os.name} dispatch now wires these primitives into {@code
 * EmbedHost}/{@code EmbedPlug} ({@code jembetter-host.EmbedHostWin32}, {@code
 * jembetter-client.EmbedPlugWin32}) per the spike's findings: host-initiated
 * reparent stays symmetric with X11 (confirmed by question 1 above), and
 * {@code embedOpaque}'s always-on behavior on this backend collapses with
 * plain {@code embed} into the same operation, since there is no {@code
 * _XEMBED_INFO} equivalent to make them differ. {@link
 * cz.loplex.jembetter.core.win32.Win32ReparentWatcher}, used by {@code
 * EmbedPlugWin32} to detect being embedded/the host detaching, is poll-based
 * and was <b>not</b> exercised by the spike — see its own Javadoc.
 */
package cz.loplex.jembetter.core.win32;
