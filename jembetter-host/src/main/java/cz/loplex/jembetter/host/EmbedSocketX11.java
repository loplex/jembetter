package cz.loplex.jembetter.host;

import com.sun.jna.platform.unix.X11.Display;
import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.ControlMessage;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.x11.InputFocus;
import cz.loplex.jembetter.core.x11.RawWindow;
import cz.loplex.jembetter.core.x11.Reparenting;
import cz.loplex.jembetter.core.x11.WindowDeathWatcher;
import cz.loplex.jembetter.core.x11.WindowFinder;
import cz.loplex.jembetter.core.x11.WindowGeometry;
import cz.loplex.jembetter.core.x11.WindowConfigureWatcher;
import cz.loplex.jembetter.core.x11.WindowRelease;
import cz.loplex.jembetter.core.x11.WindowTree;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.xembed.XEmbedFocus;
import cz.loplex.jembetter.core.xembed.XEmbedInboundWatcher;
import cz.loplex.jembetter.core.xembed.XEmbedInfo;
import cz.loplex.jembetter.core.xembed.XEmbedInfoProperty;
import cz.loplex.jembetter.core.xembed.XEmbedMessage;
import cz.loplex.jembetter.core.xembed.XEmbedMessages;

import java.awt.Canvas;
import java.awt.Frame;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
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
import java.util.Optional;
import java.util.function.IntFunction;
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
 * into JDK-internal AWT classes. Owning the window via {@code jembetter-core-x11}'s
 * own {@link X11Display} instead means those events can be read directly.
 * The tradeoff: this window is not part of AWT's own window tree, so with
 * {@link #open(int, int, int, int)} the host is responsible for keeping it
 * positioned over wherever it should appear (e.g. a placeholder Swing
 * component's {@code getLocationOnScreen()} plus a resize/move listener
 * calling {@link #setBounds}) rather than laying it out with the rest of
 * its UI — {@link #open(Canvas)} avoids that entirely by making this
 * window a genuine X11 child of the placeholder itself.
 */
public final class EmbedSocketX11 implements EmbedSocket {

    private static final Duration OPAQUE_POLL_INTERVAL = Duration.ofMillis(20);
    private static final int OPAQUE_MAX_ATTEMPTS = 100;

    private final Frame owner;
    private final X11Display display = X11Display.open(null);
    private final WindowDeathWatcher deathWatcher = new WindowDeathWatcher();
    private final WindowConfigureWatcher configureWatcher = new WindowConfigureWatcher();
    private XEmbedInboundWatcher inbound;
    private long windowId = -1;
    private volatile int width = -1;
    private volatile int height = -1;
    private volatile long embeddedWindowId = -1;
    private volatile boolean listening = false;
    private ServerSocketChannel server;
    private Thread acceptThread;
    /**
     * The current {@link #listen}-embedded client's control channel — the
     * same {@link SocketChannel} the accept loop took the pid handshake on,
     * kept open for the life of that embed so {@link #setModal}/{@link
     * #sendActivated} can write {@link ControlMessage} frames to it. Null
     * whenever nothing is embedded via {@link #listen} (a plain {@link
     * #embed(long)}/{@link #embed(Path)}/{@link #embedOpaque} never opens
     * one). Host&rarr;client only: the client asks for focus with a real
     * {@code XEMBED_REQUEST_FOCUS} {@code ClientMessage} the embedder
     * connection reads directly (see {@link #handleInboundMessage}), so this
     * needs no reader side the way {@code EmbedSocketWin32}'s does.
     */
    private volatile SocketChannel controlChannel;
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
    private volatile boolean closed = false;

    private final WindowFocusListener ownerFocusListener = new WindowAdapter() {
        @Override
        public void windowGainedFocus(WindowEvent event) {
            sendActivated(true);
        }

        @Override
        public void windowLostFocus(WindowEvent event) {
            sendActivated(false);
        }
    };

    public EmbedSocketX11(Frame owner) {
        this.owner = owner;
        owner.addWindowFocusListener(ownerFocusListener);
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
     * above it, unlike with {@link #open(int, int, int, int)}. Also attaches,
     * to {@code hostCanvas}:
     *
     * <ul>
     *   <li>a {@link java.awt.event.ComponentListener} that calls {@link
     *       #resize} automatically on every resize, so the caller doesn't
     *       have to track it manually the way {@link #setBounds} requires;
     *   <li>a {@link java.awt.event.HierarchyListener} that calls {@link
     *       #close()} once {@code hostCanvas} becomes non-displayable (e.g.
     *       because it, or its containing window, was disposed), guarding
     *       against a double-close if the caller also calls {@link #close()}
     *       itself — so an app that forgets to release this socket
     *       explicitly no longer leaks its own X11 connection and the two
     *       background threads it drives for the rest of the process's
     *       lifetime. (The child X11 window itself was never really the
     *       leak: disposing {@code hostCanvas} already destroys its native
     *       peer, and this socket's child window — along with, transitively,
     *       whatever client is still embedded in it — goes with it as
     *       ordinary X11 subtree destruction, before this listener even
     *       runs. {@link #close()} still calls {@link #detachClient()}
     *       first for exactly this reason: a client still embedded when
     *       {@code close()} runs some other way — the canvas hasn't been
     *       disposed, just released explicitly — ends up released rather
     *       than destroyed, since X11's save-set only rescues a
     *       reparented-in window like that by reparenting it back to root
     *       when the owning connection itself closes, not when that
     *       connection destroys one of its own windows while staying open.)
     * </ul>
     *
     * <p>{@code hostCanvas} must already be displayable (i.e. part of a
     * visible window). Clicking away from the embedded area (e.g. into the
     * host's own menu bar) and back returns input focus to the embedded
     * client automatically: a passive {@code XGrabButton} on the client
     * window (installed on embed, removed on detach — see {@link
     * cz.loplex.jembetter.core.x11.ButtonGrab}) intercepts the resulting
     * {@code ButtonPress} before the client's own toolkit ever sees it,
     * calls {@link #focusClient()}, then replays the press via {@code
     * XAllowEvents(ReplayPointer)} so the client's own interactivity isn't
     * broken. A plain {@code MouseListener} on {@code hostCanvas} cannot do
     * this on its own: the embedded client's window is a real X11 window
     * sized to cover the canvas exactly, and per X11 event propagation
     * rules a button press stops at the first window in the hierarchy that
     * selected for it — virtually every real client selects {@code
     * ButtonPress} on its own window, so the press never bubbles up to an
     * ancestor {@code Canvas} listener (confirmed by live testing against a
     * real X server: such a listener never fires for a genuine click on the
     * embedded area, only for one synthetically dispatched in-process,
     * which is why the grab, not a listener, is what does the interception
     * here).
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
        hostCanvas.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0
                    && !hostCanvas.isDisplayable()) {
                close();
            }
        });
    }

    private void initInboundWatcher(int width, int height) {
        this.width = width;
        this.height = height;
        inbound = new XEmbedInboundWatcher(display, windowId);
        inbound.onClientMessage(this::handleInboundMessage);
        inbound.onEmbeddedInfoChanged(this::handleEmbeddedInfoChanged);
        inbound.onButtonPress(this::focusClient);
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
     * Starts watching the embedded client window for {@code ConfigureNotify}
     * and reissuing our own desired size whenever one reports something
     * else. Confirmed by direct observation against a live X server: a
     * plain, XEmbed-unaware AWT client (like {@code ClientDemo}) reacts to
     * being reparented by reasserting its own previous size from its own
     * connection, once, right after the reparent — a fully XEmbed-aware
     * client shouldn't contest embedder-driven geometry at all, so this only
     * matters for AWT-unaware embeddees like the demo. Reacting to the
     * actual {@code ConfigureNotify} this produces, rather than a fixed
     * delay guessed to outlast it, also corrects any later self-resize the
     * same way, not just the reparent-time one.
     *
     * <p><strong>Must be called before {@link Reparenting#reparent}, not
     * after.</strong> {@code XSelectInput} is not retroactive: a {@code
     * ConfigureNotify} generated before this connection has selected {@code
     * StructureNotifyMask} on {@code clientWindowId} is gone, not queued —
     * calling this after the reparent used to race the client's own
     * reflexive self-resize above, and losing that race (confirmed by
     * instrumenting a failing run: the client window really was left at its
     * own 30x30 pre-embed size, not the embedder's desired size) is what
     * made {@code EmbedSocketTest.aRealClickOnTheEmbeddedAreaReturnsInputFocusToTheClient}
     * flaky — a real click could land past the edge of the still-shrunk
     * client window and never reach the click-to-focus {@link
     * cz.loplex.jembetter.core.x11.ButtonGrab} at all. Calling this first
     * closes the race structurally rather than outrunning it with a timer:
     * 40/40 repeated runs passed once this was reordered, versus a
     * reproducible failure with a fixed post-reparent delay in place of a
     * genuine subscribe-before-reparent ordering.
     *
     * <p>{@link WindowGeometry#moveResize} raised in response also
     * generates its own {@code ConfigureNotify}, so this watcher sees every
     * resize it issues too — harmless, since {@link #reassertSizeIfChanged}
     * only re-issues on a mismatch and a self-issued resize always already
     * matches {@link #width}/{@link #height} by the time it's reported back.
     */
    private void watchForSizeContest(long clientWindowId) {
        configureWatcher.watch(clientWindowId, this::reassertSizeIfChanged);
    }

    private void reassertSizeIfChanged(int reportedWidth, int reportedHeight) {
        if (reportedWidth != width || reportedHeight != height) {
            followSizeIntoEmbeddedWindow();
        }
    }

    /**
     * Starts listening on {@code socketPath} on a background thread and
     * keeps accepting client connections there for as long as this
     * EmbedSocket stays open. Each accepted client is reparented in exactly
     * as a one-shot accept would do it; once it detaches (see {@link
     * #onClientDetached}), the socket goes back to accepting the next one
     * instead of being good for exactly one embed.
     */
    @Override
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
                long clientPid;
                try {
                    clientPid = PidHandshake.receive(accepted);
                } catch (RuntimeException e) {
                    // A failed/aborted handshake must not take the accept
                    // loop down; the socket keeps listening for the next
                    // client.
                    closeQuietly(accepted);
                    e.printStackTrace();
                    continue;
                }
                // Kept open, unlike embed(Path)'s one-shot handshake: this is
                // the client's control channel for the rest of its embed, for
                // setModal(boolean)/sendActivated(boolean) to write
                // ControlMessage frames into. Set before embed() so the
                // WINDOW_ACTIVATE frame embed() itself sends (owner.isFocused()
                // at embed time) reaches a listen client too. Closed once this
                // client detaches, below.
                controlChannel = accepted;
                try {
                    embed(clientPid);
                } catch (RuntimeException e) {
                    closeQuietly(accepted);
                    controlChannel = null;
                    e.printStackTrace();
                    continue;
                }
                onClientEmbedded.run();
                awaitDetach();
                closeQuietly(controlChannel);
                controlChannel = null;
            }
        } finally {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /**
     * Embeds a client process whose pid is already known — e.g. one this
     * host spawned itself — without any Unix domain socket rendezvous.
     * {@code clientPid}'s single top-level window (see {@link
     * #expectClientWindowClass} if it owns more than one) must already carry
     * {@code _XEMBED_INFO}, the same precondition {@link #listen}'s
     * socket-based accept loop relies on (see {@code
     * jembetter-client.EmbedClient#announce}, which sets that up without
     * dialing a host socket either).
     */
    @Override
    public void embed(long clientPid) {
        requireOpen();
        long clientWindowId = resolveClientWindow(clientPid);
        // watchForSizeContest before the reparent, not after - see its
        // Javadoc for why the ordering itself is the fix.
        watchForSizeContest(clientWindowId);
        WindowRelease.release(display, clientWindowId);
        Reparenting.reparent(display, clientWindowId, windowId, 0, 0);
        embeddedWindowId = clientWindowId;
        followSizeIntoEmbeddedWindow();
        synchronized (X11Display.GLOBAL_LOCK) {
            XEmbedMessages.send(display.raw(), clientWindowId, XEmbedMessage.EMBEDDED_NOTIFY, 0, windowId,
                    XEmbedInfo.PROTOCOL_VERSION);
        }
        InputFocus.set(display, clientWindowId);
        sendActivated(owner.isFocused());
        deathWatcher.watch(clientWindowId, this::handleClientDetached);
        inbound.watchEmbeddedInfo(clientWindowId);
        inbound.watchButtonPress(clientWindowId);
    }

    /**
     * Starts a Unix domain socket at {@code rendezvousSocket}, accepts
     * exactly one client connection there, embeds it via {@link
     * #embed(long)}, and returns — unlike {@link #listen}, this does not keep
     * accepting further clients afterward, and the handshake channel is
     * closed as soon as the pid is read rather than kept open as a control
     * channel.
     */
    @Override
    public void embed(Path rendezvousSocket) {
        requireOpen();
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

    /**
     * Embeds a client window whose id is already known, using a fixed,
     * generous poll budget — delegates to {@link #embedOpaque(long, Duration,
     * int)}. Call that overload directly if the budget needs tuning.
     */
    @Override
    public void embedOpaque(long clientWindowId) {
        embedOpaque(clientWindowId, OPAQUE_POLL_INTERVAL, OPAQUE_MAX_ATTEMPTS);
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
        synchronized (X11Display.GLOBAL_LOCK) {
            XEmbedInfoProperty.write(display.raw(), clientWindowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }
        // watchForSizeContest before the reparent, not after - see its
        // Javadoc for why the ordering itself is the fix.
        watchForSizeContest(clientWindowId);
        WindowRelease.release(display, clientWindowId);
        Reparenting.reparent(display, clientWindowId, windowId, 0, 0);
        embeddedWindowId = clientWindowId;
        followSizeIntoEmbeddedWindow();
        waitForReparentConfirmed(clientWindowId, pollInterval, maxAttempts);
        synchronized (X11Display.GLOBAL_LOCK) {
            XEmbedMessages.send(display.raw(), clientWindowId, XEmbedMessage.EMBEDDED_NOTIFY, 0, windowId,
                    XEmbedInfo.PROTOCOL_VERSION);
        }
        InputFocus.set(display, clientWindowId);
        sendActivated(owner.isFocused());
        deathWatcher.watch(clientWindowId, this::handleClientDetached);
        inbound.watchButtonPress(clientWindowId);
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
    @Override
    public void onClientEmbedded(Runnable callback) {
        onClientEmbedded = callback;
    }

    /**
     * Registers a callback invoked when the currently embedded window's
     * process exits or crashes. Runs on {@link WindowDeathWatcher}'s own
     * background thread — marshal to the EDT yourself if you touch Swing.
     */
    @Override
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
    @Override
    public void detachClient() {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        int[] rootPosition = WindowGeometry.rootPosition(display, id);
        deathWatcher.unwatch(id);
        configureWatcher.unwatch(id);
        inbound.stopWatchingEmbeddedInfo();
        inbound.stopWatchingButtonPress();
        Reparenting.release(display, id, display.defaultRootWindow().longValue(), rootPosition[0], rootPosition[1]);
        embeddedWindowId = -1;
    }

    /**
     * Destroys the currently embedded client's window outright, instead of
     * releasing it back to the caller as a live top-level window the way
     * {@link #detachClient()} does — for a caller that knows the embedded
     * client is a private renderer process never meant to survive
     * independently (e.g. one it spawned purely to embed) and wants that
     * guaranteed regardless of call order. No-op if nothing is currently
     * embedded.
     *
     * <p>Contrast with {@link #detachClient()}: the client is not given a
     * chance to keep running as a top-level window afterward. {@link
     * RawWindow#destroy} is already {@code GLOBAL_LOCK}-safe and {@code
     * BadWindow}-tolerant via the global X11 error handler — see its
     * existing use in {@link #close()} — so a client that has already
     * exited on its own by the time this runs is handled the same as one
     * still alive.
     */
    public void destroyClient() {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        deathWatcher.unwatch(id);
        configureWatcher.unwatch(id);
        inbound.stopWatchingEmbeddedInfo();
        inbound.stopWatchingButtonPress();
        RawWindow.destroy(display, id);
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
    @Override
    public void setWindowLookupTimeout(Duration timeout) {
        windowLookupTimeout = timeout;
    }

    /**
     * Tells the embedded client it's shadowed by (or no longer shadowed by)
     * a modal dialog. Sends the XEmbed {@code XEMBED_MODALITY_ON}/{@code OFF}
     * {@code ClientMessage} as a courtesy to a genuinely XEmbed-aware
     * external toolkit, and — for a client embedded via {@link #listen} —
     * also writes a {@link ControlMessage.Type#MODALITY} frame to that
     * client's control channel, the path a {@code
     * jembetter-client.EmbedClient} actually reads (via its {@code
     * onModalityChanged}), since the {@code ClientMessage} only ever reaches
     * the connection that created the client's window (AWT's own). No-op if
     * nothing is embedded; the control-channel write is skipped (not an
     * error) for a client embedded via {@link #embed(long)}/{@link
     * #embed(Path)}/{@link #embedOpaque}, which never keep a channel open
     * past the handshake.
     */
    @Override
    public void setModal(boolean modal) {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        synchronized (X11Display.GLOBAL_LOCK) {
            XEmbedMessages.send(display.raw(), id, modal ? XEmbedMessage.MODALITY_ON : XEmbedMessage.MODALITY_OFF, 0,
                    0, 0);
        }
        sendControlMessage(ControlMessage.of(ControlMessage.Type.MODALITY, modal));
    }

    /**
     * Best-effort write of {@code message} to the current {@link #listen}
     * client's control channel — a no-op when nothing was embedded via
     * {@link #listen}, and swallowing an {@link IOException} from a peer that
     * has already closed its end, the same "no receiver required" contract
     * {@link #setModal} documents.
     */
    private void sendControlMessage(ControlMessage message) {
        SocketChannel channel = controlChannel;
        if (channel == null) {
            return;
        }
        try {
            message.writeTo(channel);
        } catch (IOException e) {
            // Best-effort, no-receiver-required send - see setModal's Javadoc.
        }
    }

    private void handleInboundMessage(XEmbedMessage message, long detail) {
        switch (message) {
            case REQUEST_FOCUS -> focusClient();
            case FOCUS_NEXT -> onFocusNext.run();
            case FOCUS_PREV -> onFocusPrev.run();
            default -> {
                // REGISTER_ACCELERATOR/UNREGISTER_ACCELERATOR/ACTIVATE_ACCELERATOR:
                // not handled yet, no accelerator registry exists on the
                // embedder side.
            }
        }
    }

    /**
     * Gives the currently embedded client input focus, the same way it
     * would be granted in response to the client's own {@code
     * XEMBED_REQUEST_FOCUS} — for a host that wants to push focus into the
     * embedded window on its own initiative (e.g. the user clicked the
     * canvas placeholder) instead of waiting for the client to ask for it.
     * No-op if nothing is currently embedded.
     */
    @Override
    public void focusClient() {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        InputFocus.set(display, id);
        synchronized (X11Display.GLOBAL_LOCK) {
            XEmbedMessages.send(display.raw(), id, XEmbedMessage.FOCUS_IN, XEmbedFocus.CURRENT, 0, 0);
        }
    }

    private void handleEmbeddedInfoChanged(long clientWindowId) {
        Optional<XEmbedInfoProperty.Value> info;
        synchronized (X11Display.GLOBAL_LOCK) {
            info = XEmbedInfoProperty.read(display.raw(), clientWindowId);
        }
        info.ifPresent(value -> WindowGeometry.setMapped(display, clientWindowId, value.mapped()));
    }

    private void handleClientDetached(long detachedWindowId) {
        embeddedWindowId = -1;
        configureWatcher.unwatch(detachedWindowId);
        inbound.stopWatchingEmbeddedInfo();
        inbound.stopWatchingButtonPress();
        onClientDetached.run();
    }

    /**
     * Relays the host owner {@link Frame}'s activation state to the embedded
     * client: the XEmbed {@code WINDOW_ACTIVATE}/{@code WINDOW_DEACTIVATE}
     * (plus {@code FOCUS_IN}/{@code FOCUS_OUT}) {@code ClientMessage} for a
     * real XEmbed toolkit, and — for a {@link #listen} client — a {@link
     * ControlMessage.Type#ACTIVATION} frame on its control channel, the path
     * a {@code jembetter-client.EmbedClient} reads via {@code
     * onActivationChanged} (the {@code ClientMessage} reaching only AWT's own
     * connection, not the client's).
     */
    private void sendActivated(boolean active) {
        long id = embeddedWindowId;
        if (id < 0) {
            return;
        }
        Display raw = display.raw();
        synchronized (X11Display.GLOBAL_LOCK) {
            if (active) {
                XEmbedMessages.send(raw, id, XEmbedMessage.FOCUS_IN, XEmbedFocus.CURRENT, 0, 0);
                XEmbedMessages.send(raw, id, XEmbedMessage.WINDOW_ACTIVATE, 0, 0, 0);
            } else {
                XEmbedMessages.send(raw, id, XEmbedMessage.FOCUS_OUT, 0, 0, 0);
                XEmbedMessages.send(raw, id, XEmbedMessage.WINDOW_DEACTIVATE, 0, 0, 0);
            }
        }
        sendControlMessage(ControlMessage.of(ControlMessage.Type.ACTIVATION, active));
    }

    private long resolveClientWindow(long clientPid) {
        String wmClass = expectedClientWmClass;
        if (wmClass != null) {
            // Filter by WM_CLASS from the first poll, rather than waiting
            // for *any* candidate and only then checking its class: a
            // multi-window client's sibling windows can still be unmapped at
            // that first sight, so stopping as soon as exactly one candidate
            // exists (regardless of class) can return the wrong window
            // before the expected one has even appeared.
            return pollForSingleMatch(
                    () -> WindowFinder.findTopLevelWindowsByPidAndClass(display, clientPid, wmClass),
                    "Client process " + clientPid + " never published a top-level window matching WM_CLASS \""
                            + wmClass + "\"",
                    count -> "Client process " + clientPid + " has " + count + " top-level windows matching WM_CLASS \""
                            + wmClass + "\" (need exactly 1)");
        }
        List<Long> candidates = pollUntil(
                () -> WindowFinder.findTopLevelWindowsByPid(display, clientPid),
                list -> !list.isEmpty(),
                "Client process " + clientPid + " never published a top-level window");
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalStateException("Client process " + clientPid + " has " + candidates.size()
                + " top-level windows; call expectClientWindowClass(...) to disambiguate");
    }

    /**
     * Polls {@code probe} until it returns exactly one match, failing fast
     * (before {@code windowLookupTimeout} elapses) if it ever returns more
     * than one — an ambiguous match won't resolve itself by waiting longer —
     * but retrying on zero, since the expected match may simply not have
     * appeared yet.
     */
    private long pollForSingleMatch(Supplier<List<Long>> probe, String timeoutMessage,
            IntFunction<String> ambiguousMessage) {
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        do {
            List<Long> matches = probe.get();
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                throw new IllegalStateException(ambiguousMessage.apply(matches.size()));
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(timeoutMessage);
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

    private static void closeQuietly(SocketChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // Best-effort cleanup of a channel already headed nowhere useful.
        }
    }

    @Override
    public void close() {
        closeImpl(false);
    }

    /**
     * Same as {@link #close()}, but a still-embedded client is destroyed
     * outright via {@link #destroyClient()} instead of gracefully released
     * via {@link #detachClient()} — see {@link #destroyClient()} for when
     * that's the right choice.
     */
    @Override
    public void tryDestroy() {
        closeImpl(true);
    }

    private void closeImpl(boolean destroyClient) {
        if (closed) {
            return;
        }
        closed = true;
        // owner.dispose() posts window (de)activation events onto the AWT
        // EventQueue asynchronously, and in a reuseForks Surefire run that
        // queue's thread outlives any one test method - a stale listener
        // callback firing after display is closed below would call into a
        // freed native Display*, crashing the JVM instead of throwing.
        // Removing it here, before anything else, closes that window.
        owner.removeWindowFocusListener(ownerFocusListener);
        listening = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // Unblocks the accept loop's awaitDetach() path and covers the case
        // where the acceptThread.join() below times out before the loop
        // closes this itself.
        closeQuietly(controlChannel);
        controlChannel = null;
        if (acceptThread != null) {
            try {
                acceptThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // A still-embedded client's window is a genuine X11 child of
        // windowId at this point; save-set rescue only rescues it from
        // XDestroyWindow below by reparenting it back to root if that
        // happens as a side effect of *this connection closing* - an
        // explicit XDestroyWindow on windowId while this connection stays
        // open destroys the whole subtree outright instead, client included.
        // Releasing it the same way a voluntary detachClient() would avoids
        // relying on that distinction and leaves the client exactly as
        // uninvolved as an explicit detach would - unless the caller has
        // opted into destroyClient() instead, e.g. because it knows the
        // client is a private renderer process never meant to survive this.
        if (destroyClient) {
            destroyClient();
        } else {
            detachClient();
        }
        if (inbound != null) {
            inbound.close();
        }
        deathWatcher.close();
        configureWatcher.close();
        if (windowId >= 0) {
            RawWindow.destroy(display, windowId);
        }
        display.close();
    }
}
