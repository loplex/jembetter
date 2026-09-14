package cz.loplex.jembetter.host;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import cz.loplex.jembetter.common.CanvasNativeHandle;
import cz.loplex.jembetter.common.ipc.ControlMessage;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32FocusWatcher;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
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
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final Duration FOCUS_TIMEOUT = Duration.ofSeconds(5);
    private static final long FOCUS_RETRY_INTERVAL_MILLIS = 250;
    private static final long FOCUS_POLL_INTERVAL_MILLIS = 25;

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
        try (SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, new AtomicReference<>())) {
            PidHandshake.send(channel, clientPid);
            assertTrue(embedded.await(5, TimeUnit.SECONDS), "client was never embedded via listen()");

            socket.setModal(true);
            assertControlFrame(readFrame(channel), ControlMessage.Type.MODALITY, true,
                    "setModal(true) did not write a MODALITY=true frame into the control channel");

            socket.setModal(false);
            assertControlFrame(readFrame(channel), ControlMessage.Type.MODALITY, false,
                    "setModal(false) did not write a MODALITY=false frame into the control channel");
        }
    }

    /**
     * A {@link ControlMessage.Type#FOCUS_REQUEST} frame written by the client
     * moves Win32 input focus onto its embedded window.
     *
     * <p>Embedding focuses the client by itself - {@code
     * Win32EmbedCore.reparentAndWatch} grants focus to the window it has just
     * reparented - so the frame has nothing left to prove unless focus is
     * taken back from the client first. {@link #parkFocusOnHost} does that
     * and waits for the watcher to confirm it, which is what makes the last
     * assertion a statement about the marker byte rather than about the
     * embed that preceded it.
     *
     * <p>Everything else here is written against Windows' foreground lock,
     * which makes every focus grant by a process that isn't the foreground
     * one a silent no-op (see {@link Win32Focus} for the measurements behind
     * that). That has two consequences for a test of a focus grant:
     *
     * <ul>
     *   <li>It has to <em>hold</em> the foreground before it can measure
     *       anything, so the grant this asserts on is not disqualified before
     *       it is made. Until {@link #takeForeground} confirmed it, a run
     *       that started in the background reported a defect in the host
     *       rather than a machine the assertion could not be made on.</li>
     *   <li>A single frame gives the host a single attempt, at whatever
     *       instant it lands. The request is one-way and best-effort by
     *       design (see {@code EmbedSocketWin32}'s own control-channel
     *       reader), so the test has to keep asking for as long as it is
     *       willing to wait, rather than ask once and then wait passively -
     *       which is what {@link #focusRequestedUntilFocused} does.</li>
     * </ul>
     *
     * <p>Both were flakiness, measured: on real Windows this test failed 4 of
     * 60 full-suite iterations as a single-shot request, the only test to
     * fail in 5,600 executions, and never under Wine - which does not
     * implement the foreground lock at all. It failed 0 of 60 once they were
     * addressed.
     */
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
        // the marker byte moved it, rather than it already having been there
        // - and confirm that landed, which is also this process taking the
        // foreground it needs to hold for the rest of the test. The client
        // process just showed a top-level window of its own, so the
        // foreground at this point belongs to it, not to this process.
        long canvasHwnd = CanvasNativeHandle.extract(canvas);
        assertTrue(takeForeground(canvasHwnd, FOCUS_TIMEOUT),
                "another process held the foreground throughout, so no focus grant made from here could take effect"
                        + foregroundDescription());

        try (Win32FocusWatcher focusWatcher = new Win32FocusWatcher()) {
            // The watcher reports transitions; this keeps the latest one, so
            // the two waits below can ask for a state rather than for an
            // edge - the second of them needs the client to have been seen
            // unfocused before it means anything.
            AtomicBoolean clientFocused = new AtomicBoolean(false);
            focusWatcher.watch(clientHwnd, clientFocused::set);

            try (SocketChannel channel = Win32TestClients.connectWhenReady(socketPath, new AtomicReference<>())) {
                PidHandshake.send(channel, clientPid);
                assertTrue(embedded.await(5, TimeUnit.SECONDS), "client was never embedded via listen()");

                assertTrue(parkFocusOnHost(canvasHwnd, clientFocused, FOCUS_TIMEOUT),
                        "focus never left the embedded client, so what the FOCUS_REQUEST frame does could not be told"
                                + " apart from what embedding already did"
                                + foregroundDescription() + embeddedClientDescription(clientHwnd, canvasHwnd));

                assertTrue(focusRequestedUntilFocused(channel, clientFocused, canvasHwnd, FOCUS_TIMEOUT),
                        "the embedded window was never focused after the client wrote a FOCUS_REQUEST frame"
                                + foregroundDescription() + embeddedClientDescription(clientHwnd, canvasHwnd));
            }
        }
    }

    @Test
    void setModalIsANoOpWithNothingEmbedded() {
        Canvas canvas = newVisibleHostCanvasQuiet();
        socket = new EmbedSocketWin32(canvas);

        assertDoesNotThrow(() -> socket.setModal(true), "setModal() must be a no-op when nothing is embedded");
    }

    /**
     * Focuses {@code hwnd} until this process owns the foreground window, and
     * reports whether it got there within {@code timeout}.
     *
     * <p>Retried rather than granted once and trusted: {@link Win32Focus#set}
     * has to work around the foreground lock through {@code
     * AttachThreadInput}, which attaches to whichever window holds the
     * foreground at that instant - so a window on its way out, or a
     * foreground of {@code NULL} mid-switch, costs that one attempt and
     * nothing else. A whole test built on top of one such attempt is a
     * measurement of that instant.
     */
    private static boolean takeForeground(long hwnd, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            // The frame, then the canvas inside it: only a top-level window
            // can be the foreground one, and it is the canvas that has to
            // end up holding focus.
            Win32Focus.set(topLevelOf(hwnd));
            Win32Focus.set(hwnd);
            if (foregroundIsNotAnotherProcess()) {
                return true;
            }
            Thread.sleep(FOCUS_RETRY_INTERVAL_MILLIS);
        } while (System.nanoTime() < deadline);
        return foregroundIsNotAnotherProcess();
    }

    /** The top-level window {@code hwnd} lives in — an embedded child's is the host's own frame. */
    private static long topLevelOf(long hwnd) {
        HWND root = User32.INSTANCE.GetAncestor(new HWND(new Pointer(hwnd)), WinUser.GA_ROOT);
        return root == null ? hwnd : Pointer.nativeValue(root.getPointer());
    }

    /**
     * Takes focus off the embedded client and back onto the host canvas,
     * and reports whether the watcher saw it leave within {@code timeout}.
     *
     * <p>This is the step that makes the assertion after it discriminating.
     * Embedding grants the client focus on its own, so a watcher armed
     * before the embed has already reported the client focused by the time
     * any {@code FOCUS_REQUEST} frame is written - and a test that only
     * waits for "focused" would pass identically with the frame never sent.
     */
    private static boolean parkFocusOnHost(long hostHwnd, AtomicBoolean clientFocused, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            Win32Focus.set(topLevelOf(hostHwnd));
            Win32Focus.set(hostHwnd);
            if (awaitFocusState(clientFocused, false, FOCUS_RETRY_INTERVAL_MILLIS)) {
                return true;
            }
        } while (System.nanoTime() < deadline);
        return !clientFocused.get();
    }

    /**
     * Writes {@link ControlMessage#focusRequest()} frames until the watcher
     * reports the client focused or {@code timeout} runs out, reclaiming the
     * foreground for {@code hostHwnd} first whenever something else has
     * taken it.
     *
     * <p>Reclaiming is not the test focusing the window it is asserting on:
     * it points focus at the <em>host</em> canvas, which is where this test
     * parks it to begin with. It only restores the precondition every
     * request needs - that the grant the host is about to make comes from
     * the foreground process. An embedded client is a {@code WS_CHILD} of
     * that canvas, so the foreground window stays this process's own frame
     * while the client holds focus, and a passing run never reclaims
     * anything.
     */
    private static boolean focusRequestedUntilFocused(SocketChannel channel, AtomicBoolean clientFocused,
            long hostHwnd, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            if (!foregroundIsNotAnotherProcess()) {
                Win32Focus.set(topLevelOf(hostHwnd));
                Win32Focus.set(hostHwnd);
            }
            ControlMessage.focusRequest().writeTo(channel);
            if (awaitFocusState(clientFocused, true, FOCUS_RETRY_INTERVAL_MILLIS)) {
                return true;
            }
        } while (System.nanoTime() < deadline);
        return clientFocused.get();
    }

    /** Waits up to {@code timeoutMillis} for the watcher's last report to read {@code wanted}. */
    private static boolean awaitFocusState(AtomicBoolean state, boolean wanted, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (state.get() != wanted) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(FOCUS_POLL_INTERVAL_MILLIS);
        }
        return true;
    }

    /**
     * Whether the foreground is in a state a focus grant from here can
     * survive — this process holding it, or no window holding it at all.
     *
     * <p>No foreground window is deliberately not a failure. What disqualifies
     * this test is <em>another</em> process holding the foreground, because
     * that is what makes a grant from here a silent no-op; with no foreground
     * window there is no lock owner to enforce anything, and the grant lands.
     * Treating the empty state as hostile cost a Wine run that would otherwise
     * have passed: {@code GetForegroundWindow} returned null for the whole
     * five seconds and the test reported a machine it could not measure on,
     * over a machine that was fine.
     */
    private static boolean foregroundIsNotAnotherProcess() {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            return true;
        }
        long hwnd = Pointer.nativeValue(foreground.getPointer());
        return Win32WindowFinder.pidOfWindow(hwnd) == ProcessHandle.current().pid();
    }

    /** Names the window holding the foreground, so a focus failure says whose machine state it lost to. */
    private static String foregroundDescription() {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        if (foreground == null) {
            return " (nothing held the foreground; this test is pid " + ProcessHandle.current().pid() + ")";
        }
        long hwnd = Pointer.nativeValue(foreground.getPointer());
        return " (foreground: " + Win32WindowFinder.describeWindow(hwnd)
                + " pid=" + Win32WindowFinder.pidOfWindow(hwnd)
                + "; this test is pid " + ProcessHandle.current().pid() + ")";
    }

    /** Tells a focus failure apart from an embed that had already come undone by then. */
    private static String embeddedClientDescription(long clientHwnd, long canvasHwnd) {
        return " (client: " + Win32WindowFinder.describeWindow(clientHwnd)
                + " parent=0x" + Long.toHexString(Win32Reparent.parentOf(clientHwnd))
                + ", host canvas=0x" + Long.toHexString(canvasHwnd) + ")";
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
            //noinspection BusyWait
            Thread.sleep(20);
        }
        return buffer.array();
    }

    @SuppressWarnings("SameParameterValue")
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
