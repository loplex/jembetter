package cz.loplex.xembed.client;

import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.X11Display;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Makes this process's own already-visible top-level window available to be
 * reparented by a host listening on a Unix domain socket.
 *
 * <p><strong>v0:</strong> see {@code cz.loplex.xembed.host.EmbedSocket} for
 * what is and isn't implemented yet.
 */
public final class EmbedClient {

    private EmbedClient() {
    }

    /**
     * Blocks until this process's own top-level window is visible to the
     * window manager, then hands its process id to the host at
     * {@code hostSocketPath} so the host can look the window up and
     * reparent it.
     */
    public static void offer(Path hostSocketPath) {
        try (X11Display display = X11Display.open(null)) {
            long pid = ProcessHandle.current().pid();
            waitForOwnWindow(display, pid);

            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(address);
                PidHandshake.send(channel, pid);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void waitForOwnWindow(X11Display display, long pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> ownWindows;
        do {
            ownWindows = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!ownWindows.isEmpty()) {
                return;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Could not resolve this process's own top-level window");
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
