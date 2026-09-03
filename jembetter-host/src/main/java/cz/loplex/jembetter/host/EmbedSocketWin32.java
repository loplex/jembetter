package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.ipc.ControlMessage;
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
 * <p><strong>{@link #setModal(boolean)}</strong> mirrors {@code
 * EmbedSocket#setModal}: a one-way, best-effort send (X11's own version is
 * best-effort in a different sense — it relies on {@code XSetInputFocus} to
 * actually do the work and only sends the {@code ClientMessage} as a
 * courtesy). Written as a {@link ControlMessage.Type#MODALITY} frame to the
 * client's own control channel, the same {@link SocketChannel} {@link
 * #listen} accepted the pid handshake on — kept open for the life of the
 * embed instead of closed right after, unlike {@link #embed(Path)}'s
 * one-shot handshake. That means this only has anywhere to send <em>to</em>
 * for a client embedded via {@link #listen}; a no-op for one embedded via
 * {@link #embed(long)}/{@link #embed(Path)}/{@link #embedOpaque(long)},
 * which never open a channel that outlives the handshake. A client built on
 * {@code jembetter-client}'s {@code EmbedClientWin32} reads this back via
 * its own {@code onModalityChanged}; one that only uses the narrower {@code
 * EmbedPlugWin32} facade still sees this fail silently against an
 * already-closed peer, since that facade's {@code announce(Path, String)}
 * closes its handshake channel immediately after sending its pid rather
 * than keeping it open.
 *
 * <p><strong>What this still lacks</strong> (see {@code
 * docs/win32-status.md}): no focus-next/focus-prev tab-cycling (X11's own
 * version has no working sender either — deliberately not chased for the
 * same reason). {@code EmbedSocket}'s {@code expectClientWindowClass} has no
 * counterpart here yet either — {@code Win32WindowFinder} has no {@code
 * WM_CLASS} equivalent to disambiguate multiple client windows with in the
 * first place (see {@code Win32EmbedCore}'s own {@code
 * IllegalStateException} for that case).
 */
public final class EmbedSocketWin32 implements AutoCloseable {

    private final Win32EmbedCore core;
    private volatile boolean listening = false;
    private ServerSocketChannel server;
    private Thread acceptThread;
    private volatile Runnable onClientEmbedded = () -> {
    };
    private volatile SocketChannel controlChannel;
    private volatile Thread controlChannelReaderThread;

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
                    core.embed(PidHandshake.receive(accepted));
                } catch (RuntimeException e) {
                    // A failed/aborted handshake must not take the accept
                    // loop down; the socket keeps listening for the next
                    // client.
                    closeQuietly(accepted);
                    e.printStackTrace();
                    continue;
                }
                // Kept open, unlike embed(Path)'s one-shot handshake: this is
                // this client's control channel for the rest of its embed,
                // e.g. for setModal(boolean) to write into. Closed once this
                // client detaches, below.
                controlChannel = accepted;
                controlChannelReaderThread = new Thread(() -> readControlChannel(accepted),
                        "jembetter-win32-embed-socket-control-reader");
                controlChannelReaderThread.setDaemon(true);
                controlChannelReaderThread.start();
                onClientEmbedded.run();
                awaitDetach();
                closeQuietly(controlChannel);
                controlChannel = null;
                joinControlChannelReader();
            }
        } finally {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Reads the client's control channel for the life of its embed, the
     * client-to-host counterpart of {@link #setModal}'s host-to-client
     * writes on the same channel: a {@link ControlMessage.Type#FOCUS_REQUEST}
     * frame (written by {@code
     * jembetter-client.EmbedClientWin32#requestFocus()}) gives the currently
     * embedded client input focus, the same as {@link #focusClient()}. Any
     * other frame type is ignored — nothing else flows in this direction on
     * this backend today.
     */
    private void readControlChannel(SocketChannel channel) {
        try {
            ControlMessage message;
            while ((message = ControlMessage.readFrom(channel)) != null) {
                if (message.type() == ControlMessage.Type.FOCUS_REQUEST) {
                    core.requestFocus();
                }
            }
        } catch (IOException e) {
            // closeQuietly(controlChannel) closes the channel to unblock
            // this read() as its shutdown signal once the client detaches;
            // anything else means the client's end is simply gone - either
            // way, nothing left to read.
        }
    }

    private void joinControlChannelReader() {
        Thread thread = controlChannelReaderThread;
        controlChannelReaderThread = null;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            // Best-effort cleanup of a channel already headed nowhere useful.
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

    /**
     * Tells the embedded client it's shadowed by (or no longer shadowed by)
     * a modal dialog — see this class's own Javadoc for exactly what this
     * does and doesn't guarantee on this backend today. No-op if nothing is
     * currently embedded, or if it wasn't embedded via {@link #listen}.
     */
    public void setModal(boolean modal) {
        SocketChannel channel = controlChannel;
        if (channel == null || !core.isEmbedded()) {
            return;
        }
        try {
            ControlMessage.of(ControlMessage.Type.MODALITY, modal).writeTo(channel);
        } catch (IOException e) {
            // Best-effort, no-receiver-required send - see this class's own
            // Javadoc on setModal(boolean) for why a failure here (e.g. the
            // peer already closed its end) is expected, not exceptional.
        }
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
        SocketChannel channel = controlChannel;
        if (channel != null) {
            closeQuietly(channel);
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
