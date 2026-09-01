package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.ipc.PidHandshake;

import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Window;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * {@link EmbedHost}'s X11 implementation, via {@link EmbedSocket} — see
 * {@link EmbedHostWin32} for the Win32 counterpart {@link EmbedHost#create}
 * dispatches to instead on Windows.
 */
final class EmbedHostX11 implements EmbedHost {

    private static final Duration OPAQUE_POLL_INTERVAL = Duration.ofMillis(20);
    private static final int OPAQUE_MAX_ATTEMPTS = 100;

    private final EmbedSocket socket;

    EmbedHostX11(Canvas hostCanvas) {
        Window window = SwingUtilities.getWindowAncestor(hostCanvas);
        if (!(window instanceof Frame frame)) {
            throw new IllegalArgumentException(
                    "hostCanvas must already be added to a Frame/JFrame's component tree to use EmbedHost.create(...)");
        }
        socket = new EmbedSocket(frame);
        socket.open(hostCanvas);
    }

    @Override
    public void embed(long clientPid) {
        socket.embed(clientPid);
    }

    @Override
    public void embed(Path rendezvousSocket) {
        try {
            Files.deleteIfExists(rendezvousSocket);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(rendezvousSocket));
                try (SocketChannel accepted = server.accept()) {
                    socket.embed(PidHandshake.receive(accepted));
                }
            } finally {
                Files.deleteIfExists(rendezvousSocket);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void embedOpaque(long clientWindowId) {
        socket.embedOpaque(clientWindowId, OPAQUE_POLL_INTERVAL, OPAQUE_MAX_ATTEMPTS);
    }

    @Override
    public void onDetached(Runnable callback) {
        socket.onClientDetached(callback);
    }

    @Override
    public void requestFocus() {
        socket.focusClient();
    }

    @Override
    public void close() {
        socket.close();
    }

    @Override
    public void tryDestroy() {
        socket.tryDestroy();
    }
}
