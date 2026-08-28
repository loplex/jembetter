package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.Platform;

import java.awt.Canvas;
import java.nio.file.Path;

/**
 * Simplified 1:1 host-side facade dispatched by {@code os.name} to either an
 * X11 backend ({@link EmbedHostX11}, over {@link EmbedSocket}) or a Win32
 * backend ({@link EmbedHostWin32}, over {@code jembetter-core-win32}'s {@code
 * SetParent} wrapper), for the common case of a host embedding exactly one
 * client for the lifetime of a single child process — e.g. a JVM the host
 * spawns itself and reparents into a placeholder {@link Canvas} in its own
 * UI.
 *
 * <p>Deliberately narrower than {@code EmbedSocket}: no multi-client re-use
 * of one socket ({@link EmbedSocket#listen} keeps accepting new clients
 * after a detach), no voluntary host-initiated {@link
 * EmbedSocket#detachClient() detach}, no focus-next/focus-prev tab-cycling
 * callbacks, and no modality signaling. Reach for {@link EmbedSocket}
 * directly when any of those are needed — {@code EmbedSocket} has no Win32
 * equivalent yet.
 *
 * <p><b>{@link #embedOpaque} and {@link #embed(long)} are the same operation
 * on the Win32 backend.</b> X11 has a real distinction: {@link #embed(long)}
 * relies on the client having published {@code _XEMBED_INFO} itself, while
 * {@link #embedOpaque} writes it on the client's behalf and confirms the
 * reparent by polling instead of trusting a handshake. Win32 has no {@code
 * _XEMBED_INFO} equivalent at all — {@code SetParent} is the whole
 * operation regardless of which method is called — so both do the exact
 * same poll-verified {@code SetParent} there. Confirmed by a real-machine
 * spike; see {@code jembetter-core-win32}'s package-info.
 */
public interface EmbedHost extends AutoCloseable {

    /**
     * Creates a host bound to {@code hostCanvas}, the placeholder the
     * embedded client's window will become a genuine child of — a real X11
     * child (see {@link EmbedSocket#open(Canvas)}) or a Win32 {@code
     * SetParent} child, depending on {@code os.name}. {@code hostCanvas}
     * must already be part of a {@link java.awt.Frame}/{@link
     * javax.swing.JFrame}'s component tree (it doesn't need to be visible
     * yet) — on Windows, it must additionally already be displayable, since
     * extracting its HWND requires a real peer (see {@code
     * CanvasNativeHandle}, which also requires this JVM to be started with
     * {@code --add-opens java.desktop/java.awt=ALL-UNNAMED --add-opens
     * java.desktop/sun.awt.windows=ALL-UNNAMED}).
     */
    static EmbedHost create(Canvas hostCanvas) {
        return Platform.isWindows() ? new EmbedHostWin32(hostCanvas) : new EmbedHostX11(hostCanvas);
    }

    /**
     * Embeds a client process whose pid is already known, e.g. one this
     * host spawned itself — see {@link EmbedSocket#embed(long)}.
     */
    void embed(long clientPid);

    /**
     * Starts a Unix domain socket at {@code rendezvousSocket}, accepts
     * exactly one client connection there, embeds it, and returns — unlike
     * {@link EmbedSocket#listen}, this does not keep accepting further
     * clients afterward.
     */
    void embed(Path rendezvousSocket);

    /**
     * Embeds a client window whose id is already known, without relying on
     * the client's own cooperation — see {@link EmbedSocket#embedOpaque}.
     * Uses a fixed, generous poll budget internally; call {@link
     * EmbedSocket#embedOpaque(long, java.time.Duration, int)} directly if
     * that needs tuning.
     */
    void embedOpaque(long clientWindowId);

    /**
     * Registers a callback invoked when the embedded client's process exits
     * or crashes — see {@link EmbedSocket#onClientDetached}.
     */
    void onDetached(Runnable callback);

    /**
     * Gives the embedded client input focus on this host's own initiative —
     * see {@link EmbedSocket#focusClient()}.
     */
    void requestFocus();

    /**
     * Releases this host's resources — the underlying {@link EmbedSocket}
     * and its X11 window on the X11 backend; nothing OS-level on the Win32
     * backend, which has no separate socket window of its own.
     */
    @Override
    void close();

    /**
     * Same as {@link #close()}, but when {@code destroyClient} is {@code
     * true}, a still-embedded client's window is destroyed instead of
     * gracefully released — see {@link EmbedSocket#destroyClient()}. For a
     * caller that knows the embedded client is a private renderer process
     * never meant to survive independently (e.g. one it spawned purely to
     * embed) and wants that guaranteed regardless of call order.
     *
     * <p><b>Not the same guarantee on both backends.</b> On the X11 backend
     * this is unconditional — {@code XDestroyWindow} removes the window
     * regardless of what the embedded client does. Win32 has no equivalent:
     * {@code DestroyWindow} can only be called by the thread that created
     * the window, so the Win32 backend instead posts {@code WM_CLOSE} to it
     * ({@code Win32Window#destroy} in {@code jembetter-core-win32}), which
     * only <em>asks</em> the client to close and depends on it not
     * overriding that to do something else. A caller that needs the Win32
     * guarantee to actually hold regardless of the client's own cooperation
     * still needs to kill its process directly.
     */
    void close(boolean destroyClient);
}
