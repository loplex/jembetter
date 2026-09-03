package cz.loplex.jembetter.host;

import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.ControlMessage;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32FocusWatcher;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import java.awt.Canvas;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedSocketWin32}'s advanced-API surface over {@link
 * EmbedHostWin32Test}'s single-client coverage — currently just {@link
 * EmbedSocketWin32#detachClient()}, the one capability {@link EmbedHost}
 * deliberately doesn't expose. Gated on {@code OS.WINDOWS} the same way
 * {@code jembetter-core-win32}'s own tests are — see that module's {@code
 * Win32ReparentTest} for why that also covers the Wine-hosted run.
 */
@Tag("windows")
class EmbedSocketWin32Test {

    private JFrame owner;
    private EmbedSocketWin32 socket;
    private Process clientProcess;

    @AfterEach
    void cleanup() throws InterruptedException {
        if (socket != null) {
            socket.close();
        }
        if (owner != null) {
            owner.dispose();
        }
        if (clientProcess != null) {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** {@link EmbedSocket#create} dispatches to the Win32 implementation on Windows. */
    @Test
    void factoryReturnsTheWin32ImplementationOnThisPlatform() throws InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        socket = assertInstanceOf(EmbedSocketWin32.class, EmbedSocket.create(canvas));
    }

    @Test
    void detachClientReleasesTheWindowWithoutFiringOnClientDetached() throws IOException, InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        socket = new EmbedSocketWin32(canvas);

        CountDownLatch detached = new CountDownLatch(1);
        socket.onClientDetached(detached::countDown);

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = Win32TestClients.waitForOwnWindow(clientPid);

        socket.embed(clientPid);
        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        assertEquals(canvasHwnd, Win32Reparent.parentOf(clientHwnd),
                "embed(pid) did not reparent the client under the host canvas HWND");

        socket.detachClient();

        assertNotEquals(canvasHwnd, Win32Reparent.parentOf(clientHwnd),
                "detachClient() did not release the client from the host canvas HWND");
        assertFalse(detached.await(500, TimeUnit.MILLISECONDS), "onClientDetached fired for a voluntary detach");

        // The earlier detachClient() call already unwatched this window;
        // the client process exiting now must not retroactively fire
        // onClientDetached for it.
        clientProcess.destroy();
        clientProcess.waitFor(5, TimeUnit.SECONDS);
        clientProcess = null;
        assertFalse(detached.await(500, TimeUnit.MILLISECONDS),
                "onClientDetached fired after the already-detached client's process exited");
    }

    @Test
    void detachClientAllowsEmbeddingADifferentClientAfterward() throws IOException, InterruptedException {
        Canvas canvas = newVisibleHostCanvas();
        socket = new EmbedSocketWin32(canvas);
        long canvasHwnd = CanvasNativeHandle.extract(canvas);

        Process firstClient = Win32TestClients.startFakeClientProcess();
        long firstPid = firstClient.pid();
        long firstHwnd = Win32TestClients.waitForOwnWindow(firstPid);
        socket.embed(firstPid);
        socket.detachClient();
        firstClient.destroy();
        firstClient.waitFor(5, TimeUnit.SECONDS);

        clientProcess = Win32TestClients.startFakeClientProcess();
        long secondPid = clientProcess.pid();
        long secondHwnd = Win32TestClients.waitForOwnWindow(secondPid);
        socket.embed(secondPid);

        assertEquals(canvasHwnd, Win32Reparent.parentOf(secondHwnd),
                "embed() after detachClient() did not reparent the second client under the host canvas HWND");
        assertNotEquals(canvasHwnd, Win32Reparent.parentOf(firstHwnd),
                "the first, already-detached client should not have been re-adopted");
    }

    @Test
    void listenReEmbedsANewClientAfterThePreviousOneDetaches() throws Exception {
        Canvas canvas = newVisibleHostCanvas();
        socket = new EmbedSocketWin32(canvas);
        long canvasHwnd = CanvasNativeHandle.extract(canvas);

        Path socketPath = Files.createTempFile("jembetter-host-win32-socket-test-", ".sock");
        Files.delete(socketPath);

        CountDownLatch firstEmbed = new CountDownLatch(1);
        socket.onClientEmbedded(firstEmbed::countDown);
        socket.listen(socketPath);

        Process firstClient = Win32TestClients.startFakeClientProcess();
        long firstPid = firstClient.pid();
        long firstHwnd = Win32TestClients.waitForOwnWindow(firstPid);
        try (SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, new AtomicReference<>())) {
            PidHandshake.send(channel, firstPid);
        }
        assertTrue(firstEmbed.await(5, TimeUnit.SECONDS), "first client was never embedded via listen()");
        assertEquals(canvasHwnd, Win32Reparent.parentOf(firstHwnd),
                "listen() did not reparent the first client under the host canvas HWND");

        socket.detachClient();
        firstClient.destroy();
        firstClient.waitFor(5, TimeUnit.SECONDS);

        CountDownLatch secondEmbed = new CountDownLatch(1);
        socket.onClientEmbedded(secondEmbed::countDown);
        clientProcess = Win32TestClients.startFakeClientProcess();
        long secondPid = clientProcess.pid();
        long secondHwnd = Win32TestClients.waitForOwnWindow(secondPid);
        try (SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, new AtomicReference<>())) {
            PidHandshake.send(channel, secondPid);
        }
        assertTrue(secondEmbed.await(5, TimeUnit.SECONDS),
                "a second client was never (re-)embedded on the same socket after the first detached");
        assertEquals(canvasHwnd, Win32Reparent.parentOf(secondHwnd),
                "listen() did not reparent the second client under the host canvas HWND after re-embedding");
    }

    @Test
    void setModalWritesAModalityFrameIntoTheListenControlChannel() throws Exception {
        Canvas canvas = newVisibleHostCanvas();
        socket = new EmbedSocketWin32(canvas);

        Path socketPath = Files.createTempFile("jembetter-host-win32-modal-test-", ".sock");
        Files.delete(socketPath);

        CountDownLatch embedded = new CountDownLatch(1);
        socket.onClientEmbedded(embedded::countDown);
        socket.listen(socketPath);

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        Win32TestClients.waitForOwnWindow(clientPid);

        // Kept open (not try-with-resources) to stand in for a control
        // channel a real client would keep reading from - EmbedSocketWin32
        // itself doesn't require a live peer (see setModal's own Javadoc),
        // but this test wants to prove the byte is actually written on the
        // wire, not just that the call doesn't throw.
        SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, new AtomicReference<>());
        try {
            PidHandshake.send(channel, clientPid);
            assertTrue(embedded.await(5, TimeUnit.SECONDS), "client was never embedded via listen()");

            socket.setModal(true);
            assertControlFrame(readFrame(channel), ControlMessage.Type.MODALITY, true,
                    "setModal(true) did not write a MODALITY=true frame into the control channel");

            socket.setModal(false);
            assertControlFrame(readFrame(channel), ControlMessage.Type.MODALITY, false,
                    "setModal(false) did not write a MODALITY=false frame into the control channel");
        } finally {
            channel.close();
        }
    }

    @Test
    void aFocusRequestMarkerByteFromTheClientFocusesTheEmbeddedWindow() throws Exception {
        Canvas canvas = newVisibleHostCanvas();
        socket = new EmbedSocketWin32(canvas);

        Path socketPath = Files.createTempFile("jembetter-host-win32-focus-test-", ".sock");
        Files.delete(socketPath);

        CountDownLatch embedded = new CountDownLatch(1);
        socket.onClientEmbedded(embedded::countDown);
        socket.listen(socketPath);

        clientProcess = Win32TestClients.startFakeClientProcess();
        long clientPid = clientProcess.pid();
        long clientHwnd = Win32TestClients.waitForOwnWindow(clientPid);

        // Move focus elsewhere first, so the assertion below actually proves
        // the marker byte moved it, rather than it already having been there.
        Win32Focus.set(CanvasNativeHandle.extract(canvas));

        Win32FocusWatcher focusWatcher = new Win32FocusWatcher();
        try {
            CountDownLatch focused = new CountDownLatch(1);
            focusWatcher.watch(clientHwnd, gained -> {
                if (gained) {
                    focused.countDown();
                }
            });

            SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, new AtomicReference<>());
            try {
                PidHandshake.send(channel, clientPid);
                assertTrue(embedded.await(5, TimeUnit.SECONDS), "client was never embedded via listen()");

                ControlMessage.focusRequest().writeTo(channel);

                assertTrue(focused.await(5, TimeUnit.SECONDS),
                        "the embedded window was never focused after the client wrote a FOCUS_REQUEST frame");
            } finally {
                channel.close();
            }
        } finally {
            focusWatcher.close();
        }
    }

    @Test
    void setModalIsANoOpWithNothingEmbedded() {
        Canvas canvas = newVisibleHostCanvasQuiet();
        socket = new EmbedSocketWin32(canvas);

        assertDoesNotThrow(() -> socket.setModal(true), "setModal() must be a no-op when nothing is embedded");
    }

    private static byte[] readFrame(SocketChannel channel) throws IOException, InterruptedException {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new IllegalStateException("Control channel closed before a full frame arrived");
            }
            if (!buffer.hasRemaining()) {
                break;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Timed out waiting for a control frame");
            }
            Thread.sleep(20);
        }
        return buffer.array();
    }

    private static void assertControlFrame(byte[] frame, ControlMessage.Type type, boolean flag, String message) {
        assertEquals(type.code(), frame[0], message + " (type byte)");
        assertEquals((byte) (flag ? 1 : 0), frame[1], message + " (flag byte)");
    }

    private Canvas newVisibleHostCanvasQuiet() {
        try {
            return newVisibleHostCanvas();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private Canvas newVisibleHostCanvas() throws InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new JFrame("EmbedSocketWin32Test owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);
        return canvas;
    }
}
