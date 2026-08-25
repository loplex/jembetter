package cz.loplex.xembed.host;

import java.awt.Canvas;
import java.nio.file.Path;

/**
 * Simplified 1:1 host-side facade over {@link EmbedSocket}, for the common
 * case of a host embedding exactly one client for the lifetime of a single
 * child process — e.g. a JVM the host spawns itself and reparents into a
 * placeholder {@link Canvas} in its own UI. Composed entirely from {@link
 * EmbedSocket}; nothing here re-implements X11 handling of its own.
 *
 * <p>Deliberately narrower than {@code EmbedSocket}: no multi-client re-use
 * of one socket ({@link EmbedSocket#listen} keeps accepting new clients
 * after a detach), no voluntary host-initiated {@link
 * EmbedSocket#detachClient() detach}, no focus-next/focus-prev tab-cycling
 * callbacks, and no modality signaling. Reach for {@link EmbedSocket}
 * directly when any of those are needed.
 */
public interface EmbedHost extends AutoCloseable {

    /**
     * Creates a host bound to {@code hostCanvas}, the placeholder the
     * embedded client's window will become a genuine X11 child of — see
     * {@link EmbedSocket#open(Canvas)}. {@code hostCanvas} must already be
     * part of a {@link java.awt.Frame}/{@link javax.swing.JFrame}'s
     * component tree (it doesn't need to be visible yet).
     */
    static EmbedHost create(Canvas hostCanvas) {
        return new EmbedHostX11(hostCanvas);
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

    /** Releases the underlying {@link EmbedSocket} and its X11 window. */
    @Override
    void close();
}
