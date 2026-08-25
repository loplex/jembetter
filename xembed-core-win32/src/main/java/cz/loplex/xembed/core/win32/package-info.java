/**
 * Win32 native bindings (via JNA's bundled {@code user32}/{@code kernel32}
 * declarations) mirroring {@code xembed-core}'s X11 primitives 1:1:
 * {@link cz.loplex.xembed.core.win32.Win32Reparent} for {@code Reparenting},
 * {@link cz.loplex.xembed.core.win32.Win32WindowGeometry} for {@code
 * WindowGeometry}, {@link cz.loplex.xembed.core.win32.Win32Focus} for {@code
 * InputFocus}, {@link cz.loplex.xembed.core.win32.Win32WindowFinder} for
 * {@code WindowFinder}.
 *
 * <p><b>Unverified against a real Windows machine.</b> A Wine-based smoke
 * test (see {@code .mvn/win32-wine-smoketest} in this repo) confirmed these
 * JNA calls actually reach real {@code user32.dll}/{@code kernel32.dll}
 * entry points and that basic {@code SetParent}/{@code MoveWindow}/{@code
 * EnumWindows} mechanics plausibly work, since Wine's {@code winex11.drv}
 * renders Win32 windows as real X11 windows on Linux. Wine does not
 * faithfully replicate Windows' foreground-lock restriction on {@code
 * SetForegroundWindow}/{@code SetFocus} from a non-foreground process (see
 * {@link cz.loplex.xembed.core.win32.Win32Focus}), Windows-version-specific
 * {@code explorer.exe}/{@code dwm.exe} policy quirks, or the process model
 * {@link java.lang.ProcessHandle#onExit()} relies on for a foreign (not
 * self-spawned) pid — none of that is confirmed by anything run so far. No
 * {@code os.name} dispatch wires these primitives into {@code EmbedHost}/
 * {@code EmbedPlug}/{@code EmbedSocket}/{@code EmbedClient} yet; that's
 * deferred until a real Windows machine spike answers the open questions
 * (host-initiated-reparent symmetry, {@code embedOpaque}'s always-on
 * behavior on this backend).
 */
package cz.loplex.xembed.core.win32;
