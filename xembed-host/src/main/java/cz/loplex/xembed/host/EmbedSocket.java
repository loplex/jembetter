package cz.loplex.xembed.host;

import com.sun.jna.platform.unix.X11.Display;
import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.InputFocus;
import cz.loplex.xembed.core.x11.Reparenting;
import cz.loplex.xembed.core.x11.WindowDeathWatcher;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.WindowGeometry;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.xembed.XEmbedFocus;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedMessage;
import cz.loplex.xembed.core.xembed.XEmbedMessages;

import java.awt.Frame;
import java.awt.Window;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A borderless top-level AWT window that a client process's own top-level
 * window gets reparented into.
 *
 * <p><strong>v3:</strong> after reparenting, resizes the embedded window to
 * fill this socket, keeps following this socket's own resizes, points X
 * input focus at the embedded window, forwards this socket owner's
 * activation state (XEMBED_FOCUS_IN/OUT, XEMBED_WINDOW_ACTIVATE/DEACTIVATE),
 * and detects the embedded process exiting or crashing via DestroyNotify.
 * Client-initiated focus requests (XEMBED_REQUEST_FOCUS) still aren't
 * handled — that needs an event loop reading ClientMessages sent to this
 * window, which AWT's own X11 connection currently owns.
 */
public final class EmbedSocket extends Window {

    private final X11Display display = X11Display.open(null);
    private final WindowDeathWatcher deathWatcher = new WindowDeathWatcher();
    private long windowId = -1;
    private volatile long embeddedWindowId = -1;
    private volatile Runnable onClientDetached = () -> {
    };

    public EmbedSocket(Frame owner) {
        super(owner);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                followSizeIntoEmbeddedWindow();
            }
        });
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

    /** Realizes the native window and resolves its own X11 window id. */
    public void open() {
        long pid = ProcessHandle.current().pid();
        Set<Long> before = new HashSet<>(WindowFinder.findTopLevelWindowsByPid(display, pid));

        setVisible(true);

        List<Long> appeared = pollUntil(() -> {
            List<Long> current = WindowFinder.findTopLevelWindowsByPid(display, pid);
            current.removeIf(before::contains);
            return current;
        }, list -> !list.isEmpty(), "Could not resolve this socket window's own X11 window id");

        windowId = appeared.get(0);
    }

    /**
     * Listens on {@code socketPath}, accepts exactly one client connection,
     * and reparents that client's top-level window into this one.
     */
    public void acceptOnce(Path socketPath) {
        if (windowId < 0) {
            throw new IllegalStateException("open() must be called before acceptOnce()");
        }
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
                    XEmbedMessages.send(display.raw(), clientWindowId, XEmbedMessage.EMBEDDED_NOTIFY, 0, windowId,
                            XEmbedInfo.PROTOCOL_VERSION);
                    InputFocus.set(display, clientWindowId);
                    sendActivated(getOwner().isFocused());
                    deathWatcher.watch(clientWindowId, this::handleClientDetached);
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

    private void handleClientDetached(long detachedWindowId) {
        embeddedWindowId = -1;
        onClientDetached.run();
    }

    private void followSizeIntoEmbeddedWindow() {
        long id = embeddedWindowId;
        if (id >= 0) {
            WindowGeometry.moveResize(display, id, 0, 0, getWidth(), getHeight());
        }
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
    public void dispose() {
        super.dispose();
        deathWatcher.close();
        display.close();
    }
}
