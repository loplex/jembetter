package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

import javax.swing.JFrame;
import java.awt.AWTException;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedHost}'s Win32 backend ({@code EmbedHostWin32})
 * against real HWNDs — mirrors {@link EmbedHostTest}'s X11 coverage, using
 * {@link Win32Reparent}/{@link Win32WindowFinder} for verification instead
 * of raw X11 calls. Gated on {@code OS.WINDOWS} the same way {@code
 * jembetter-core-win32}'s own tests are — see that module's {@code
 * Win32ReparentTest} for why that also covers the Wine-hosted run.
 */
@Tag("windows")
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
        long clientHwnd = waitForOwnWindow(clientPid);

        host.embed(clientPid);

        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        assertEquals(canvasHwnd, Win32Reparent.parentOf(clientHwnd),
                "EmbedHost.embed(pid) did not reparent the client under the host canvas HWND");

        clientProcess.destroy();
        assertTrue(detached.await(5, TimeUnit.SECONDS), "onDetached never fired after the client process died");
        clientProcess = null;
    }

    @Test
    void clickIntoEmbeddedAreaDoesNotThrow() throws IOException, InterruptedException, AWTException {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();
        waitForOwnWindow(clientPid);
        host.embed(clientPid);

        Point center = new Point(canvas.getLocationOnScreen());
        center.translate(canvas.getWidth() / 2, canvas.getHeight() / 2);
        Robot robot = new Robot();

        assertDoesNotThrow(() -> {
            robot.mouseMove(center.x, center.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }, "a real click into the embedded area must not throw through the click-to-focus hook");
    }

    /**
     * Regression coverage for the opt-in destroying close: {@link
     * EmbedHost#tryDestroy()} posts {@code WM_CLOSE} to the still-embedded
     * client HWND via {@code Win32Window#destroy}, rather than leaving it
     * untouched the way plain {@link EmbedHost#close()} does on this backend
     * (Win32's {@code SetParent} has no save-set-style "graceful release"
     * step to begin with — see {@code EmbedHostWin32}'s Javadoc). The fake
     * client here doesn't override {@code WM_CLOSE} handling (a plain
     * {@code STATIC} top-level window — see {@link FakeClientProcessMain}),
     * so the default {@code DefWindowProc} behavior actually destroys it;
     * this asserts that end-to-end outcome rather than the {@code WM_CLOSE}
     * post itself, since {@link Win32Window#destroy} only guarantees the
     * ask — see {@link EmbedHost#tryDestroy()}'s Javadoc for why this isn't
     * the same unconditional guarantee the X11 backend's {@code
     * XDestroyWindow}-based {@code destroyClient()} gives. Closes {@code
     * host} explicitly here (nulling the field afterward) rather than
     * relying on {@code @AfterEach}'s own {@code close()} call, since that
     * only exercises the non-destroying path.
     */
    @Test
    void tryDestroyDestroysTheStillEmbeddedClientHwnd() throws IOException, InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        host = EmbedHost.create(canvas);

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = waitForOwnWindow(clientPid);

        host.embed(clientPid);

        host.tryDestroy();
        host = null;

        assertTrue(waitUntilWindowDestroyed(clientHwnd),
                "tryDestroy() did not destroy the still-embedded client HWND");
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

        AtomicReference<Throwable> embedderFailure = new AtomicReference<>();
        Thread embedder = new Thread(() -> host.embed(socketPath), "embed-host-win32-test-embedder");
        embedder.setDaemon(true);
        embedder.setUncaughtExceptionHandler((t, e) -> embedderFailure.set(e));
        embedder.start();

        clientProcess = startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = waitForOwnWindow(clientPid);

        try (SocketChannel channel = connectWhenReady(socketPath, embedderFailure)) {
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

    /**
     * Connects to the rendezvous socket once {@code EmbedHost.embed(Path)}'s
     * background thread has bound it, retrying until then. Polling the
     * connect rather than {@link Files#exists} on the socket path is
     * deliberate: the Wine-hosted JDK this test runs under implements Windows
     * {@code AF_UNIX} without ever materialising a filesystem socket node, so
     * {@code Files.exists} on the bound path stays {@code false} forever even
     * though the socket is fully connectable.
     */
    private static SocketChannel connectWhenReady(Path socketPath,
            AtomicReference<Throwable> embedderFailure) throws InterruptedException, IOException {
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Throwable failure = embedderFailure.get();
            if (failure != null) {
                throw new IllegalStateException("EmbedHost.embed(Path) threw before binding the rendezvous socket", failure);
            }
            SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                channel.connect(address);
                return channel;
            } catch (IOException notReadyYet) {
                channel.close();
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("EmbedHost.embed(Path) never bound the rendezvous socket", notReadyYet);
                }
                Thread.sleep(20);
            }
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

    /** Polls {@code IsWindow} until {@code hwnd} no longer refers to a live window. */
    private static boolean waitUntilWindowDestroyed(long hwnd) throws InterruptedException {
        HWND handle = new HWND(new Pointer(hwnd));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (!User32.INSTANCE.IsWindow(handle)) {
                return true;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static long waitForOwnWindow(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            List<Long> found = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Fake client window never became visible; top-level windows for pid " + pid
                + ": " + Win32WindowFinder.findTopLevelWindowsByPid(pid).stream()
                        .map(Win32WindowFinder::describeWindow)
                        .collect(java.util.stream.Collectors.joining("; ")));
    }
}
