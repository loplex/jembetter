package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.FocusListener;
import cz.loplex.jembetter.common.SizeListener;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.x11.WindowConfigureWatcher;
import cz.loplex.jembetter.core.x11.WindowFinder;
import cz.loplex.jembetter.core.x11.WindowFocusWatcher;
import cz.loplex.jembetter.core.x11.WindowReparentWatcher;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.xembed.XEmbedInfo;
import cz.loplex.jembetter.core.xembed.XEmbedInfoProperty;
import cz.loplex.jembetter.core.xembed.XEmbedMessage;
import cz.loplex.jembetter.core.xembed.XEmbedMessages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Makes this process's own already-visible top-level window available to be
 * reparented by a host listening on a Unix domain socket, marks it XEmbed-
 * aware via {@code _XEMBED_INFO}, then watches for the embed itself (see
 * {@link #onEmbedded}) and for the host dying afterward (see
 * {@link #onHostDetached}). {@link #requestFocus()} sends {@code
 * XEMBED_REQUEST_FOCUS} back to the embedder once embedded.
 */
public final class EmbedClient implements AutoCloseable {

    private final X11Display display = X11Display.open(null);
    private final WindowReparentWatcher reparentWatcher = new WindowReparentWatcher();
    private final WindowConfigureWatcher configureWatcher = new WindowConfigureWatcher();
    private final WindowFocusWatcher focusWatcher = new WindowFocusWatcher();
    private long windowId = -1;
    private volatile long embedderWindowId = -1;
    private volatile boolean awaitingEmbed = false;
    private volatile Runnable onHostDetached = () -> {
    };
    private volatile LongConsumer onEmbedded = embedderId -> {
    };
    private volatile SizeListener onResized = (width, height) -> {
    };
    private volatile FocusListener onFocusChanged = focused -> {
    };
    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);

    /**
     * Registers a callback invoked when this window is reparented back to
     * the root window after having been embedded — what the X server does
     * automatically, with no XEmbed message involved, as soon as it notices
     * the host's connection is gone (the save-set mechanism {@link
     * cz.loplex.jembetter.core.x11.Reparenting#reparent} relies on). Runs on
     * {@link WindowReparentWatcher}'s own background thread.
     */
    public void onHostDetached(Runnable callback) {
        onHostDetached = callback;
    }

    /**
     * Registers a callback invoked once this window has actually been
     * reparented into an embedder, with the embedder's window id.
     *
     * <p>This deliberately doesn't come from XEmbed's own {@code
     * EMBEDDED_NOTIFY} ClientMessage: {@code XSendEvent} with a zero event
     * mask (as XEmbed requires for ClientMessages) is only ever delivered to
     * the connection that <em>created</em> the destination window — see
     * {@code cz.loplex.jembetter.host.EmbedSocket}'s Javadoc, which is why the
     * host had to stop being an AWT window in the first place. This
     * process's own top-level window is created by AWT's internal X11
     * connection, not by this class's {@link X11Display}, so the same
     * restriction would make {@code EMBEDDED_NOTIFY} unreadable here without
     * reflecting into JDK-internal AWT classes. The initial
     * {@code ReparentNotify} carries the same information (the window's new
     * parent <em>is</em> the embedder window) and, being a real
     * server-generated event rather than one injected via {@code
     * XSendEvent}, isn't subject to that restriction — it arrives on this
     * class's own connection just as reliably as the "reparented back to
     * root" event {@link #onHostDetached} already relies on.
     *
     * <p>Only the reparent immediately following {@link #offer}'s handshake
     * counts: a window manager reparents any ordinary top-level window into
     * its own decoration frame, including one just released back to the
     * root window by {@code
     * cz.loplex.jembetter.host.EmbedSocket#detachClient()} — without this
     * filter, that second, unrelated reparent would be mistaken for a new
     * embed. Runs on {@link WindowReparentWatcher}'s own background thread.
     */
    public void onEmbedded(LongConsumer callback) {
        onEmbedded = callback;
    }

    /** The embedder's window id last reported to {@link #onEmbedded}, or -1 if not currently embedded. */
    public long embedderWindowId() {
        return embedderWindowId;
    }

    /**
     * Registers a callback invoked whenever this window gains ({@code true})
     * or loses ({@code false}) X input focus — the real, delivered
     * counterpart of XEmbed's host&rarr;client {@code XEMBED_FOCUS_IN}/{@code
     * XEMBED_FOCUS_OUT} ClientMessages, which never reach this class (they
     * are only delivered to the connection that created this process's
     * top-level window — AWT's own, not this class's {@link X11Display} —
     * the same restriction behind {@link #onEmbedded} not coming from {@code
     * EMBEDDED_NOTIFY}). A host that grants the embedded client focus does so
     * with {@code XSetInputFocus} (see {@code EmbedSocket#focusClient}),
     * which generates a real server-side {@code FocusIn} on this window — and
     * a {@code FocusOut} when focus later moves away — that {@link
     * WindowFocusWatcher} reads directly off this class's own connection,
     * just like the {@code ReparentNotify}/{@code ConfigureNotify} the other
     * callbacks rely on.
     *
     * <p>Like {@link #onResized}, this needs no {@link #announce} — {@link
     * #watchOwnWindow} is enough — so a toolkit-opaque client (embedded via
     * {@code EmbedSocket#embedOpaque}) can drive its own focus rendering
     * (caret blink, selection highlight) from it without any handshake. A
     * cooperative AWT/Swing client doesn't need this: its own toolkit reads
     * the same {@code FocusIn}/{@code FocusOut} on AWT's connection and
     * updates itself. Runs on {@link WindowFocusWatcher}'s own background
     * thread.
     */
    public void onFocusChanged(FocusListener callback) {
        onFocusChanged = callback;
        if (windowId >= 0) {
            focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
        }
    }

    /**
     * Registers a callback invoked whenever this window's own size changes,
     * with its new width/height in pixels — in particular, when a host
     * follows its own resize into the embedded window (see {@code
     * EmbedSocket#resize}), the same way it would for a cooperative,
     * XEmbed-aware client. Unlike {@link #onEmbedded}/{@link
     * #onHostDetached}'s {@code ReparentNotify}-based mechanism, this needs
     * no {@link #announce} at all — {@link #watchOwnWindow} is enough — so a
     * toolkit-opaque client (e.g. JavaFX, embedded via {@code
     * EmbedSocket#embedOpaque}) can still learn its own on-screen size
     * without any handshake, and drive its own scene-graph relayout from it
     * instead of a side channel of the caller's own (e.g. a stdin protocol)
     * that would otherwise have to be kept in sync with the embedder's
     * geometry by hand. Runs on {@link WindowConfigureWatcher}'s own
     * background thread.
     */
    public void onResized(SizeListener callback) {
        onResized = callback;
        if (windowId >= 0) {
            configureWatcher.watch(windowId, (width, height) -> onResized.resized(width, height));
        }
    }

    /**
     * Starts watching this process's own already-known top-level window
     * ({@code windowId}) for {@link #onEmbedded}/{@link #onHostDetached}/
     * {@link #onResized} — everything {@link #announce} does except
     * resolving the window by pid/{@code WM_CLASS} and publishing {@code
     * _XEMBED_INFO}, both unneeded for a toolkit-opaque client whose host
     * embeds it via {@code EmbedSocket#embedOpaque} (which writes {@code
     * _XEMBED_INFO} on the client's behalf and doesn't require the client to
     * have announced itself at all). Use this when the client process
     * already knows its own native window handle directly (the same one it
     * hands the host out-of-band, e.g. on its own stdout) rather than
     * needing this class to resolve it.
     */
    public void watchOwnWindow(long windowId) {
        this.windowId = windowId;
        awaitingEmbed = true;
        reparentWatcher.watch(windowId, this::handleReparented);
        configureWatcher.watch(windowId, (width, height) -> onResized.resized(width, height));
        focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
    }

    /**
     * Sends {@code XEMBED_REQUEST_FOCUS} to the embedder, asking it to give
     * this window input focus. No-op if not currently embedded.
     */
    public void requestFocus() {
        long id = embedderWindowId;
        if (id >= 0) {
            synchronized (X11Display.GLOBAL_LOCK) {
                XEmbedMessages.send(display.raw(), id, XEmbedMessage.REQUEST_FOCUS, 0, 0, 0);
            }
        }
    }

    /**
     * Overrides how long {@link #offer} waits for this process's own
     * top-level window to become visible to the window manager before
     * giving up. Defaults to 5 seconds.
     */
    public void setWindowLookupTimeout(Duration timeout) {
        windowLookupTimeout = timeout;
    }

    /**
     * Blocks until this process's own top-level window is visible to the
     * window manager, marks it XEmbed-aware, then hands its process id to
     * the host at {@code hostSocketPath} so the host can look the window up
     * and reparent it. Starts watching for the host's death — and the
     * initial embed, see {@link #onEmbedded} — immediately beforehand, to
     * close the race against the host reparenting this window before the
     * watch is in place.
     */
    public void offer(Path hostSocketPath) {
        offer(hostSocketPath, null);
    }

    /**
     * Same as {@link #offer(Path)}, but for a process with more than one
     * top-level window: {@code wmClass} disambiguates which one gets
     * offered, by matching {@code WM_CLASS}'s class component (the same
     * string {@code xprop WM_CLASS} prints as the second, quoted value).
     */
    public void offer(Path hostSocketPath, String wmClass) {
        announce(wmClass);
        try {
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(address);
                PidHandshake.send(channel, ProcessHandle.current().pid());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Same as {@link #announce(String)}, for a process with a single
     * top-level window.
     */
    public void announce() {
        announce(null);
    }

    /**
     * Does everything {@link #offer} does except dial a host socket: blocks
     * until this process's own top-level window is visible to the window
     * manager, marks it XEmbed-aware, and starts watching for the host's
     * death and the initial embed (see {@link #onEmbedded}/{@link
     * #onHostDetached}). For a host that already knows this process's pid or
     * window handle directly — e.g. one that spawned this process itself —
     * without needing a Unix domain socket rendezvous to learn it. {@code
     * wmClass} disambiguates which top-level window gets announced the same
     * way {@link #offer(Path, String)}'s does; pass {@code null} for a
     * single-window process.
     */
    public void announce(String wmClass) {
        long pid = ProcessHandle.current().pid();
        windowId = waitForOwnWindow(pid, wmClass);

        synchronized (X11Display.GLOBAL_LOCK) {
            XEmbedInfoProperty.write(display.raw(), windowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }

        awaitingEmbed = true;
        reparentWatcher.watch(windowId, this::handleReparented);
        configureWatcher.watch(windowId, (width, height) -> onResized.resized(width, height));
        focusWatcher.watch(windowId, focused -> onFocusChanged.focusChanged(focused));
    }

    private void handleReparented(long newParentId) {
        if (newParentId == display.defaultRootWindow().longValue()) {
            if (embedderWindowId >= 0) {
                embedderWindowId = -1;
                onHostDetached.run();
            }
            // else: reparented to root without having been embedded first
            // (e.g. this window's very first map, before offer() was ever
            // called) — not a host detaching, nothing to report.
        } else if (awaitingEmbed) {
            awaitingEmbed = false;
            embedderWindowId = newParentId;
            onEmbedded.accept(newParentId);
        }
        // else: some other non-root reparent while not expecting an embed —
        // e.g. the window manager decorating this window into its own frame
        // right after a release — not an embed, ignore.
    }

    private long waitForOwnWindow(long pid, String wmClass) {
        if (wmClass != null) {
            // Filter by WM_CLASS from the first poll, rather than waiting
            // for *any* candidate and only then checking its class: a
            // multi-window process's sibling windows can still be unmapped
            // at that first sight, so stopping as soon as exactly one
            // candidate exists (regardless of class) can return the wrong
            // window before the expected one has even appeared.
            return pollForSingleMatch(
                    () -> WindowFinder.findTopLevelWindowsByPidAndClass(display, pid, wmClass),
                    "This process never published a top-level window matching WM_CLASS \"" + wmClass + "\"",
                    count -> "This process has " + count + " top-level windows matching WM_CLASS \"" + wmClass
                            + "\" (need exactly 1)");
        }
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        do {
            List<Long> ownWindows = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (ownWindows.size() == 1) {
                return ownWindows.get(0);
            }
            if (ownWindows.size() > 1) {
                throw new IllegalStateException("This process has " + ownWindows.size()
                        + " top-level windows; call offer(path, wmClass) to disambiguate");
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Could not resolve this process's own top-level window");
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
        configureWatcher.close();
        focusWatcher.close();
        display.close();
    }
}
