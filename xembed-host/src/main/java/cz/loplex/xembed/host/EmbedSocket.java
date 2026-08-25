package cz.loplex.xembed.host;

import com.sun.jna.platform.unix.X11.Display;
import cz.loplex.xembed.common.CanvasNativeHandle;
import cz.loplex.xembed.common.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.InputFocus;
import cz.loplex.xembed.core.x11.RawWindow;
import cz.loplex.xembed.core.x11.Reparenting;
import cz.loplex.xembed.core.x11.WindowDeathWatcher;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.WindowGeometry;
import cz.loplex.xembed.core.x11.WindowRelease;
import cz.loplex.xembed.core.x11.WindowTree;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.xembed.XEmbedFocus;
import cz.loplex.xembed.core.xembed.XEmbedInboundWatcher;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedInfoProperty;
import cz.loplex.xembed.core.xembed.XEmbedMessage;
import cz.loplex.xembed.core.xembed.XEmbedMessages;

import java.awt.Canvas;
import java.awt.Frame;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A raw X11 window that a client process's own top-level window gets
 * reparented into: either a real child of an AWT {@link Canvas}'s own
 * native window ({@link #open(Canvas)}, the recommended path — see its
 * Javadoc), or a root-level, override-redirect window kept positioned over
 * wherever the host wants it on screen ({@link #open(int, int, int, int)},
 * for callers with no AWT tree to hang a {@code Canvas} off of).
 *
 * <p><strong>v4:</strong> unlike v0-v3, this is no longer a {@code
 * java.awt.Window}. AWT manages its own internal X11 connection, so
 * ClientMessages sent <em>to</em> an AWT-backed socket window (e.g.
 * XEMBED_REQUEST_FOCUS, or PropertyNotify on a client's _XEMBED_INFO) landed
 * on AWT's connection, not this library's — unreadable without reflecting
 * into JDK-internal AWT classes. Owning the window via {@code xembed-core}'s
 * own {@link X11Display} instead means those events can be read directly.
 * The tradeoff: this window is not part of AWT's own window tree, so with
 * {@link #open(int, int, int, int)} the host is responsible for keeping it
 * positioned over wherever it should appear (e.g. a placeholder Swing
 * component's {@code getLocationOnScreen()} plus a resize/move listener
 * calling {@link #setBounds}) rather than laying it out with the rest of
 * its UI — {@link #open(Canvas)} avoids that entirely by making this
 * window a genuine X11 child of the placeholder itself.
 */
public final class EmbedSocket implements AutoCloseable {

    private final Frame owner;
    private final X11Display display = X11Display.open(null);
    private final WindowDeathWatcher deathWatcher = new WindowDeathWatcher();
    private XEmbedInboundWatcher inbound;
    private long windowId = -1;
    private volatile int width = -1;
    private volatile int height = -1;
    private volatile long embeddedWindowId = -1;
    private volatile boolean listening = false;
    private ServerSocketChannel server;
    private Thread acceptThread;
    private volatile Runnable onClientDetached = () -> {
    };
    private volatile Runnable onClientEmbedded = () -> {
    };
    private volatile Runnable onFocusNext = () -> {
    };
    private volatile Runnable onFocusPrev = () -> {
    };
    private volatile String expectedClientWmClass;
    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);

    public EmbedSocket(Frame owner) {
        this.owner = owner;
        owner.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                sendActivated(true);
            }

            @Override
            public void windowLostFocus(WindowEvent event) {
                sendActivated(false);
            }
        });
    }

    /** Creates the underlying X11 window at the given screen bounds and starts watching it for inbound XEmbed messages. */
    public void open(int x, int y, int width, int height) {
        windowId = RawWindow.createOverrideRedirect(display, x, y, width, height);
        initInboundWatcher(width, height);
    }

    /**
     * Creates the underlying X11 window as a real child of {@code
     * hostCanvas}'s own native window instead of a root-level
     * override-redirect sibling, so normal X11 stacking (and the window
     * manager) treats it as part of the host window — a heavyweight AWT/
     * Swing popup, tooltip, or modal dialog from the host correctly renders
     * above it, unlike with {@link #open(int, int, int, int)}. Also attaches
     * a {@link java.awt.event.ComponentListener} to {@code hostCanvas} that
     * calls {@link #resize} automatically on every resize, so the caller
     * doesn't have to track it manually the way {@link #setBounds} requires.
     *
     * <p>{@code hostCanvas} must already be displayable (i.e. part of a
     * visible window). <strong>Known limitation:</strong> disposing {@code
     * hostCanvas} (or its containing window) without calling {@link #close()}
     * first leaves this socket's child window orphaned — there is no
     * automatic cleanup tied to the canvas's own lifecycle yet.
     */
    public void open(Canvas hostCanvas) {
        long canvasWindowId = CanvasNativeHandle.extract(hostCanvas);
        windowId = RawWindow.createChild(display, canvasWindowId, hostCanvas.getWidth(), hostCanvas.getHeight());
        initInboundWatcher(hostCanvas.getWidth(), hostCanvas.getHeight());
        hostCanvas.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                resize(hostCanvas.getWidth(), hostCanvas.getHeight());
            }
        });
    }

    private void initInboundWatcher(int width, int height) {
        this.width = width;
        this.height = height;
        inbound = new XEmbedInboundWatcher(display, windowId);
        inbound.onClientMessage(this::handleInboundMessage);
        inbound.onEmbeddedInfoChanged(this::handleEmbeddedInfoChanged);
    }

    /** Repositions/resizes this socket window and follows the resize into the embedded client, if any. */
    public void setBounds(int x, int y, int width, int height) {
        requireOpen();
        WindowGeometry.moveResize(display, windowId, x, y, width, height);
        WindowGeometry.raise(display, windowId);
        applySize(width, height);
    }

    /**
     * Resizes this socket window in place (local origin stays {@code (0,0)})
     * and follows the resize into the embedded client, if any. {@link
     * #open(Canvas)} already calls this automatically on every resize of its
     * host canvas; call it directly only if driving the resize yourself.
     */
    public void resize(int width, int height) {
        requireOpen();
        WindowGeometry.moveResize(display, windowId, 0, 0, width, height);
        applySize(width, height);
    }

    private void applySize(int width, int height) {
        this.width = width;
        this.height = height;
        followSizeIntoEmbeddedWindow();
    }

    private void followSizeIntoEmbeddedWindow() {
        long id = embeddedWindowId;
        if (id >= 0) {
            WindowGeometry.moveResize(display, id, 0, 0, width, height);
        }
    }

    /**
     * Reissues the just-applied initial resize once more after a short
     * delay. Confirmed by direct observation against a live X server: a
     * plain, XEmbed-unaware AWT client (like {@code ClientDemo}) reacts to
     * being reparented by reasserting its own previous size from its own
     * connection — a one-shot correction that beats our first resize's
     * XGetWindowAttributes readback every time, but never fires a second
     * time. A resize issued after that has already happened sticks. A
     * fully XEmbed-aware client shouldn't contest embedder-driven geometry
     * at all, so this only matters for AWT-unaware embeddees like the demo.
     */
    private void settleInitialSize() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        followSizeIntoEmbeddedWindow();
    }

    /**
     * Starts listening on {@code socketPath} on a background thread and
     * keeps accepting client connections there for as long as this
     * EmbedSocket stays open. Each accepted client is reparented in exactly
     * as a one-shot accept would do it; once it detaches (see {@link
     * #onClientDetached}), the socket goes back to accepting the next one
     * instead of being good for exactly one embed.
     */
    public void listen(Path socketPath) {
        requireOpen();
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
        acceptThread = new Thread(() -> acceptLoop(socketPath), "xembed-socket-accept-loop");
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
                    // close() closes the server channel to unblock this
                    // accept() as its shutdown signal; anything else is a
                    // real failure.
                    if (!listening) {
                        return;
                    }
                    throw new UncheckedIOException(e);
                }
                try {
                    try (accepted) {
                        embedFromHandshake(accepted);
                    }
                } catch (RuntimeException | IOException e) {
                    // A failed/aborted handshake must not take the accept
                    // loop down; the socket keeps listening for the next
                    // client.
                    e.printStackTrace();
                    continue;
                }
                onClientEmbedded.run();
                awaitDetach();
            }
        } finally {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void embedFromHandshake(SocketChannel accepted) {
        embed(PidHandshake.receive(accepted));
    }

    /**
     * Embeds a client process whose pid is already known — e.g. one this
     * host spawned itself — without any Unix domain socket rendezvous.
     * {@code clientPid}'s single top-level window (see {@link
     * #expectClientWindowClass} if it owns more than one) must already carry
     * {@code _XEMBED_INFO}, the same precondition {@link #listen}'s
     * socket-based accept loop relies on (see {@code
     * xembed-client.EmbedClient#announce}, which sets that up without
     * dialing a host socket either).
     */
    public void embed(long clientPid) {
        requireOpen();
        long clientWindowId = resolveClientWindow(clientPid);
        WindowRelease.release(display, clientWindowId);
        Reparenting.reparent(display, clientWindowId, windowId, 0, 0);
        embeddedWindowId = clientWindowId;
        followSizeIntoEmbeddedWindow();
        settleInitialSize();
        XEmbedMessages.send(display.raw(), clientWindowId, XEmbedMessage.EMBEDDED_NOTIFY, 0, windowId,
                XEmbedInfo.PROTOCOL_VERSION);
        InputFocus.set(display, clientWindowId);
        sendActivated(owner.isFocused());
        deathWatcher.watch(clientWindowId, this::handleClientDetached);
        inbound.watchEmbeddedInfo(clientWindowId);
    }

    /**
     * Embeds a client window whose id is already known, without relying on
     * the client's own cooperation at all: unlike {@link #embed(long)}, this
     * writes {@code _XEMBED_INFO} on {@code clientWindowId} itself (rather
     * than requiring the client to have published it) and, since a
     * toolkit-opaque client (e.g. one whose native connection this process
     * can't read events on) can't be trusted to react to {@code
     * EMBEDDED_NOTIFY} or anything else sent its way, confirms the reparent
     * actually took effect by polling {@link WindowTree#parentOf} up to
     * {@code maxAttempts} times, {@code pollInterval} apart, instead of
     * trusting it synchronously. Throws {@link IllegalStateException} if the
     * reparent is never confirmed within that budget.
     *
     * <p>{@code EMBEDDED_NOTIFY} is still sent on a best-effort basis
     * afterward, in case the client's toolkit does read XEmbed
     * ClientMessages despite not being relied on to.
     */
    public void embedOpaque(long clientWindowId, Duration pollInterval, int maxAttempts) {
        requireOpen();
        XEmbedInfoProperty.write(display.raw(), clientWindowId,
                new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        WindowRelease.release(display, clientWindowId);
        Reparenting.reparent(display, clientWindowId, windowId, 0, 0);
        embeddedWindowId = clientWindowId;
        followSizeIntoEmbeddedWindow();
        settleInitialSize();
        waitForReparentConfirmed(clientWindowId, pollInterval, maxAttempts);
        XEmbedMessages.send(display.raw(), clientWindowId, XEmbedMessage.EMBEDDED_NOTIFY, 0, windowId,
                XEmbedInfo.PROTOCOL_VERSION);
        InputFocus.set(display, clientWindowId);
        sendActivated(owner.isFocused());
        deathWatcher.watch(clientWindowId, this::handleClientDetached);
    }

    private void waitForReparentConfirmed(long clientWindowId, Duration pollInterval, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (WindowTree.parentOf(display, clientWindowId) == windowId) {
                return;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new IllegalStateException(
                "Client window " + clientWindowId + " was never confirmed reparented into this socket");
    }

    /** Blocks the accept loop until the currently embedded client detaches, or {@link #close()} is called. */
    private void awaitDetach() {
        while (listening && embeddedWindowId >= 0) {
            sleep();
        }
    }

    /**
     * Registers a callback invoked each time a client finishes being
     * embedded — the initial one, and again after any later re-embed
     * following a previous detach. Runs on the accept loop's own background
     * thread.
     */
    public void onClientEmbedded(Runnable callback) {
        onClientEmbedded = callback;
    }

    /**
     * Registers a callback invoked when the currently embedded window's
     * process exits or crashes. Runs on {@link WindowDeathWatcher}'s own
     * background thread — marshal to the EDT yourself if you touch Swing.
     */
    public void onClientDetached(Runnable callback) {
        onClientDetached = callback;
    }

    /**
     * Voluntarily releases the currently embedded client: reparents its
     * window back to the root window at its current on-screen position and
     * maps it as an ordinary top-level window again, so the host can
     * deliberately swap in a different client afterward — as opposed to
     * only ever finding out a client is gone after the fact via {@link
     * #onClientDetached}, which does not fire for this (the caller already
     * knows). The accept loop goes back to accepting a new client
     * immediately. No-op if nothing is currently embedded.
     *
     * <p>The client's own {@code WindowReparentWatcher}-based host-death
     * detection fires for this exactly as it would for a real detach —
     * appropriate, since as far as the client is concerned it genuinely is
     * no longer embedded.
     */
    public void detachClient() {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        int[] rootPosition = WindowGeometry.rootPosition(display, id);
        deathWatcher.unwatch(id);
        inbound.stopWatchingEmbeddedInfo();
        Reparenting.release(display, id, display.defaultRootWindow().longValue(), rootPosition[0], rootPosition[1]);
        embeddedWindowId = -1;
    }

    /**
     * Registers a callback invoked when the embedded client's tab chain is
     * exhausted going forward (XEMBED_FOCUS_NEXT) and it hands focus back.
     * Runs on {@link XEmbedInboundWatcher}'s own background thread.
     */
    public void onFocusNext(Runnable callback) {
        onFocusNext = callback;
    }

    /** Same as {@link #onFocusNext}, but for the tab chain exhausted going backward (XEMBED_FOCUS_PREV). */
    public void onFocusPrev(Runnable callback) {
        onFocusPrev = callback;
    }

    /**
     * Disambiguates which of a client process's top-level windows gets
     * embedded, by matching {@code WM_CLASS}'s class component (the same
     * string {@code xprop WM_CLASS} prints as the second, quoted value).
     * Only needed when a connecting client can own more than one top-level
     * window at once; a single-window client resolves unambiguously without
     * this. Applies to every client accepted from here on, including
     * re-embeds after a detach.
     */
    public void expectClientWindowClass(String wmClass) {
        expectedClientWmClass = wmClass;
    }

    /**
     * Overrides how long a connecting client is given to publish its
     * top-level window before its handshake attempt is abandoned. Defaults
     * to 5 seconds. Applies to every client accepted from here on.
     */
    public void setWindowLookupTimeout(Duration timeout) {
        windowLookupTimeout = timeout;
    }

    /** Tells the embedded client it's shadowed by (or no longer shadowed by) a modal dialog. */
    public void setModal(boolean modal) {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        XEmbedMessages.send(display.raw(), id, modal ? XEmbedMessage.MODALITY_ON : XEmbedMessage.MODALITY_OFF, 0, 0,
                0);
    }

    private void handleInboundMessage(XEmbedMessage message, long detail) {
        switch (message) {
            case REQUEST_FOCUS -> grantFocus();
            case FOCUS_NEXT -> onFocusNext.run();
            case FOCUS_PREV -> onFocusPrev.run();
            default -> {
                // REGISTER_ACCELERATOR/UNREGISTER_ACCELERATOR/ACTIVATE_ACCELERATOR:
                // not handled yet, no accelerator registry exists on the
                // embedder side.
            }
        }
    }

    private void grantFocus() {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        InputFocus.set(display, id);
        XEmbedMessages.send(display.raw(), id, XEmbedMessage.FOCUS_IN, XEmbedFocus.CURRENT, 0, 0);
    }

    private void handleEmbeddedInfoChanged(long clientWindowId) {
        XEmbedInfoProperty.read(display.raw(), clientWindowId)
                .ifPresent(info -> WindowGeometry.setMapped(display, clientWindowId, info.mapped()));
    }

    private void handleClientDetached(long detachedWindowId) {
        embeddedWindowId = -1;
        inbound.stopWatchingEmbeddedInfo();
        onClientDetached.run();
    }

    private void sendActivated(boolean active) {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        Display raw = display.raw();
        if (active) {
            XEmbedMessages.send(raw, id, XEmbedMessage.FOCUS_IN, XEmbedFocus.CURRENT, 0, 0);
            XEmbedMessages.send(raw, id, XEmbedMessage.WINDOW_ACTIVATE, 0, 0, 0);
        } else {
            XEmbedMessages.send(raw, id, XEmbedMessage.FOCUS_OUT, 0, 0, 0);
            XEmbedMessages.send(raw, id, XEmbedMessage.WINDOW_DEACTIVATE, 0, 0, 0);
        }
    }

    private long resolveClientWindow(long clientPid) {
        List<Long> candidates = pollUntil(
                () -> WindowFinder.findTopLevelWindowsByPid(display, clientPid),
                list -> !list.isEmpty(),
                "Client process " + clientPid + " never published a top-level window");
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        String wmClass = expectedClientWmClass;
        if (wmClass == null) {
            throw new IllegalStateException("Client process " + clientPid + " has " + candidates.size()
                    + " top-level windows; call expectClientWindowClass(...) to disambiguate");
        }
        List<Long> matches = candidates.stream()
                .filter(id -> WindowFinder.readWmClass(display, id).map(wmClass::equals).orElse(false))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Client process " + clientPid + " has " + candidates.size()
                    + " top-level windows and " + matches.size() + " match WM_CLASS \"" + wmClass
                    + "\" (need exactly 1)");
        }
        return matches.get(0);
    }

    private void requireOpen() {
        if (windowId < 0) {
            throw new IllegalStateException("open() must be called first");
        }
    }

    private <T> T pollUntil(Supplier<T> probe, Predicate<T> done, String timeoutMessage) {
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        T value;
        do {
            value = probe.get();
            if (done.test(value)) {
                return value;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(timeoutMessage);
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
        listening = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (inbound != null) {
            inbound.close();
        }
        deathWatcher.close();
        if (windowId >= 0) {
            RawWindow.destroy(display, windowId);
        }
        display.close();
    }
}
