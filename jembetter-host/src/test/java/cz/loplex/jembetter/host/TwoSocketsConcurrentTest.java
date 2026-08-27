package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.x11.WindowFinder;
import cz.loplex.jembetter.core.x11.WindowReparentWatcher;
import cz.loplex.jembetter.core.x11.WindowTree;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.xembed.XEmbedInfo;
import cz.loplex.jembetter.core.xembed.XEmbedInfoProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises several {@link EmbedSocket}s live in one host process at the same
 * time — each with its own {@link X11Display} connection, its own background
 * watcher threads, and its own embedded client — which nothing structurally
 * prevents but no other test covers ({@code EmbedSocket#listen}'s accept loop
 * is deliberately one-client-at-a-time <em>per socket</em>; simultaneous
 * embedding means several sockets, not several clients on one).
 *
 * <p>The two fake clients run as genuinely separate JVM processes (see {@link
 * FakeClientProcessMain}): distinct pids let each socket's window lookup
 * resolve unambiguously, and it matches how the library is actually used.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class TwoSocketsConcurrentTest {

    private Frame owner;
    private EmbedSocket socketA;
    private EmbedSocket socketB;
    private Process clientA;
    private Process clientB;

    @AfterEach
    void cleanup() {
        if (socketA != null) {
            socketA.close();
        }
        if (socketB != null) {
            socketB.close();
        }
        if (clientA != null) {
            clientA.destroy();
        }
        if (clientB != null) {
            clientB.destroy();
        }
        if (owner != null) {
            owner.dispose();
        }
    }

    @Test
    void twoSocketsInOneProcessEachHoldTheirOwnClientAtTheSameTime()
            throws IOException, InterruptedException {
        Canvas canvasA = new Canvas();
        Canvas canvasB = new Canvas();
        canvasA.setPreferredSize(new Dimension(100, 100));
        canvasB.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("TwoSocketsConcurrentTest owner");
        owner.setLayout(new GridLayout(1, 2));
        owner.add(canvasA);
        owner.add(canvasB);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socketA = new EmbedSocket(owner);
        socketB = new EmbedSocket(owner);
        socketA.open(canvasA);
        socketB.open(canvasB);

        Path socketPathA = freshSocketPath();
        Path socketPathB = freshSocketPath();
        CountDownLatch embeddedA = new CountDownLatch(1);
        CountDownLatch embeddedB = new CountDownLatch(1);
        socketA.onClientEmbedded(embeddedA::countDown);
        socketB.onClientEmbedded(embeddedB::countDown);
        socketA.listen(socketPathA);
        socketB.listen(socketPathB);

        clientA = startFakeClientProcess();
        long clientWindowA = offerClient(socketPathA, clientA.pid());
        assertTrue(embeddedA.await(5, TimeUnit.SECONDS), "first socket's client was never embedded");

        clientB = startFakeClientProcess();
        long clientWindowB = offerClient(socketPathB, clientB.pid());
        assertTrue(embeddedB.await(5, TimeUnit.SECONDS),
                "second socket's client was never embedded while the first was still embedded");

        try (X11Display display = X11Display.open(null)) {
            assertTrue(isDescendantOf(display, clientWindowA, CanvasNativeHandle.extract(canvasA)),
                    "first client is no longer a child of its own socket's canvas");
            assertTrue(isDescendantOf(display, clientWindowB, CanvasNativeHandle.extract(canvasB)),
                    "second client is not a child of its own socket's canvas");
        }

        assertTrue(countThreadsNamed("xembed-inbound-watcher") >= 2,
                "expected each EmbedSocket to be driving its own inbound-watcher thread");
    }

    @Test
    void closingOneSocketReleasesOnlyItsOwnClient() throws IOException, InterruptedException {
        Canvas canvasA = new Canvas();
        Canvas canvasB = new Canvas();
        canvasA.setPreferredSize(new Dimension(100, 100));
        canvasB.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("TwoSocketsConcurrentTest owner");
        owner.setLayout(new GridLayout(1, 2));
        owner.add(canvasA);
        owner.add(canvasB);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socketA = new EmbedSocket(owner);
        socketB = new EmbedSocket(owner);
        socketA.open(canvasA);
        socketB.open(canvasB);

        Path socketPathA = freshSocketPath();
        Path socketPathB = freshSocketPath();
        CountDownLatch embeddedA = new CountDownLatch(1);
        CountDownLatch embeddedB = new CountDownLatch(1);
        socketA.onClientEmbedded(embeddedA::countDown);
        socketB.onClientEmbedded(embeddedB::countDown);
        socketA.listen(socketPathA);
        socketB.listen(socketPathB);

        clientA = startFakeClientProcess();
        long clientWindowA = offerClient(socketPathA, clientA.pid());
        assertTrue(embeddedA.await(5, TimeUnit.SECONDS), "first socket's client was never embedded");
        clientB = startFakeClientProcess();
        long clientWindowB = offerClient(socketPathB, clientB.pid());
        assertTrue(embeddedB.await(5, TimeUnit.SECONDS), "second socket's client was never embedded");

        try (X11Display probe = X11Display.open(null); WindowReparentWatcher watcher = new WindowReparentWatcher()) {
            long root = probe.defaultRootWindow().longValue();
            CountDownLatch aReleased = new CountDownLatch(1);
            CountDownLatch bReleased = new CountDownLatch(1);
            watcher.watch(clientWindowA, parent -> {
                if (parent == root) {
                    aReleased.countDown();
                }
            });
            watcher.watch(clientWindowB, parent -> {
                if (parent == root) {
                    bReleased.countDown();
                }
            });

            socketA.close();
            socketA = null;

            assertTrue(aReleased.await(5, TimeUnit.SECONDS),
                    "closing the first socket did not release its own client back to root");
            assertFalse(bReleased.await(1, TimeUnit.SECONDS),
                    "closing the first socket also released the second socket's client");
            assertTrue(isDescendantOf(probe, clientWindowB, CanvasNativeHandle.extract(canvasB)),
                    "second client stopped being embedded when the first socket closed");
        }
    }

    private static long offerClient(Path socketPath, long pid) throws IOException, InterruptedException {
        long clientWindowId;
        try (X11Display display = X11Display.open(null)) {
            clientWindowId = waitForOwnWindow(display, pid);
            XEmbedInfoProperty.write(display.raw(), clientWindowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            PidHandshake.send(channel, pid);
        }
        return clientWindowId;
    }

    private static Path freshSocketPath() throws IOException {
        Path path = Files.createTempFile("jembetter-two-sockets-test-", ".sock");
        Files.delete(path);
        return path;
    }

    private static long waitForOwnWindow(X11Display display, long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            List<Long> found = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("fake client process " + pid + " never published a top-level window");
    }

    private static boolean isDescendantOf(X11Display display, long windowId, long ancestorWindowId) {
        long root = display.defaultRootWindow().longValue();
        long current = windowId;
        for (int i = 0; i < 20 && current != root; i++) {
            long parent = WindowTree.parentOf(display, current);
            if (parent == ancestorWindowId) {
                return true;
            }
            if (parent == current) {
                break;
            }
            current = parent;
        }
        return false;
    }

    private static long countThreadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> name.equals(t.getName())).count();
    }

    private static Process startFakeClientProcess() throws IOException {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder processBuilder = new ProcessBuilder(javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                FakeClientProcessMain.class.getName());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return processBuilder.start();
    }
}
