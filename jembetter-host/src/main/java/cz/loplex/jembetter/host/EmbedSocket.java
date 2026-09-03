package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.Platform;

import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The advanced, multi-client host-side API — the backend-portable type a
 * caller programs against for anything {@link EmbedHost}'s narrow 1:1 facade
 * leaves out: several embedded clients at once (one {@code EmbedSocket}
 * each), a voluntary host-initiated {@link #detachClient() detach}/re-embed,
 * or modality / host-window-activation signaling to the client. Dispatched
 * by {@code os.name} to an X11 backend ({@link EmbedSocketX11}, a real X11
 * child window) or a Win32 backend ({@link EmbedSocketWin32}, {@code
 * SetParent}), matching the {@link EmbedHost}/{@code EmbedHostX11}/{@code
 * EmbedHostWin32} split on the same side.
 *
 * <p>This interface is the <strong>intersection</strong> of what both
 * backends implement (plus a couple of cheap parity shims). X11-only
 * capabilities — override-redirect {@link EmbedSocketX11#open(int, int, int,
 * int)}, focus-next/prev tab-cycling, {@link EmbedSocketX11#destroyClient()}'s
 * unconditional {@code XDestroyWindow}, {@code WM_CLASS} disambiguation,
 * geometry control — live on {@link EmbedSocketX11} only; a caller that needs
 * one downcasts to it explicitly, a visible opt-in to platform coupling.
 *
 * <p><b>{@link #embedOpaque(long)} and {@link #embed(long)} are the same
 * operation on the Win32 backend</b> — see {@link EmbedHost} for why (Win32
 * has no {@code _XEMBED_INFO} equivalent).
 */
public interface EmbedSocket extends AutoCloseable {

    /**
     * Creates a socket bound to {@code hostCanvas}, the placeholder the
     * embedded client's window will become a genuine child of — a real X11
     * child (see {@link EmbedSocketX11#open(Canvas)}) or a Win32 {@code
     * SetParent} child, depending on {@code os.name}. {@code hostCanvas}
     * must already be part of a {@link java.awt.Frame}/{@link
     * javax.swing.JFrame}'s component tree (on Windows it must additionally
     * already be displayable — see {@link EmbedHost#create}).
     */
    static EmbedSocket create(Canvas hostCanvas) {
        if (Platform.isWindows()) {
            return new EmbedSocketWin32(hostCanvas);
        }
        Window window = SwingUtilities.getWindowAncestor(hostCanvas);
        if (!(window instanceof Frame frame)) {
            throw new IllegalArgumentException(
                    "hostCanvas must already be added to a Frame/JFrame's component tree to use EmbedSocket.create(...)");
        }
        EmbedSocketX11 socket = new EmbedSocketX11(frame);
        socket.open(hostCanvas);
        return socket;
    }

    /**
     * Embeds a client process whose pid is already known, e.g. one this
     * host spawned itself — see {@link EmbedSocketX11#embed(long)}.
     */
    void embed(long clientPid);

    /**
     * Starts a Unix domain socket at {@code rendezvousSocket}, accepts
     * exactly one client connection there, embeds it, and returns — unlike
     * {@link #listen}, this does not keep accepting further clients
     * afterward.
     */
    void embed(Path rendezvousSocket);

    /**
     * Embeds a client window whose id is already known, without relying on
     * the client's own cooperation — see {@link
     * EmbedSocketX11#embedOpaque(long, Duration, int)}. Uses a fixed,
     * generous poll budget internally; downcast to {@link EmbedSocketX11}
     * for the tuning overload.
     */
    void embedOpaque(long clientWindowId);

    /**
     * Starts a background accept loop over a persistent rendezvous socket at
     * {@code socketPath}, embedding each connecting client in turn and,
     * once it detaches, going back to accepting the next one — see {@link
     * EmbedSocketX11#listen}.
     */
    void listen(Path socketPath);

    /**
     * Voluntarily releases the currently embedded client so a different one
     * can be embedded next — does not fire {@link #onClientDetached}. No-op
     * if nothing is currently embedded. See {@link
     * EmbedSocketX11#detachClient()}.
     */
    void detachClient();

    /**
     * Gives the embedded client input focus on this host's own initiative —
     * see {@link EmbedSocketX11#focusClient()}.
     */
    void focusClient();

    /**
     * Tells the embedded client it's shadowed by (or no longer shadowed by)
     * a modal dialog — see {@link EmbedSocketX11#setModal(boolean)}. Only
     * has a delivery path for a client embedded via {@link #listen}.
     */
    void setModal(boolean modal);

    /**
     * Overrides how long a connecting client is given to publish its
     * top-level window before its handshake attempt is abandoned. Defaults
     * to 5 seconds.
     */
    void setWindowLookupTimeout(Duration timeout);

    /**
     * Registers a callback invoked each time a client finishes being
     * embedded via {@link #listen} — see {@link
     * EmbedSocketX11#onClientEmbedded}.
     */
    void onClientEmbedded(Runnable callback);

    /**
     * Registers a callback invoked when the embedded client's process exits
     * or crashes — see {@link EmbedSocketX11#onClientDetached}.
     */
    void onClientDetached(Runnable callback);

    /**
     * Releases this socket's resources — its X11 window and background
     * threads on the X11 backend; the Win32 backend has no separate socket
     * window of its own.
     */
    @Override
    void close();

    /**
     * Same as {@link #close()}, but a still-embedded client's window is
     * destroyed instead of gracefully released — see {@link
     * EmbedSocketX11#destroyClient()}. <b>Not the same guarantee on both
     * backends</b> — the name is a reminder, not a promise: unconditional on
     * X11 ({@code XDestroyWindow}), best-effort on Win32 ({@code WM_CLOSE}
     * only asks) — see {@link EmbedHost#tryDestroy()}.
     */
    void tryDestroy();
}
