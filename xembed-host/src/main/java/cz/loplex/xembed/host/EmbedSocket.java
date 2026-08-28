package cz.loplex.xembed.host;

import com.sun.jna.platform.unix.X11.Display;
import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.InputFocus;
import cz.loplex.xembed.core.x11.RawWindow;
import cz.loplex.xembed.core.x11.Reparenting;
import cz.loplex.xembed.core.x11.WindowDeathWatcher;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.WindowGeometry;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.xembed.XEmbedFocus;
import cz.loplex.xembed.core.xembed.XEmbedInboundWatcher;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedInfoProperty;
import cz.loplex.xembed.core.xembed.XEmbedMessage;
import cz.loplex.xembed.core.xembed.XEmbedMessages;

import java.awt.Frame;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A raw, override-redirect X11 window that a client process's own top-level
 * window gets reparented into, kept positioned over wherever the host wants
 * it on screen.
 *
 * <p><strong>v4:</strong> unlike v0-v3, this is no longer a {@code
 * java.awt.Window}. AWT manages its own internal X11 connection, so
 * ClientMessages sent <em>to</em> an AWT-backed socket window (e.g.
 * XEMBED_REQUEST_FOCUS, or PropertyNotify on a client's _XEMBED_INFO) landed
 * on AWT's connection, not this library's — unreadable without reflecting
 * into JDK-internal AWT classes. Owning the window via {@code xembed-core}'s
 * own {@link X11Display} instead means those events can be read directly.
 * The tradeoff: this window is no longer part of the AWT window tree, so the
 * host is responsible for keeping it positioned over wherever it should
 * appear (e.g. a placeholder Swing component's {@code getLocationOnScreen()}
 * plus a resize/move listener calling {@link #setBounds}) rather than laying
 * it out with the rest of its UI.
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
    private volatile Runnable onClientDetached = () -> {
    };
    private volatile Runnable onFocusNext = () -> {
    };
    private volatile Runnable onFocusPrev = () -> {
    };

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
     * Listens on {@code socketPath}, accepts exactly one client connection,
     * and reparents that client's top-level window into this one.
     */
    public void acceptOnce(Path socketPath) {
        requireOpen();
        try {
            Files.deleteIfExists(socketPath);
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(address);
                try (SocketChannel accepted = server.accept()) {
                    long clientPid = PidHandshake.receive(accepted);
                    long clientWindowId = resolveClientWindow(clientPid);
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
            } finally {
                Files.deleteIfExists(socketPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        List<Long> found = pollUntil(
                () -> WindowFinder.findTopLevelWindowsByPid(display, clientPid),
                list -> !list.isEmpty(),
                "Client process " + clientPid + " never published a top-level window");
        return found.get(0);
    }

    private void requireOpen() {
        if (windowId < 0) {
            throw new IllegalStateException("open() must be called first");
        }
    }

    private static <T> T pollUntil(Supplier<T> probe, Predicate<T> done, String timeoutMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
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
