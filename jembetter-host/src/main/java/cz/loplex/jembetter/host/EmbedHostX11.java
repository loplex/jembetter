package cz.loplex.jembetter.host;

import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Window;
import java.nio.file.Path;

/**
 * {@link EmbedHost}'s X11 implementation, via {@link EmbedSocketX11} — see
 * {@link EmbedHostWin32} for the Win32 counterpart {@link EmbedHost#create}
 * dispatches to instead on Windows.
 */
final class EmbedHostX11 implements EmbedHost {

    private final EmbedSocketX11 socket;

    EmbedHostX11(Canvas hostCanvas) {
        Window window = SwingUtilities.getWindowAncestor(hostCanvas);
        if (!(window instanceof Frame frame)) {
            throw new IllegalArgumentException(
                    "hostCanvas must already be added to a Frame/JFrame's component tree to use EmbedHost.create(...)");
        }
        socket = new EmbedSocketX11(frame);
        socket.open(hostCanvas);
    }

    @Override
    public void embed(long clientPid) {
        socket.embed(clientPid);
    }

    @Override
    public void embed(Path rendezvousSocket) {
        socket.embed(rendezvousSocket);
    }

    @Override
    public void embedOpaque(long clientWindowId) {
        socket.embedOpaque(clientWindowId);
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
