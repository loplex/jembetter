package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedHost}'s Win32 backend ({@code EmbedHostWin32})
 * against real HWNDs — mirrors {@link EmbedHostTest}'s X11 coverage, using
 * {@link Win32Reparent}/{@link Win32WindowFinder} for verification instead
 * of raw X11 calls. Gated on {@code OS.WINDOWS} the same way {@code
 * jembetter-core-win32}'s own tests are — see that module's {@code
 * Win32ReparentTest} for why that also covers the Wine-based smoke test.
 */
@EnabledOnOs(OS.WINDOWS)
class EmbedHostWin32Test {

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
    void embedsAKnownClientPidAndReportsDetach() throws IOException, InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        CountDownLatch detached = new CountDownLatch(1);
        host.onDetached(detached::countDown);

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();

        host.embed(clientPid);

        long clientHwnd = waitForOwnWindow(clientPid);
        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        assertEquals(canvasHwnd, Win32Reparent.parentOf(clientHwnd),
                "EmbedHost.embed(pid) did not reparent the client under the host canvas HWND");

        clientProcess.destroy();
        assertTrue(detached.await(5, TimeUnit.SECONDS), "onDetached never fired after the client process died");
        clientProcess = null;
    }

    @Test
    void embedsAKnownClientWindowOpaquely() throws IOException, InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        clientProcess = startFakeClientProcess();
        long clientHwnd = waitForOwnWindow(clientProcess.pid());

        host.embedOpaque(clientHwnd);

        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        assertEquals(canvasHwnd, Win32Reparent.parentOf(clientHwnd),
                "EmbedHost.embedOpaque(windowId) did not reparent the client under the host canvas HWND");
    }

    @Test
    void embedsOverAOneShotRendezvousSocketWithoutLooping() throws Exception {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        Path socketPath = Files.createTempFile("jembetter-host-win32-facade-test-", ".sock");
        Files.delete(socketPath);

        Thread embedder = new Thread(() -> host.embed(socketPath), "embed-host-win32-test-embedder");
        embedder.setDaemon(true);
        embedder.start();

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = waitForOwnWindow(clientPid);

        waitForSocketToExist(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            PidHandshake.send(channel, clientPid);
        }

        embedder.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(!embedder.isAlive(), "EmbedHost.embed(Path) never returned after the one-shot handshake");

        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        assertEquals(canvasHwnd, Win32Reparent.parentOf(clientHwnd),
                "EmbedHost.embed(Path) did not reparent the client under the host canvas HWND");
    }

    private Canvas newVisibleHostCanvas() throws InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new JFrame("EmbedHostWin32Test owner");
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
        String javaBin = System.getProperty("java.home") + "\\bin\\java.exe";
        ProcessBuilder processBuilder = new ProcessBuilder(javaBin,
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("java.class.path"),
                FakeClientProcessMain.class.getName());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return processBuilder.start();
    }

    private static long waitForOwnWindow(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = Win32WindowFinder.findTopLevelWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Fake client window never became visible");
    }
}
