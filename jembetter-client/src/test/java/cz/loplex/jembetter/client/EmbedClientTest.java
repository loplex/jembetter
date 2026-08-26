package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.x11.RawWindow;
import cz.loplex.jembetter.core.x11.Reparenting;
import cz.loplex.jembetter.core.x11.WindowFinder;
import cz.loplex.jembetter.core.x11.WindowGeometry;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.xembed.XEmbedInboundWatcher;
import cz.loplex.jembetter.core.xembed.XEmbedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.JFrame;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedClient} end to end against a real window manager and
 * a hand-rolled "host": accepts the PID handshake, reparents the client's
 * window under one of its own with the save-set the real
 * {@code jembetter-host.EmbedSocket} relies on, then simulates the host process
 * dying by closing its own connection.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EmbedClientTest {

    private JFrame frame;
    private EmbedClient client;

    @AfterEach
    void cleanup() {
        if (client != null) {
            client.close();
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    @Test
    void detectsHostDeathAfterBeingEmbedded() throws IOException, InterruptedException {
        frame = new JFrame("jembetter-client EmbedClientTest");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        Path socketPath = Files.createTempFile("jembetter-client-test-", ".sock");
        Files.delete(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try {
            CountDownLatch hostReady = new CountDownLatch(1);
            CountDownLatch hostDone = new CountDownLatch(1);
            Thread host = new Thread(() -> runFakeHost(address, hostReady, hostDone));
            host.setDaemon(true);
            host.start();
            assertTrue(hostReady.await(5, TimeUnit.SECONDS), "fake host never started listening");

            CountDownLatch detached = new CountDownLatch(1);
            client = new EmbedClient();
            client.onHostDetached(detached::countDown);
            client.offer(socketPath);

            assertTrue(hostDone.await(5, TimeUnit.SECONDS), "fake host never finished embedding and dying");
            assertTrue(detached.await(5, TimeUnit.SECONDS), "onHostDetached was never invoked");
        } finally {
            Files.deleteIfExists(socketPath);
        }
    }

    @Test
    void learnsEmbedderWindowIdAndCanRequestFocus() throws IOException, InterruptedException {
        frame = new JFrame("jembetter-client EmbedClientTest");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        Path socketPath = Files.createTempFile("jembetter-client-test-", ".sock");
        Files.delete(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try {
            CountDownLatch hostReady = new CountDownLatch(1);
            AtomicLong hostEmbedderWindow = new AtomicLong(-1);
            CountDownLatch focusRequested = new CountDownLatch(1);
            Thread host = new Thread(() -> runFakeHostAwaitingFocusRequest(address, hostReady, hostEmbedderWindow,
                    focusRequested));
            host.setDaemon(true);
            host.start();
            assertTrue(hostReady.await(5, TimeUnit.SECONDS), "fake host never started listening");

            CountDownLatch embedded = new CountDownLatch(1);
            AtomicLong clientReportedEmbedderWindow = new AtomicLong(-1);
            client = new EmbedClient();
            client.onEmbedded(id -> {
                clientReportedEmbedderWindow.set(id);
                embedded.countDown();
            });
            client.offer(socketPath);

            assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked");
            assertEquals(hostEmbedderWindow.get(), clientReportedEmbedderWindow.get());

            client.requestFocus();
            assertTrue(focusRequested.await(5, TimeUnit.SECONDS), "host never received XEMBED_REQUEST_FOCUS");
        } finally {
            Files.deleteIfExists(socketPath);
        }
    }

    /**
     * Regression coverage for splitting {@link EmbedClient#announce(String)}
     * out of {@link EmbedClient#offer(Path, String)}: a host that already
     * knows this process's pid directly (e.g. because it spawned this
     * process itself) can embed it after only {@code announce()} — no Unix
     * domain socket rendezvous at all.
     */
    @Test
    void announceSetsUpXEmbedInfoAndWatchersWithoutDialingAHostSocket() throws IOException, InterruptedException {
        frame = new JFrame("jembetter-client EmbedClientTest");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch embedded = new CountDownLatch(1);
        AtomicLong reportedEmbedderWindow = new AtomicLong(-1);
        client = new EmbedClient();
        client.onEmbedded(id -> {
            reportedEmbedderWindow.set(id);
            embedded.countDown();
        });
        client.announce();

        long pid = ProcessHandle.current().pid();
        try (X11Display hostDisplay = X11Display.open(null)) {
            long embedderWindow = RawWindow.createOverrideRedirect(hostDisplay, 0, 0, 100, 100);
            long clientWindowId = resolveClientWindow(hostDisplay, pid);
            Reparenting.reparent(hostDisplay, clientWindowId, embedderWindow, 0, 0);

            assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after announce()");
            assertEquals(embedderWindow, reportedEmbedderWindow.get());
        }
    }

    /**
     * Regression coverage for {@link EmbedClient#watchOwnWindow}/{@link
     * EmbedClient#onResized}: a toolkit-opaque client embedded via {@code
     * EmbedSocket#embedOpaque} never calls {@link EmbedClient#announce}, so
     * this is the only path that wires {@link
     * cz.loplex.jembetter.core.x11.WindowConfigureWatcher} up for it.
     */
    @Test
    void watchOwnWindowInvokesOnResizedWhenAnotherConnectionResizesTheWindow() throws InterruptedException {
        try (X11Display rawDisplay = X11Display.open(null)) {
            long windowId = RawWindow.createOverrideRedirect(rawDisplay, 0, 0, 10, 10);
            try {
                CountDownLatch resized = new CountDownLatch(1);
                AtomicLong reportedWidth = new AtomicLong(-1);
                AtomicLong reportedHeight = new AtomicLong(-1);
                client = new EmbedClient();
                client.onResized((width, height) -> {
                    reportedWidth.set(width);
                    reportedHeight.set(height);
                    resized.countDown();
                });
                client.watchOwnWindow(windowId);

                // Stands in for a host following its own resize into an
                // embedded window (WindowGeometry#moveResize), on a
                // connection distinct from both the client's and the raw
                // window's creator above.
                try (X11Display hostDisplay = X11Display.open(null)) {
                    WindowGeometry.moveResize(hostDisplay, windowId, 0, 0, 42, 24);
                }

                assertTrue(resized.await(5, TimeUnit.SECONDS), "onResized was never invoked");
                assertEquals(42, reportedWidth.get());
                assertEquals(24, reportedHeight.get());
            } finally {
                RawWindow.destroy(rawDisplay, windowId);
            }
        }
    }

    /** Covers {@link EmbedClient#onResized}'s rewatch branch: registering the callback after {@link EmbedClient#watchOwnWindow} must still wire it up. */
    @Test
    void onResizedRegisteredAfterWatchOwnWindowStillReceivesCallbacks() throws InterruptedException {
        try (X11Display rawDisplay = X11Display.open(null)) {
            long windowId = RawWindow.createOverrideRedirect(rawDisplay, 0, 0, 10, 10);
            try {
                client = new EmbedClient();
                client.watchOwnWindow(windowId);

                CountDownLatch resized = new CountDownLatch(1);
                client.onResized((width, height) -> resized.countDown());

                try (X11Display hostDisplay = X11Display.open(null)) {
                    WindowGeometry.moveResize(hostDisplay, windowId, 0, 0, 33, 18);
                }

                assertTrue(resized.await(5, TimeUnit.SECONDS),
                        "onResized registered after watchOwnWindow was never invoked");
            } finally {
                RawWindow.destroy(rawDisplay, windowId);
            }
        }
    }

    private void runFakeHostAwaitingFocusRequest(UnixDomainSocketAddress address, CountDownLatch ready,
            AtomicLong embedderWindowOut, CountDownLatch focusRequested) {
        try (X11Display hostDisplay = X11Display.open(null);
                ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            ready.countDown();

            long embedderWindow = RawWindow.createOverrideRedirect(hostDisplay, 0, 0, 100, 100);
            embedderWindowOut.set(embedderWindow);
            try (XEmbedInboundWatcher inbound = new XEmbedInboundWatcher(hostDisplay, embedderWindow)) {
                inbound.onClientMessage((message, detail) -> {
                    if (message == XEmbedMessage.REQUEST_FOCUS) {
                        focusRequested.countDown();
                    }
                });
                try (SocketChannel accepted = server.accept()) {
                    long clientPid = PidHandshake.receive(accepted);
                    long clientWindowId = resolveClientWindow(hostDisplay, clientPid);
                    Reparenting.reparent(hostDisplay, clientWindowId, embedderWindow, 0, 0);
                }
                focusRequested.await(5, TimeUnit.SECONDS);
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void runFakeHost(UnixDomainSocketAddress address, CountDownLatch ready, CountDownLatch done) {
        try (X11Display hostDisplay = X11Display.open(null);
                ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            ready.countDown();

            long embedderWindow = RawWindow.createOverrideRedirect(hostDisplay, 0, 0, 100, 100);
            try (SocketChannel accepted = server.accept()) {
                long clientPid = PidHandshake.receive(accepted);
                long clientWindowId = resolveClientWindow(hostDisplay, clientPid);
                Reparenting.reparent(hostDisplay, clientWindowId, embedderWindow, 0, 0);
            }
            // Falling out of the try-with-resources below closes
            // hostDisplay, simulating the host process dying.
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            done.countDown();
        }
    }

    private static long resolveClientWindow(X11Display display, long pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Client process " + pid + " never published a top-level window");
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
