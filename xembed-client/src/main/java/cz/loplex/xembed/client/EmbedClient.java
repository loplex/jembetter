package cz.loplex.xembed.client;

import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.WindowReparentWatcher;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedInfoProperty;
import cz.loplex.xembed.core.xembed.XEmbedMessage;
import cz.loplex.xembed.core.xembed.XEmbedMessages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.LongConsumer;

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
    private long windowId = -1;
    private volatile long embedderWindowId = -1;
    private volatile boolean awaitingEmbed = false;
    private volatile Runnable onHostDetached = () -> {
    };
    private volatile LongConsumer onEmbedded = embedderId -> {
    };
    private volatile Duration windowLookupTimeout = Duration.ofSeconds(5);

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
     * Registers a callback invoked once this window has actually been
     * reparented into an embedder, with the embedder's window id.
     *
     * <p>This deliberately doesn't come from XEmbed's own {@code
     * EMBEDDED_NOTIFY} ClientMessage: {@code XSendEvent} with a zero event
     * mask (as XEmbed requires for ClientMessages) is only ever delivered to
     * the connection that <em>created</em> the destination window — see
     * {@code cz.loplex.xembed.host.EmbedSocket}'s Javadoc, which is why the
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
     * cz.loplex.xembed.host.EmbedSocket#detachClient()} — without this
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
     * Sends {@code XEMBED_REQUEST_FOCUS} to the embedder, asking it to give
     * this window input focus. No-op if not currently embedded.
     */
    public void requestFocus() {
        long id = embedderWindowId;
        if (id >= 0) {
            XEmbedMessages.send(display.raw(), id, XEmbedMessage.REQUEST_FOCUS, 0, 0, 0);
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
        try {
            long pid = ProcessHandle.current().pid();
            windowId = waitForOwnWindow(pid, wmClass);

            XEmbedInfoProperty.write(display.raw(), windowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));

            awaitingEmbed = true;
            reparentWatcher.watch(windowId, this::handleReparented);

            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(hostSocketPath);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(address);
                PidHandshake.send(channel, pid);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        long deadline = System.nanoTime() + windowLookupTimeout.toNanos();
        List<Long> ownWindows;
        do {
            ownWindows = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (ownWindows.size() == 1) {
                return ownWindows.get(0);
            }
            if (ownWindows.size() > 1) {
                if (wmClass == null) {
                    throw new IllegalStateException("This process has " + ownWindows.size()
                            + " top-level windows; call offer(path, wmClass) to disambiguate");
                }
                List<Long> matches = ownWindows.stream()
                        .filter(id -> WindowFinder.readWmClass(display, id).map(wmClass::equals).orElse(false))
                        .toList();
                if (matches.size() != 1) {
                    throw new IllegalStateException("This process has " + ownWindows.size()
                            + " top-level windows and " + matches.size() + " match WM_CLASS \"" + wmClass
                            + "\" (need exactly 1)");
                }
                return matches.get(0);
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
