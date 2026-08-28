package cz.loplex.xembed.host;

import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.Reparenting;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.X11Display;

import java.awt.Frame;
import java.awt.Window;
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
 * <p><strong>v0:</strong> proves the discovery + handshake + reparent
 * pipeline end to end. It is not yet XEmbed-compliant: there is no focus or
 * resize protocol running between host and client after the reparent, and
 * the embedded window keeps whatever size it had before being embedded.
 */
public final class EmbedSocket extends Window {

    private final X11Display display = X11Display.open(null);
    private long windowId = -1;

    public EmbedSocket(Frame owner) {
        super(owner);
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
                }
            } finally {
                Files.deleteIfExists(socketPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
        display.close();
    }
}
