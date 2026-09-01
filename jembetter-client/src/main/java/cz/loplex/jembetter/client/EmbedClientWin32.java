package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.ModalityListener;
import cz.loplex.jembetter.common.ipc.ModalityOpcode;
import cz.loplex.jembetter.common.ipc.PidHandshake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * The client-side counterpart to {@code jembetter-host}'s {@code
 * EmbedSocketWin32#setModal(boolean)}: connects to a host's rendezvous
 * socket exactly like {@code EmbedPlugWin32#announce(Path, String)} does
 * (same {@link PidHandshake}), but — unlike that narrower facade — keeps its
 * end of the channel open afterward instead of closing it, since that
 * channel is the only place {@code EmbedSocketWin32#setModal(boolean)} has
 * anywhere to send to (see its Javadoc). A background thread then reads
 * {@link ModalityOpcode}-encoded bytes off it for the life of the embed and
 * dispatches them to {@link #onModalityChanged}.
 *
 * <p>Deliberately narrow: this only closes the {@code setModal} delivery gap
 * {@code docs/win32-status.md} tracked, not a full mirror of X11's {@code
 * EmbedClient} — it does nothing to resolve or watch this process's own
 * window (reparenting, focus, and host-detach detection already live on
 * {@code EmbedPlugWin32}, which a Win32 client can use independently of
 * this class), and it does not (yet) plug into {@code EmbedPlug}'s narrow
 * facade — {@code EmbedPlugWin32#announce(Path, String)} still closes its
 * handshake channel immediately after sending the pid, unaffected by this
 * class's existence.
 */
public final class EmbedClientWin32 implements AutoCloseable {

    private volatile SocketChannel controlChannel;
    private volatile Thread readerThread;
    private volatile ModalityListener onModalityChanged = modal -> {
    };

    /**
     * Connects to the host's rendezvous socket at {@code hostSocketPath} and
     * sends this process's own pid — the same handshake {@code
     * EmbedPlugWin32#announce(Path, String)} performs — but keeps the
     * channel open afterward and starts a background thread reading {@link
     * ModalityOpcode}-encoded bytes off it, dispatching each to {@link
     * #onModalityChanged}. Only meaningful against a host that keeps its own
     * end open too, i.e. one embedding this client via {@code
     * EmbedSocketWin32#listen(Path)} — a plain {@code embed(long)}/{@code
     * embed(Path)}/{@code embedOpaque(long)} host closes its side of the
     * handshake channel right away, so nothing would ever arrive here either
     * way.
     */
    public void connect(Path hostSocketPath) {
        if (controlChannel != null) {
            throw new IllegalStateException("Already connected");
        }
        SocketChannel channel;
        try {
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(address);
            PidHandshake.send(channel, ProcessHandle.current().pid());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        controlChannel = channel;
        readerThread = new Thread(this::readLoop, "jembetter-win32-embed-client-control-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Registers a callback invoked each time the host signals this client is
     * shadowed by (or no longer shadowed by) a modal dialog, via {@code
     * EmbedSocketWin32#setModal(boolean)}. Runs on this class's own
     * background reader thread.
     */
    public void onModalityChanged(ModalityListener callback) {
        onModalityChanged = callback;
    }

    private void readLoop() {
        SocketChannel channel = controlChannel;
        ByteBuffer buffer = ByteBuffer.allocate(1);
        try {
            while (true) {
                buffer.clear();
                if (channel.read(buffer) < 0) {
                    return; // Host closed its end — nothing more to read.
                }
                if (!buffer.hasRemaining()) {
                    onModalityChanged.modalityChanged(ModalityOpcode.decode(buffer.get(0)));
                }
            }
        } catch (IOException e) {
            // close() closes the channel to unblock this read() as its
            // shutdown signal; the host disappearing does the same via EOF
            // above, not this branch. Either way, nothing left to read.
        }
    }

    @Override
    public void close() {
        SocketChannel channel = controlChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // Best-effort cleanup of a channel already headed nowhere useful.
            }
        }
        Thread thread = readerThread;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
