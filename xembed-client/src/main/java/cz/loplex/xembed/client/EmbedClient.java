package cz.loplex.xembed.client;

import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.WindowReparentWatcher;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedInfoProperty;

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
 * reparented by a host listening on a Unix domain socket, then watches for
 * that host dying afterward.
 *
 * <p><strong>v1:</strong> marks the window XEmbed-aware via
 * {@code _XEMBED_INFO} before offering it; see
 * {@code cz.loplex.xembed.host.EmbedSocket} for what is still missing
 * (proper focus protocol from this side).
 */
public final class EmbedClient implements AutoCloseable {

    private final X11Display display = X11Display.open(null);
    private final WindowReparentWatcher reparentWatcher = new WindowReparentWatcher();
    private long windowId = -1;
    private volatile Runnable onHostDetached = () -> {
    };

    /**
     * Registers a callback invoked when this window is reparented back to
     * the root window after having been embedded — what the X server does
     * automatically, with no XEmbed message involved, as soon as it notices
     * the host's connection is gone (the save-set mechanism {@link
     * cz.loplex.xembed.core.x11.Reparenting#reparent} relies on). Runs on
     * {@link WindowReparentWatcher}'s own background thread.
     */
    public void onHostDetached(Runnable callback) {
        onHostDetached = callback;
    }

    /**
     * Blocks until this process's own top-level window is visible to the
     * window manager, marks it XEmbed-aware, then hands its process id to
     * the host at {@code hostSocketPath} so the host can look the window up
     * and reparent it. Starts watching for the host's death immediately
     * afterward.
     */
    public void offer(Path hostSocketPath) {
        try {
            long pid = ProcessHandle.current().pid();
            windowId = waitForOwnWindow(display, pid);

            XEmbedInfoProperty.write(display.raw(), windowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));

            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(address);
                PidHandshake.send(channel, pid);
            }

            reparentWatcher.watch(windowId, this::handleReparented);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void handleReparented(long newParentId) {
        if (newParentId == display.defaultRootWindow().longValue()) {
            onHostDetached.run();
        }
    }

    private static long waitForOwnWindow(X11Display display, long pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> ownWindows;
        do {
            ownWindows = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!ownWindows.isEmpty()) {
                return ownWindows.get(0);
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

    @Override
    public void close() {
        reparentWatcher.close();
        display.close();
    }
}
