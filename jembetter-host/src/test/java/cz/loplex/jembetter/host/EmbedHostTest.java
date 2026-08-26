package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.x11.WindowFinder;
import cz.loplex.jembetter.core.x11.WindowTree;
import cz.loplex.jembetter.core.x11.X11Display;
import cz.loplex.jembetter.core.xembed.XEmbedInfo;
import cz.loplex.jembetter.core.xembed.XEmbedInfoProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.JFrame;
import java.awt.Canvas;
import java.awt.Dimension;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedHost}, the 1:1 facade over {@link EmbedSocket} —
 * confirms it actually reparents a
 * client under the host canvas via each of its embed paths, rather than
 * re-testing behavior ({@code EmbedSocket} internals, death detection
 * mechanics, ...) already covered by {@link EmbedSocketTest}.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EmbedHostTest {

    private JFrame owner;
    private EmbedHost host;
    private Process clientProcess;

    @AfterEach
    void cleanup() throws InterruptedException {
        if (host != null) {
            host.close();
        }
        if (owner != null) {
            owner.dispose();
        }
        if (clientProcess != null) {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void createRejectsACanvasNotInsideAFrame() {
        Canvas orphan = new Canvas();
        assertThrows(IllegalArgumentException.class, () -> EmbedHost.create(orphan));
    }

    @Test
    void embedsAKnownClientPidAndReportsDetach() throws IOException, InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        CountDownLatch detached = new CountDownLatch(1);
        host.onDetached(detached::countDown);

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientWindowId;
        try (X11Display display = X11Display.open(null)) {
            clientWindowId = waitForOwnWindow(display, clientPid);
            XEmbedInfoProperty.write(display.raw(), clientWindowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }

        host.embed(clientPid);

        long canvasWindowId = CanvasNativeHandle.extract(canvas);
        try (X11Display display = X11Display.open(null)) {
            assertTrue(isDescendantOf(display, clientWindowId, canvasWindowId),
                    "EmbedHost.embed(pid) did not reparent the client under the host canvas");
        }

        clientProcess.destroy();
        assertTrue(detached.await(5, TimeUnit.SECONDS), "onDetached never fired after the client process died");
        clientProcess = null;
    }

    @Test
    void embedsOverAOneShotRendezvousSocketWithoutLooping() throws Exception {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        Path socketPath = Files.createTempFile("jembetter-host-facade-test-", ".sock");
        Files.delete(socketPath);

        Thread embedder = new Thread(() -> host.embed(socketPath), "embed-host-test-embedder");
        embedder.setDaemon(true);
        embedder.start();

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientWindowId;
        try (X11Display display = X11Display.open(null)) {
            clientWindowId = waitForOwnWindow(display, clientPid);
            XEmbedInfoProperty.write(display.raw(), clientWindowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }

        waitForSocketToExist(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            PidHandshake.send(channel, clientPid);
        }

        embedder.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(!embedder.isAlive(), "EmbedHost.embed(Path) never returned after the one-shot handshake");

        long canvasWindowId = CanvasNativeHandle.extract(canvas);
        try (X11Display display = X11Display.open(null)) {
            assertTrue(isDescendantOf(display, clientWindowId, canvasWindowId),
                    "EmbedHost.embed(Path) did not reparent the client under the host canvas");
        }
    }

    private Canvas newVisibleHostCanvas() throws InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new JFrame("EmbedHostTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);
        return canvas;
    }

    private static void waitForSocketToExist(Path socketPath) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(socketPath)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("EmbedHost.embed(Path) never created the rendezvous socket");
            }
            Thread.sleep(20);
        }
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

    private static boolean isDescendantOf(X11Display display, long windowId, long ancestorWindowId) {
        long rootWindowId = display.defaultRootWindow().longValue();
        long current = windowId;
        for (int i = 0; i < 20 && current != rootWindowId; i++) {
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

    private static long waitForOwnWindow(X11Display display, long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = WindowFinder.findTopLevelWindowsByPid(display, pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Fake client window never became visible to the window manager");
    }
}
