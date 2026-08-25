package cz.loplex.xembed.client;

import cz.loplex.xembed.common.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.RawWindow;
import cz.loplex.xembed.core.x11.Reparenting;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.X11Display;
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
 * Exercises {@link EmbedPlug}, the 1:1 facade over {@link EmbedClient} —
 * confirms its two {@code announce} paths
 * (known-handle and socket-rendezvous) and its callbacks actually delegate
 * to a working {@code EmbedClient}, rather than re-testing behavior already
 * covered by {@link EmbedClientTest}.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EmbedPlugTest {

    private JFrame frame;
    private EmbedPlug plug;

    @AfterEach
    void cleanup() {
        if (plug != null) {
            plug.close();
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    @Test
    void announcesWithoutDialingAHostSocket() throws IOException, InterruptedException {
        frame = new JFrame("EmbedPlugTest");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch embedded = new CountDownLatch(1);
        AtomicLong reportedEmbedderWindow = new AtomicLong(-1);
        plug = EmbedPlug.create();
        plug.onEmbedded(id -> {
            reportedEmbedderWindow.set(id);
            embedded.countDown();
        });
        plug.announce(null);

        long pid = ProcessHandle.current().pid();
        try (X11Display hostDisplay = X11Display.open(null)) {
            long embedderWindow = RawWindow.createOverrideRedirect(hostDisplay, 0, 0, 100, 100);
            long clientWindowId = resolveClientWindow(hostDisplay, pid);
            Reparenting.reparent(hostDisplay, clientWindowId, embedderWindow, 0, 0);

            assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after announce(wmClass)");
            assertEquals(embedderWindow, reportedEmbedderWindow.get());
        }
    }

    @Test
    void announcesOverAHostSocketAndDetectsHostDeath() throws IOException, InterruptedException {
        frame = new JFrame("EmbedPlugTest");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        Path socketPath = Files.createTempFile("xembed-client-facade-test-", ".sock");
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
            plug = EmbedPlug.create();
            plug.onHostDetached(detached::countDown);
            plug.announce(socketPath, null);

            assertTrue(hostDone.await(5, TimeUnit.SECONDS), "fake host never finished embedding and dying");
            assertTrue(detached.await(5, TimeUnit.SECONDS), "onHostDetached was never invoked");
        } finally {
            Files.deleteIfExists(socketPath);
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
