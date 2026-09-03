package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32ClickWatcher;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32Window;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import cz.loplex.jembetter.core.win32.Win32WindowGeometry;

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
 * The Win32 embed/detach/focus/watch mechanics shared by {@link
 * EmbedHostWin32} (the narrow, single-client {@link EmbedHost} facade) and
 * {@link EmbedSocketWin32} (the advanced API, mirroring {@code
 * jembetter-core-x11}'s {@link EmbedSocket} — see its own Javadoc for what's
 * built so far and {@code docs/win32-status.md} for what isn't yet).
 * Extracted here rather than duplicated once both classes needed it, the
 * same relationship {@code jembetter-core-x11} already has between {@link
 * EmbedHostX11} (thin wrapper) and {@link EmbedSocket} (does the actual
 * work) — except on this backend neither of the two public classes is
 * "the" implementation, so the shared mechanics live in this
 * package-private third class instead of one wrapping the other.
 */
final class Win32EmbedCore {

    private static final Duration REPARENT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final int REPARENT_MAX_ATTEMPTS = 100;
    private static final long POLL_SLEEP_MILLIS = 50;

    private final Canvas hostCanvas;
    private final long hostCanvasHwnd;
    private final Win32ClickWatcher clickWatcher = new Win32ClickWatcher();
    private volatile long embeddedHwnd = -1;
    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);
    private volatile Runnable onDetached = () -> {
    };

    Win32EmbedCore(Canvas hostCanvas) {
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

    void embed(long clientPid) {
        long clientHwnd = resolveClientWindow(clientPid);
        reparentAndWatch(clientHwnd, clientPid);
    }

    void embed(Path rendezvousSocket) {
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

    void embedOpaque(long clientWindowId) {
        reparentAndWatch(clientWindowId, Win32WindowFinder.pidOfWindow(clientWindowId));
    }

    void onDetached(Runnable callback) {
        onDetached = callback;
    }

    /** Whether a client is currently embedded — for {@link EmbedSocketWin32}'s accept loop to poll for a detach (voluntary or via death). */
    boolean isEmbedded() {
        return embeddedHwnd >= 0;
    }

    void requestFocus() {
        long id = embeddedHwnd;
        if (id >= 0) {
            Win32Focus.set(id);
        }
    }

    /**
     * Voluntarily releases the currently embedded client: restores its
     * pre-embed {@code WS_POPUP} style, reparents it back to the desktop
     * window at its current on-screen position (so it doesn't visually jump
     * — see {@link Win32WindowGeometry#screenPosition}), and stops watching
     * it for clicks. No-op if nothing is currently embedded.
     *
     * <p>Unlike a client death, this must not fire {@link #onDetached} — the
     * caller already knows, exactly as {@code EmbedSocket#detachClient()}
     * documents on the X11 side. {@link #handleClientDetached} already
     * guards on {@code embeddedHwnd} still matching the process that just
     * exited, so clearing it here first is enough: the {@link
     * ProcessHandle#onExit()} future registered in {@link #watchClientDeath}
     * has no cancellation hook on this JDK API, but its eventual completion
     * for this now-detached window becomes a no-op once {@code embeddedHwnd}
     * no longer matches it.
     */
    void detachClient() {
        long id = embeddedHwnd;
        if (id < 0) {
            return;
        }
        int[] screenPosition = Win32WindowGeometry.screenPosition(id);
        embeddedHwnd = -1;
        clickWatcher.unwatch(id);
        Win32Reparent.release(id, screenPosition[0], screenPosition[1]);
    }

    void close() {
        // The host Canvas's own HWND is the parent directly (unlike
        // EmbedSocket's own X11 window, this backend has no separate socket
        // window), so its lifecycle belongs to the caller's own AWT tree -
        // nothing to release there. The click-to-focus hook is this
        // instance's own resource, though, and must be unhooked.
        clickWatcher.close();
    }

    /**
     * Same as {@link #close()}, but also asks the embedded HWND to close via
     * {@link Win32Window#destroy} — unlike X11, Win32's {@code SetParent}
     * has no save-set concept to preserve, so there's no "graceful release"
     * step on this backend to begin with, just leaving the embedded HWND
     * as-is. See {@link EmbedHost#tryDestroy()} for why this is best-effort
     * here, unlike the unconditional {@code XDestroyWindow} the X11 backend
     * uses.
     */
    void tryDestroy() {
        clickWatcher.close();
        long id = embeddedHwnd;
        if (id >= 0) {
            Win32Window.destroy(id);
        }
    }

    private void reparentAndWatch(long clientHwnd, long clientPid) {
        Win32Reparent.reparent(clientHwnd, hostCanvasHwnd, 0, 0);
        Win32WindowGeometry.moveResize(clientHwnd, 0, 0, hostCanvas.getWidth(), hostCanvas.getHeight());
        waitForReparentConfirmed(clientHwnd);
        embeddedHwnd = clientHwnd;
        Win32Focus.set(clientHwnd);
        clickWatcher.watch(clientHwnd, () -> Win32Focus.set(clientHwnd));
        watchClientDeath(clientHwnd, clientPid);
    }

    private void watchClientDeath(long clientHwnd, long clientPid) {
        ProcessHandle.of(clientPid).ifPresentOrElse(
                handle -> handle.onExit().whenComplete((h, ex) -> handleClientDetached(clientHwnd)),
                () -> handleClientDetached(clientHwnd));
    }

    private void handleClientDetached(long clientHwnd) {
        // Guards against a stale death notification for a client that was
        // already voluntarily detached (or replaced by a later embed) by
        // the time its process actually exits - without this, detachClient()
        // followed by that same process dying later would fire onDetached
        // for a client the caller already knows is gone, or worse, for a
        // *different* client meanwhile embedded in its place.
        if (embeddedHwnd == clientHwnd) {
            embeddedHwnd = -1;
            clickWatcher.unwatch(clientHwnd);
            onDetached.run();
        }
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

    /** Parity shim for {@code EmbedSocketX11#setWindowLookupTimeout} — see {@link EmbedSocket#setWindowLookupTimeout}. */
    void setWindowLookupTimeout(Duration timeout) {
        windowLookupTimeout = timeout;
    }

    private long resolveClientWindow(long clientPid) {
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        List<Long> candidates;
        do {
            candidates = Win32WindowFinder.findApplicationWindowsByPid(clientPid);
            if (!candidates.isEmpty()) {
                break;
            }
            sleep(POLL_SLEEP_MILLIS);
        } while (System.nanoTime() < deadline);

        if (candidates.isEmpty()) {
            throw new IllegalStateException("Client process " + clientPid + " never published a top-level window");
        }
        if (candidates.size() > 1) {
            String dump = candidates.stream()
                    .map(Win32WindowFinder::describeWindow)
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IllegalStateException("Client process " + clientPid + " has " + candidates.size()
                    + " application windows; Win32 has no WM_CLASS-equivalent way to disambiguate them: " + dump);
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
