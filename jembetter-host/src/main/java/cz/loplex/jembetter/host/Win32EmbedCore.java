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
import java.awt.event.ComponentListener;
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
import java.util.Objects;

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
    private static final long POLL_SLEEP_MILLIS = 50;

    private final Canvas hostCanvas;
    private final long hostCanvasHwnd;
    private final Win32ClickWatcher clickWatcher;
    private final ComponentListener hostCanvasResizeListener;
    private volatile long embeddedHwnd = -1;
    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);
    private volatile Runnable onDetached = () -> {
    };

    Win32EmbedCore(Canvas hostCanvas) {
        this.hostCanvas = Objects.requireNonNull(hostCanvas, "hostCanvas");
        // Before the watcher, not after: extract() is the step that can fail
        // (no native peer yet), and a watcher created first would be left
        // running with nothing able to close it, since the caller never gets
        // an object back.
        this.hostCanvasHwnd = CanvasNativeHandle.extract(hostCanvas);
        this.clickWatcher = new Win32ClickWatcher();
        // Kept in a field so close() can take it off again: a listener left
        // on a canvas that outlives this core goes on resizing an HWND this
        // instance no longer manages, and holds the instance reachable for
        // as long as the canvas lives.
        this.hostCanvasResizeListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                long id = embeddedHwnd;
                if (id >= 0) {
                    Win32WindowGeometry.moveResize(id, 0, 0, hostCanvas.getWidth(), hostCanvas.getHeight());
                }
            }
        };
        hostCanvas.addComponentListener(hostCanvasResizeListener);
    }

    void embed(long clientPid) {
        requireNoClient();
        long clientHwnd = resolveClientWindow(clientPid);
        reparentAndWatch(clientHwnd, clientPid);
    }

    void embed(Path rendezvousSocket) {
        Objects.requireNonNull(rendezvousSocket, "rendezvousSocket");
        // Before binding, not after: a core that already holds a client
        // should say so now rather than after waiting for one to connect.
        requireNoClient();
        try {
            Files.deleteIfExists(rendezvousSocket);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(rendezvousSocket));
                RendezvousSocket.restrictToOwner(rendezvousSocket);
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
        requireNoClient();
        reparentAndWatch(clientWindowId, Win32WindowFinder.pidOfWindow(clientWindowId));
    }

    /**
     * Releases the currently embedded client and embeds {@code clientPid}'s
     * window in its place — the deliberate replacement a second {@link
     * #embed(long)} is no longer allowed to stand in for. See {@code
     * EmbedSocket#swapClient(long)}.
     */
    void swapClient(long clientPid) {
        detachClient();
        embed(clientPid);
    }

    /** Same as {@link #swapClient(long)}, but for a window embedded the way {@link #embedOpaque(long)} embeds one. */
    void swapClientOpaque(long clientWindowId) {
        detachClient();
        embedOpaque(clientWindowId);
    }

    /**
     * Rejects an embed into a core that already holds a client. Without
     * this, a second embed overwrote {@code embeddedHwnd} and left the
     * first client reparented into the host canvas with nothing tracking
     * it: {@link #detachClient()} released only the second, so the first
     * stayed a {@code WS_CHILD} of a window that is about to go away,
     * taking it with it — and unlike X11 there is no save-set to rescue it.
     */
    private void requireNoClient() {
        if (isEmbedded()) {
            throw new IllegalStateException("A client is already embedded in this socket; call detachClient() first, "
                    + "or swapClient(long)/swapClientOpaque(long) to replace it in one step");
        }
    }

    void onDetached(Runnable callback) {
        onDetached = Objects.requireNonNull(callback, "callback");
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

    /** Whether {@link #close()} stopped the click watcher's thread — see {@code EmbedSocket#closedCleanly()}. */
    boolean stoppedCleanly() {
        return clickWatcher.stoppedCleanly();
    }

    void close() {
        // The host Canvas's own HWND is the parent directly (unlike
        // EmbedSocket's own X11 window, this backend has no separate socket
        // window), so its lifecycle belongs to the caller's own AWT tree -
        // nothing to release there. The click-to-focus hook is this
        // instance's own resource, though, and must be unhooked.
        hostCanvas.removeComponentListener(hostCanvasResizeListener);
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
        hostCanvas.removeComponentListener(hostCanvasResizeListener);
        clickWatcher.close();
        long id = embeddedHwnd;
        if (id >= 0) {
            Win32Window.destroy(id);
        }
    }

    private void reparentAndWatch(long clientHwnd, long clientPid) {
        // One SetWindowPos, not a reparent followed by a resize: the two-call
        // form lets the client observe the size the window happens to have
        // between losing its decorations and being given the host's geometry.
        Win32Reparent.reparent(clientHwnd, hostCanvasHwnd, 0, 0,
                hostCanvas.getWidth(), hostCanvas.getHeight());
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

    /**
     * Polls until the reparent shows up, for as long as {@link
     * #setWindowLookupTimeout} allows — the same budget the window lookup
     * uses, rather than the fixed two seconds this used to have. See {@code
     * EmbedSocketX11#reparentConfirmAttempts} for why they share one knob.
     */
    private void waitForReparentConfirmed(long clientHwnd) {
        long interval = Math.max(1, REPARENT_POLL_INTERVAL.toMillis());
        long attempts = Math.max(1, windowLookupTimeout.toMillis() / interval);
        for (long attempt = 0; attempt < attempts; attempt++) {
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
        windowLookupTimeout = Objects.requireNonNull(timeout, "timeout");
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
        return candidates.getFirst();
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
