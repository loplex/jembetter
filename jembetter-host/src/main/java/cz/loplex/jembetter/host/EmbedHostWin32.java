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
 * {@link EmbedHost}'s Win32 implementation: {@code SetParent} onto the host
 * {@code Canvas}'s own HWND, confirmed by poll-verify — see {@code
 * jembetter-core-win32}'s package-info for exactly what the 2026-08-26
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
 *
 * <p><strong>Click-to-focus:</strong> unlike {@code EmbedSocket}'s X11
 * backend (a passive {@code XGrabButton} that intercepts the press before
 * the client's own toolkit sees it, then replays it), a click on the
 * embedded area is observed rather than intercepted here — there is no
 * drop-in Win32 equivalent to X11's intercept-and-replay, since ordinary
 * window subclassing ({@code SetWindowSubclass}) only works within the
 * subclassing process's own address space and cannot reach across into a
 * genuinely separate process's HWND the way this class embeds one. Instead,
 * a single {@link Win32ClickWatcher} (a low-level mouse hook, {@code
 * SetWindowsHookEx(WH_MOUSE_LL, ...)} — runs in this process, no DLL
 * injected into the embedded one) watches every {@code WM_LBUTTONDOWN}
 * system-wide and calls {@link Win32Focus#set} whenever one lands inside
 * the currently embedded HWND's rect; see that class's Javadoc for the
 * mechanism, including what its {@code @Tag("windows")} tests under Wine
 * confirm about it versus what still needs a real-machine spike (the
 * documented caveats {@code SetWindowsHookEx} itself calls out: added
 * latency on every mouse event system-wide while installed, and UIPI
 * blocking the hook against a higher-integrity-level target). See {@code
 * docs/win32-status.md}.
 */
final class EmbedHostWin32 implements EmbedHost {

    private static final Duration WINDOW_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REPARENT_POLL_INTERVAL = Duration.ofMillis(20);
    private static final int REPARENT_MAX_ATTEMPTS = 100;
    private static final long POLL_SLEEP_MILLIS = 50;

    private final Canvas hostCanvas;
    private final long hostCanvasHwnd;
    private final Win32ClickWatcher clickWatcher = new Win32ClickWatcher();
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
        // The host Canvas's own HWND is the parent directly (unlike
        // EmbedSocket's own X11 window, this backend has no separate socket
        // window), so its lifecycle belongs to the caller's own AWT tree -
        // nothing to release there. The click-to-focus hook is this
        // instance's own resource, though, and must be unhooked.
        clickWatcher.close();
    }

    /**
     * Same as {@link #close()}, but when {@code destroyClient} is {@code
     * true}, also asks the embedded HWND to close via {@link
     * Win32Window#destroy} — unlike X11, Win32's {@code SetParent} has no
     * save-set concept to preserve, so there's no "graceful release" step on
     * this backend to begin with, just leaving the embedded HWND as-is.
     * See {@link EmbedHost#close(boolean)} for why this is best-effort here,
     * unlike the unconditional {@code XDestroyWindow} the X11 backend uses.
     */
    @Override
    public void close(boolean destroyClient) {
        clickWatcher.close();
        long id = embeddedHwnd;
        if (destroyClient && id >= 0) {
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
        if (embeddedHwnd == clientHwnd) {
            embeddedHwnd = -1;
        }
        clickWatcher.unwatch(clientHwnd);
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
