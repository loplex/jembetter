package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.BackgroundThread;
import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.ModalityListener;
import cz.loplex.jembetter.common.SizeListener;
import cz.loplex.jembetter.common.ipc.ControlMessage;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32ConfigureWatcher;
import cz.loplex.jembetter.core.win32.Win32FocusWatcher;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32ReparentWatcher;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * {@link EmbedClient}'s Win32 implementation — the advanced-API counterpart
 * to {@link EmbedClientX11}, matching the {@link EmbedPlug}/{@link
 * EmbedPlugWin32} split on the same side. Resolves and watches this
 * process's own top-level window
 * ({@link #announce}/{@link #offer}, mirroring {@code
 * EmbedPlugWin32#announce} — {@link EmbedPlugWin32} stays usable
 * independently, this class does not delegate to it), exposes {@link
 * #onEmbedded}/{@link #onHostDetached}/{@link #onFocusChanged}/{@link
 * #onResized}/{@link #requestFocus()}, and connects to {@code
 * jembetter-host}'s {@code EmbedSocketWin32#setModal(boolean)} the same way
 * it always has: dial the host's rendezvous socket exactly like {@code
 * EmbedPlugWin32#announce(Path, String)} does (same {@link PidHandshake}),
 * but keep the channel open afterward instead of closing it. A background
 * thread reads {@link ControlMessage} frames off that channel for the life of
 * the embed and dispatches {@link ControlMessage.Type#MODALITY} ones to
 * {@link #onModalityChanged}; {@link #requestFocus()} writes a {@link
 * ControlMessage.Type#FOCUS_REQUEST} frame the other direction on the same
 * channel, read back by {@code EmbedSocketWin32}'s own per-client
 * control-channel reader.
 *
 * <p>Does not (yet) plug into {@code EmbedPlug}'s narrow facade — {@code
 * EmbedPlugWin32#announce(Path, String)} still closes its handshake channel
 * immediately after sending the pid, unaffected by this class's existence.
 */
public final class EmbedClientWin32 implements EmbedClient {

    private static final Logger LOG = LoggerFactory.getLogger(EmbedClientWin32.class);

    private static final long POLL_SLEEP_MILLIS = 50;

    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);

    private final Win32ReparentWatcher reparentWatcher;
    private final Win32FocusWatcher focusWatcher;
    private final Win32ConfigureWatcher configureWatcher;
    private long windowId = -1;
    private volatile boolean closedCleanly = true;

    public EmbedClientWin32() {
        // Built here rather than in field initializers so that a failure
        // part-way through can be undone: each of these starts a thread, and
        // if the second or third throws, the ones already running are
        // unreachable and nothing can ever close them, because the caller
        // never gets an object to close.
        Win32ReparentWatcher openedReparentWatcher = null;
        Win32FocusWatcher openedFocusWatcher = null;
        Win32ConfigureWatcher openedConfigureWatcher = null;
        try {
            openedReparentWatcher = new Win32ReparentWatcher();
            openedFocusWatcher = new Win32FocusWatcher();
            openedConfigureWatcher = new Win32ConfigureWatcher();
        } catch (RuntimeException | Error e) {
            closeQuietly(openedConfigureWatcher);
            closeQuietly(openedFocusWatcher);
            closeQuietly(openedReparentWatcher);
            throw e;
        }
        this.reparentWatcher = openedReparentWatcher;
        this.focusWatcher = openedFocusWatcher;
        this.configureWatcher = openedConfigureWatcher;
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception e) {
            // Best-effort cleanup of something already headed nowhere useful.
        }
    }

    private volatile long embedderHwnd = -1;
    private volatile boolean awaitingEmbed = false;
    private volatile SocketChannel controlChannel;
    private volatile Thread readerThread;
    private volatile ModalityListener onModalityChanged = modal -> {
    };
    private volatile LongConsumer onEmbedded = embedderId -> {
    };
    private volatile Runnable onHostDetached = () -> {
    };
    private volatile FocusListener onFocusChanged = focused -> {
    };
    private volatile SizeListener onResized = (width, height) -> {
    };

    /**
     * Does everything {@link #announce(String)} does, then dials the host's
     * rendezvous socket exactly like {@link #connect}. For a process with a
     * single top-level window (Win32 has no {@code WM_CLASS} equivalent —
     * see {@link #announce(String)}).
     */
    @Override
    public void offer(Path hostSocketPath) {
        offer(hostSocketPath, null);
    }

    /** Same as {@link #offer(Path)}: {@code wmClass} must be {@code null} — see {@link #announce(String)}. */
    @Override
    public void offer(Path hostSocketPath, String wmClass) {
        Objects.requireNonNull(hostSocketPath, "hostSocketPath");
        announce(wmClass);
        connect(hostSocketPath);
    }

    /** Same as {@link #announce(String)}, for a process with a single top-level window. */
    @Override
    public void announce() {
        announce(null);
    }

    /**
     * Resolves this process's own top-level window and starts watching it
     * for {@link #onEmbedded}/{@link #onHostDetached}/{@link
     * #onFocusChanged}/{@link #onResized} — the Win32 mechanics {@code
     * EmbedPlugWin32#announce(String)} already has, folded into this class
     * instead so a caller wanting the modality channel too doesn't need both
     * classes watching the same window independently. {@code wmClass} must
     * be {@code null}: Win32 has no {@code WM_CLASS} equivalent to
     * disambiguate multiple top-level windows with, so this process must own
     * exactly one.
     */
    @Override
    public void announce(String wmClass) {
        if (wmClass != null) {
            throw new UnsupportedOperationException(
                    "Win32 has no WM_CLASS equivalent to disambiguate by; wmClass must be null "
                            + "(this process must own exactly one top-level window)");
        }
        long pid = ProcessHandle.current().pid();
        windowId = waitForOwnWindow(pid);
        awaitingEmbed = true;
        reparentWatcher.watch(windowId, this::handleParentChanged, this::handleWindowDestroyed);
        focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
        configureWatcher.watch(windowId, (width, height) -> onResized.resized(width, height));
    }

    /**
     * Starts watching this process's own already-known top-level window
     * ({@code windowId}) for {@link #onEmbedded}/{@link #onHostDetached}/
     * {@link #onFocusChanged}/{@link #onResized} — everything {@link
     * #announce} does except resolving the window by pid, for a
     * toolkit-opaque client whose host embeds it via {@code
     * EmbedSocketWin32#embedOpaque} after being handed this handle
     * out-of-band (e.g. on the client's own stdout). Use this when the
     * client process already knows its own native window handle directly —
     * a JavaFX {@code Stage}, say — rather than needing this class to
     * resolve it.
     *
     * <p>Nothing else is skipped relative to {@link #announce}: the X11
     * backend additionally publishes {@code _XEMBED_INFO} there and skips it
     * here, but this backend has no {@code _XEMBED_INFO} to publish in the
     * first place — which is also why {@code embedOpaque} and {@code embed}
     * are the same operation on the host side here (see {@code
     * EmbedHostWin32}). All three watchers poll ({@code GetParent}, {@code
     * GetGUIThreadInfo}, {@code GetClientRect}) rather than reading this
     * window's own {@code WM_SIZE}/{@code WM_SETFOCUS}, so a caller whose
     * window belongs to a foreign toolkit's message loop gets the same
     * callbacks as one whose window is a Swing peer.
     *
     * <p>Bypassing the pid lookup also sidesteps {@code
     * Win32WindowFinder#findApplicationWindowsByPid}, which by design only
     * reports top-level windows: a window already reparented into a host is
     * a {@code WS_CHILD} and no longer appears there, so a caller re-arming
     * a watch on an already-embedded window can only do it through this
     * method.
     */
    @Override
    public void watchOwnWindow(long windowId) {
        this.windowId = windowId;
        awaitingEmbed = true;
        reparentWatcher.watch(windowId, this::handleParentChanged, this::handleWindowDestroyed);
        focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
        configureWatcher.watch(windowId, (width, height) -> onResized.resized(width, height));
    }

    /**
     * Registers a callback invoked once this window has been reparented into
     * an embedder, with the embedder's window handle. Runs on {@link
     * Win32ReparentWatcher}'s own background thread.
     *
     * <p>Only the first reparent following {@link #announce}/{@link
     * #watchOwnWindow} counts, the same filter {@link
     * EmbedClientX11#onEmbedded} applies: a desktop shell reparents an
     * ordinary top-level window into a frame of its own, and without this
     * that would be indistinguishable from a host embedding it.
     *
     * <p><strong>How often this fires depends on how the client was set
     * up.</strong> The filter above is the reason, and there are two ways out
     * of it:
     *
     * <ul>
     *   <li>{@link #offer(Path)} against a host's {@code
     *       EmbedSocketWin32#listen} socket opens a control channel, and the
     *       host sends a {@link ControlMessage.Type#EMBEDDED} frame on it for
     *       every embed. That frame says which reparent was an embed instead
     *       of leaving this class to guess, so a client embedded this way is
     *       told about a re-embed after {@code detachClient()} as well as
     *       about the first one.</li>
     *   <li>{@link #announce()} and {@link #watchOwnWindow(long)} open no
     *       channel, so they have only the reparent to go on and this fires
     *       <em>once</em>: the first embed after the call. To hear about a
     *       later one, call {@link #watchOwnWindow(long)} again with the same
     *       window id — it re-arms the filter, and re-watching a window
     *       already watched replaces its callbacks rather than adding a
     *       second set. On this backend that is also the only way back in,
     *       since an embedded window is a {@code WS_CHILD} and {@link
     *       #announce()}'s pid lookup no longer finds it.</li>
     * </ul>
     */
    @Override
    public void onEmbedded(LongConsumer callback) {
        onEmbedded = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Registers a callback invoked when this window is released back to the
     * desktop after having been embedded, or when the embedding host's
     * process destroys it outright (Win32 has no save-set to survive that —
     * see {@code Win32ReparentWatcher}'s Javadoc). Runs on {@link
     * Win32ReparentWatcher}'s own background thread.
     */
    @Override
    public void onHostDetached(Runnable callback) {
        onHostDetached = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Registers a callback invoked whenever this window gains or loses Win32
     * input focus — see {@link Win32FocusWatcher}'s Javadoc for the
     * mechanism. Runs on {@link Win32FocusWatcher}'s own background thread.
     */
    @Override
    public void onFocusChanged(FocusListener callback) {
        onFocusChanged = Objects.requireNonNull(callback, "callback");
    }

    /**
     * Registers a callback invoked whenever this window's own size changes —
     * see {@link Win32ConfigureWatcher}'s Javadoc for the mechanism. Runs on
     * {@link Win32ConfigureWatcher}'s own background thread.
     *
     * <p>Expect a call for the embed itself, before any the host makes
     * afterwards: {@code SetParent} here comes with a style change that drops
     * the window's caption and border, which moves the client area this
     * watcher reports on. See {@link EmbedClient#onResized} for why that means
     * acting on the latest values rather than on the first call.
     */
    @Override
    public void onResized(SizeListener callback) {
        onResized = Objects.requireNonNull(callback, "callback");
    }

    /** The embedder's window handle last reported to {@link #onEmbedded}, or -1 if not currently embedded. */
    @Override
    public long embedderWindowId() {
        return embedderHwnd;
    }

    /** Parity shim — see {@link EmbedClient#setWindowLookupTimeout}. */
    @Override
    public void setWindowLookupTimeout(Duration timeout) {
        windowLookupTimeout = Objects.requireNonNull(timeout, "timeout");
    }

    /**
     * Asks the host to give this window input focus, by writing a {@link
     * ControlMessage.Type#FOCUS_REQUEST} frame to the control channel {@link
     * #connect} opened — read back by {@code EmbedSocketWin32}'s own
     * per-client reader, the client-to-host counterpart of {@link
     * #onModalityChanged}'s host-to-client direction on the same channel.
     * No-op if not currently connected, or best-effort if the host has
     * already closed its end (same "no receiver required" framing as {@code
     * EmbedSocketWin32#setModal}).
     */
    @Override
    public void requestFocus() {
        SocketChannel channel = controlChannel;
        if (channel == null) {
            return;
        }
        try {
            ControlMessage.focusRequest().writeTo(channel);
        } catch (IOException e) {
            // Best-effort, no-receiver-required send - see this method's own Javadoc.
        }
    }

    /**
     * The watched window no longer exists. On this backend that is how a host
     * dying is normally seen — destroying a parent HWND destroys its children
     * outright, with no X11-style save-set to leave the client's window alive
     * — so it is reported as the host detaching, which is what {@code
     * onHostDetached} documents itself to cover.
     *
     * <p>Handled separately from {@link #handleParentChanged} because the
     * parent-based path cannot see this case at all when the embed and the
     * destruction fall inside one poll interval: the parent reads 0 before and
     * after, so nothing appears to change and no embed is ever recorded. Both
     * paths clear the state they check, so whichever runs first is the only
     * one that reports.
     */
    private void handleWindowDestroyed() {
        if (embedderHwnd >= 0) {
            embedderHwnd = -1;
            awaitingEmbed = false;
            onHostDetached.run();
        } else if (awaitingEmbed) {
            awaitingEmbed = false;
            onHostDetached.run();
        }
    }

    /**
     * The host has said, on the control channel, that it embedded this
     * window — {@link ControlMessage.Type#EMBEDDED}.
     *
     * <p>This is the only signal on this backend that identifies a reparent
     * as an embed rather than merely reporting one. {@link
     * #handleParentChanged} has to guess, and guesses by accepting the first
     * non-zero parent after {@link #announce}/{@link #watchOwnWindow}; that
     * filter exists because a desktop shell reparents an ordinary top-level
     * window into a frame of its own, but it also means every reparent after
     * the first is discarded, a genuine re-embed included.
     *
     * <p>Two orders are possible and both are handled, because the frame is
     * read on this thread while the reparent is seen by the watcher's:
     *
     * <ul>
     *   <li>The reparent has already landed — read the parent directly and
     *       report it, whether or not the watcher filtered it.</li>
     *   <li>The reparent has not landed yet — re-arm the gate so the watcher
     *       reports it when it does.</li>
     * </ul>
     *
     * <p>Nothing is reported for a parent already known, so a host that sends
     * this for an embed the watcher had already reported does not produce a
     * second {@code onEmbedded} for the same embedder.
     */
    private void handleEmbeddedFrame() {
        if (windowId < 0) {
            return;
        }
        long parent = Win32Reparent.parentOf(windowId);
        if (parent == 0) {
            // The reparent is still to come; let the watcher report it.
            awaitingEmbed = true;
            return;
        }
        if (parent != embedderHwnd) {
            awaitingEmbed = false;
            embedderHwnd = parent;
            // Resync the watcher's idea of the parent before reporting.
            // Win32ReparentWatcher is poll-based, and this frame can arrive
            // before its next poll - so without this it would still hold the
            // pre-embed parent, see no transition when the host later
            // releases the client, and never report the detach. watch() takes
            // a fresh reading of the current parent, and re-watching a window
            // already watched replaces its entry rather than adding one.
            reparentWatcher.watch(windowId, this::handleParentChanged, this::handleWindowDestroyed);
            onEmbedded.accept(parent);
        }
    }

    private void handleParentChanged(long newParent) {
        if (newParent == 0) {
            if (embedderHwnd >= 0) {
                embedderHwnd = -1;
                onHostDetached.run();
            }
            // else: not embedded yet - this window's own parent is 0 until a
            // host calls SetParent on it, nothing to report.
        } else if (awaitingEmbed) {
            awaitingEmbed = false;
            embedderHwnd = newParent;
            onEmbedded.accept(newParent);
        }
        // else: some other non-zero parent while not expecting an embed - the
        // desktop shell taking this window into a frame of its own, say - not
        // an embed, ignore. Mirrors EmbedClientX11#handleParentChanged.
    }

    private long waitForOwnWindow(long pid) {
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        List<Long> ownWindows;
        do {
            ownWindows = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (ownWindows.size() == 1) {
                return ownWindows.getFirst();
            }
            if (ownWindows.size() > 1) {
                throw new IllegalStateException("This process has " + ownWindows.size()
                        + " application windows; Win32 has no WM_CLASS-equivalent way to disambiguate them");
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Could not resolve this process's own top-level window");
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Connects to the host's rendezvous socket at {@code hostSocketPath} and
     * sends this process's own pid — the same handshake {@code
     * EmbedPlugWin32#announce(Path, String)} performs — but keeps the
     * channel open afterward and starts a background thread reading {@link
     * ControlMessage} frames off it, dispatching {@link
     * ControlMessage.Type#MODALITY} ones to {@link #onModalityChanged}. Only
     * meaningful against a host that keeps its own
     * end open too, i.e. one embedding this client via {@code
     * EmbedSocketWin32#listen(Path)} — a plain {@code embed(long)}/{@code
     * embed(Path)}/{@code embedOpaque(long)} host closes its side of the
     * handshake channel right away, so nothing would ever arrive here either
     * way.
     */
    public void connect(Path hostSocketPath) {
        Objects.requireNonNull(hostSocketPath, "hostSocketPath");
        if (controlChannel != null) {
            throw new IllegalStateException("Already connected");
        }
        SocketChannel channel;
        try {
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(address);
            PidHandshake.send(channel, ProcessHandle.current().pid());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        controlChannel = channel;
        readerThread = new Thread(this::readLoop, "jembetter-win32-embed-client-control-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Registers a callback invoked each time the host signals this client is
     * shadowed by (or no longer shadowed by) a modal dialog, via {@code
     * EmbedSocketWin32#setModal(boolean)}. Runs on this class's own
     * background reader thread.
     */
    @Override
    public void onModalityChanged(ModalityListener callback) {
        onModalityChanged = Objects.requireNonNull(callback, "callback");
    }

    private void readLoop() {
        SocketChannel channel = controlChannel;
        try {
            while (true) {
                ControlMessage message;
                try {
                    message = ControlMessage.readFrom(channel);
                } catch (IllegalArgumentException e) {
                    // Skipped rather than fatal, for the reason EmbedClientX11's
                    // own reader records: the frame is a fixed two bytes and has
                    // already been consumed, so a type this build does not know
                    // cannot desynchronise the stream - while letting it out
                    // would end this thread and lose every later frame silently.
                    LOG.debug("Ignoring an unrecognised control frame", e);
                    continue;
                }
                if (message == null) {
                    return;
                }
                switch (message.type()) {
                    case MODALITY -> onModalityChanged.modalityChanged(message.flag());
                    case EMBEDDED -> handleEmbeddedFrame();
                    default -> {
                        // FOCUS_REQUEST is client->host and never arrives here.
                        // A known-but-unhandled type lands here; an unknown one
                        // is skipped above, before it can reach a switch.
                    }
                }
            }
        } catch (IOException e) {
            // close() closes the channel to unblock this read() as its
            // shutdown signal; the host disappearing does the same via EOF
            // above, not this branch. Either way, nothing left to read.
        }
    }

    @Override
    public void close() {
        SocketChannel channel = controlChannel;
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // Best-effort cleanup of a channel already headed nowhere useful.
            }
        }
        boolean readerStopped = BackgroundThread.awaitStopped(readerThread, LOG);
        reparentWatcher.close();
        focusWatcher.close();
        configureWatcher.close();
        closedCleanly = readerStopped && reparentWatcher.stoppedCleanly()
                && focusWatcher.stoppedCleanly() && configureWatcher.stoppedCleanly();
    }

    @Override
    public boolean closedCleanly() {
        return closedCleanly;
    }

}
