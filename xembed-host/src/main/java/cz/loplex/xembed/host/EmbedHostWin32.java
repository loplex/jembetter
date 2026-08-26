package cz.loplex.xembed.host;

import cz.loplex.xembed.common.CanvasNativeHandle;
import cz.loplex.xembed.common.ipc.PidHandshake;
import cz.loplex.xembed.core.win32.Win32Focus;
import cz.loplex.xembed.core.win32.Win32Reparent;
import cz.loplex.xembed.core.win32.Win32WindowFinder;
import cz.loplex.xembed.core.win32.Win32WindowGeometry;

import java.awt.Canvas;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * {@link EmbedHost}'s Win32 implementation: {@code SetParent} onto the host
 * {@code Canvas}'s own HWND, confirmed by poll-verify — see {@code
 * xembed-core-win32}'s package-info for exactly what the 2026-08-26
 * real-machine spike confirmed about this flow (question 1).
 *
 * <p>Unlike {@link EmbedHostX11}, there is no {@code _XEMBED_INFO}/
 * {@code EMBEDDED_NOTIFY} handshake protocol to speak: Win32 has nothing
 * resembling it, so {@link #embed(long)} and {@link #embedOpaque(long)} both
 * boil down to the exact same {@code SetParent}+poll-verify operation here —
 * the design decision {@code EmbedHost}'s own Javadoc now documents.
 * Reparenting itself stays host-initiated (this class resolves the client's
 * HWND and calls {@code SetParent} itself), matching the X11 side, per the
 * spike's question 1 confirming that flow works.
 *
 * <p>Client death is detected via {@link ProcessHandle#onExit()}, confirmed
 * reliable for a foreign (not self-spawned) pid by the spike's question 3 —
 * no hand-rolled {@code Kernel32.OpenProcess}/{@code WaitForSingleObject}
 * watcher is needed. The {@link #onDetached} callback runs on whichever
 * thread completes that future (a JDK-internal thread, not the EDT).
 */
final class EmbedHostWin32 implements EmbedHost {

    private static final Duration WINDOW_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REPARENT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final int REPARENT_MAX_ATTEMPTS = 100;
    private static final long POLL_SLEEP_MILLIS = 50;

    private final Canvas hostCanvas;
    private final long hostCanvasHwnd;
    private volatile long embeddedHwnd = -1;
    private volatile Runnable onDetached = () -> {
    };

    EmbedHostWin32(Canvas hostCanvas) {
        this.hostCanvas = hostCanvas;
        this.hostCanvasHwnd = CanvasNativeHandle.extract(hostCanvas);
        hostCanvas.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                long id = embeddedHwnd;
                if (id >= 0) {
                    Win32WindowGeometry.moveResize(id, 0, 0, hostCanvas.getWidth(), hostCanvas.getHeight());
                }
            }
        });
    }

    @Override
    public void embed(long clientPid) {
        long clientHwnd = resolveClientWindow(clientPid);
        reparentAndWatch(clientHwnd, clientPid);
    }

    @Override
    public void embed(Path rendezvousSocket) {
        try {
            Files.deleteIfExists(rendezvousSocket);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(rendezvousSocket));
                try (SocketChannel accepted = server.accept()) {
                    embed(PidHandshake.receive(accepted));
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
        reparentAndWatch(clientWindowId, Win32WindowFinder.pidOfWindow(clientWindowId));
    }

    @Override
    public void onDetached(Runnable callback) {
        onDetached = callback;
    }

    @Override
    public void requestFocus() {
        long id = embeddedHwnd;
        if (id >= 0) {
            Win32Focus.set(id);
        }
    }

    @Override
    public void close() {
        // Nothing OS-level to release: unlike EmbedSocket's own X11 window,
        // this backend has no separate socket window - the host Canvas's own
        // HWND is the parent directly, and its lifecycle belongs to the
        // caller's own AWT tree.
    }

    private void reparentAndWatch(long clientHwnd, long clientPid) {
        Win32Reparent.reparent(clientHwnd, hostCanvasHwnd, 0, 0);
        Win32WindowGeometry.moveResize(clientHwnd, 0, 0, hostCanvas.getWidth(), hostCanvas.getHeight());
        waitForReparentConfirmed(clientHwnd);
        embeddedHwnd = clientHwnd;
        Win32Focus.set(clientHwnd);
        watchClientDeath(clientHwnd, clientPid);
    }

    private void watchClientDeath(long clientHwnd, long clientPid) {
        ProcessHandle.of(clientPid).ifPresentOrElse(
                handle -> handle.onExit().whenComplete((h, ex) -> handleClientDetached(clientHwnd)),
                () -> handleClientDetached(clientHwnd));
    }

    private void handleClientDetached(long clientHwnd) {
        if (embeddedHwnd == clientHwnd) {
            embeddedHwnd = -1;
        }
        onDetached.run();
    }

    private void waitForReparentConfirmed(long clientHwnd) {
        for (int attempt = 0; attempt < REPARENT_MAX_ATTEMPTS; attempt++) {
            if (Win32Reparent.parentOf(clientHwnd) == hostCanvasHwnd) {
                return;
            }
            sleep(REPARENT_POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException(
                "Client window " + clientHwnd + " was never confirmed reparented into the host Canvas");
    }

    private long resolveClientWindow(long clientPid) {
        long deadline = System.nanoTime() + WINDOW_LOOKUP_TIMEOUT.toNanos();
        List<Long> candidates;
        do {
            candidates = Win32WindowFinder.findTopLevelWindowsByPid(clientPid);
            if (!candidates.isEmpty()) {
                break;
            }
            sleep(POLL_SLEEP_MILLIS);
        } while (System.nanoTime() < deadline);

        if (candidates.isEmpty()) {
            throw new IllegalStateException("Client process " + clientPid + " never published a top-level window");
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException("Client process " + clientPid + " has " + candidates.size()
                    + " top-level windows; Win32 has no WM_CLASS-equivalent way to disambiguate them");
        }
        return candidates.get(0);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
