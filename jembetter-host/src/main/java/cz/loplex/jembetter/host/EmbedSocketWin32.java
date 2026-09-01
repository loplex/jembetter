package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.ipc.PidHandshake;

import java.awt.Canvas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
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
 * <p><strong>{@link #listen(Path)}</strong> mirrors {@code
 * EmbedSocket#listen}: starts a background accept loop over a persistent
 * rendezvous socket, embedding each connecting client in turn and, once it
 * detaches (voluntarily via {@link #detachClient()}, or via death), going
 * back to accepting the next one — so a host survives a client crash/exit
 * without needing to restart, and can keep swapping clients in
 * indefinitely.
 *
 * <p><strong>What this still lacks</strong> (see {@code
 * docs/win32-status.md}): no focus-next/focus-prev tab-cycling, and no
 * modality signaling. {@code EmbedSocket}'s {@code
 * expectClientWindowClass}/{@code onFocusNext}/{@code onFocusPrev}/{@code
 * setModal} have no counterpart here yet — {@code Win32WindowFinder} has no
 * {@code WM_CLASS} equivalent to disambiguate multiple client windows with
 * in the first place (see {@code Win32EmbedCore}'s own {@code
 * IllegalStateException} for that case).
 */
public final class EmbedSocketWin32 implements AutoCloseable {

    private final Win32EmbedCore core;
    private volatile boolean listening = false;
    private ServerSocketChannel server;
    private Thread acceptThread;
    private volatile Runnable onClientEmbedded = () -> {
    };

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

    /**
     * Starts listening on {@code socketPath} on a background thread and
     * keeps accepting client connections there for as long as this socket
     * stays open — see this class's own Javadoc. Each accepted client is
     * embedded exactly as {@link #embed(long)} would do it; once it
     * detaches, the socket goes back to accepting the next one instead of
     * being good for exactly one embed the way {@link #embed(Path)} is.
     */
    public void listen(Path socketPath) {
        if (listening) {
            throw new IllegalStateException("Already listening");
        }
        try {
            Files.deleteIfExists(socketPath);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        listening = true;
        acceptThread = new Thread(() -> acceptLoop(socketPath), "jembetter-win32-embed-socket-accept-loop");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop(Path socketPath) {
        try {
            while (listening) {
                SocketChannel accepted;
                try {
                    accepted = server.accept();
                } catch (IOException e) {
                    // close()/tryDestroy() close the server channel to
                    // unblock this accept() as their shutdown signal;
                    // anything else is a real failure.
                    if (!listening) {
                        return;
                    }
                    throw new UncheckedIOException(e);
                }
                try {
                    try (accepted) {
                        core.embed(PidHandshake.receive(accepted));
                    }
                } catch (RuntimeException | IOException e) {
                    // A failed/aborted handshake must not take the accept
                    // loop down; the socket keeps listening for the next
                    // client.
                    e.printStackTrace();
                    continue;
                }
                onClientEmbedded.run();
                awaitDetach();
            }
        } finally {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Blocks the accept loop until the currently embedded client detaches, or listening stops. */
    private void awaitDetach() {
        while (listening && core.isEmbedded()) {
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers a callback invoked each time a client finishes being
     * embedded via {@link #listen} — the initial one, and again after any
     * later re-embed following a previous detach. Runs on the accept loop's
     * own background thread.
     */
    public void onClientEmbedded(Runnable callback) {
        onClientEmbedded = callback;
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
        stopListening();
        core.close();
    }

    /** Same as {@link #close()}, but a still-embedded client's window is asked to close too — see {@link EmbedHost#tryDestroy()}. */
    public void tryDestroy() {
        stopListening();
        core.tryDestroy();
    }

    private void stopListening() {
        listening = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
