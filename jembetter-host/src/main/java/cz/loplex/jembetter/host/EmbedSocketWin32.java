package cz.loplex.jembetter.host;

import java.awt.Canvas;
import java.nio.file.Path;

/**
 * The advanced, Win32 counterpart to {@code jembetter-core-x11}'s {@link
 * EmbedSocket} — not yet at parity with it (see {@code
 * docs/win32-status.md}'s "Not yet implemented" list), but no longer just
 * {@link EmbedHost}'s narrow single-client facade either. Built on {@link
 * Win32EmbedCore}, the same embed/watch mechanics {@link EmbedHostWin32}
 * uses, so the two stay behaviorally identical wherever their feature sets
 * overlap.
 *
 * <p><strong>What this adds over {@link EmbedHost}:</strong> {@link
 * #detachClient()}, a voluntary host-initiated release — the host can swap
 * in a different client afterward by calling {@link #embed(long)}/{@link
 * #embed(Path)}/{@link #embedOpaque(long)} again, without the embedded
 * client's process needing to exit first. Mirrors {@code
 * EmbedSocket#detachClient()}: releases the client back to the desktop
 * window at its current on-screen position (so it doesn't visually jump)
 * and does not fire {@link #onClientDetached} — the caller already knows.
 *
 * <p><strong>What this still lacks</strong> (see {@code
 * docs/win32-status.md}): no {@code listen}-style accept loop that keeps
 * reusing one socket across clients (a caller drives re-embedding itself,
 * one {@link #embed} call at a time), no focus-next/focus-prev tab-cycling,
 * and no modality signaling. {@code EmbedSocket}'s {@code
 * expectClientWindowClass}/{@code onFocusNext}/{@code onFocusPrev}/{@code
 * setModal} have no counterpart here yet — {@code Win32WindowFinder} has no
 * {@code WM_CLASS} equivalent to disambiguate multiple client windows with
 * in the first place (see {@code Win32EmbedCore}'s own {@code
 * IllegalStateException} for that case).
 */
public final class EmbedSocketWin32 implements AutoCloseable {

    private final Win32EmbedCore core;

    public EmbedSocketWin32(Canvas hostCanvas) {
        this.core = new Win32EmbedCore(hostCanvas);
    }

    /** Embeds a client process whose pid is already known — see {@link EmbedHost#embed(long)}. */
    public void embed(long clientPid) {
        core.embed(clientPid);
    }

    /** Starts a rendezvous socket, accepts exactly one client connection, embeds it, and returns — see {@link EmbedHost#embed(Path)}. */
    public void embed(Path rendezvousSocket) {
        core.embed(rendezvousSocket);
    }

    /** Embeds a client window whose id is already known — see {@link EmbedHost#embedOpaque(long)}. */
    public void embedOpaque(long clientWindowId) {
        core.embedOpaque(clientWindowId);
    }

    /** Registers a callback invoked when the embedded client's process exits or crashes — does not fire for {@link #detachClient()}. */
    public void onClientDetached(Runnable callback) {
        core.onDetached(callback);
    }

    /** Gives the embedded client input focus on this host's own initiative — see {@link EmbedHost#requestFocus()}. */
    public void focusClient() {
        core.requestFocus();
    }

    /**
     * Voluntarily releases the currently embedded client so a different one
     * can be embedded next, as opposed to only ever finding out a client is
     * gone after the fact via {@link #onClientDetached} (which does not
     * fire for this — the caller already knows). No-op if nothing is
     * currently embedded. See {@link Win32EmbedCore#detachClient()} for the
     * mechanism.
     */
    public void detachClient() {
        core.detachClient();
    }

    @Override
    public void close() {
        core.close();
    }

    /** Same as {@link #close()}, but a still-embedded client's window is asked to close too — see {@link EmbedHost#tryDestroy()}. */
    public void tryDestroy() {
        core.tryDestroy();
    }
}
