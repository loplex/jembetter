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
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
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

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = Win32TestClients.waitForOwnWindow(clientPid);

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

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        Win32TestClients.waitForOwnWindow(clientPid);
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

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = Win32TestClients.waitForOwnWindow(clientPid);

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

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientHwnd = Win32TestClients.waitForOwnWindow(clientProcess.pid());

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

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = Win32TestClients.waitForOwnWindow(clientPid);

        try (SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, embedderFailure)) {
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
}
