package cz.loplex.jembetter.client;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import cz.loplex.jembetter.common.ipc.ControlMessage;
import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import cz.loplex.jembetter.core.win32.Win32WindowGeometry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link EmbedClientWin32} against a hand-rolled fake host — a raw
 * {@link ServerSocketChannel} playing exactly the part {@code
 * EmbedSocketWin32#listen} plays (accept, read the pid handshake, keep the
 * channel open, write a {@code ControlMessage} frame) — rather than
 * pulling in {@code jembetter-host} itself, which this module doesn't depend
 * on. Tagged {@code windows} like the rest of this module's Win32-backend
 * tests, even though the mechanism under test (a plain {@code AF_UNIX}
 * socket) has no Win32-specific API calls in it — it exists specifically to
 * pair with {@code EmbedSocketWin32}'s own control channel.
 */
@Tag("windows")
class EmbedClientWin32Test {

    private ServerSocketChannel server;
    private Path socketPath;
    private EmbedClientWin32 client;
    private JFrame frame;
    private long fakeHostHwnd = -1;
    private long opaqueClientHwnd = -1;

    @AfterEach
    void cleanup() throws IOException {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (socketPath != null) {
            Files.deleteIfExists(socketPath);
        }
        // Put anything still embedded in the fake host back on the desktop
        // BEFORE disposing the frame. Win32 destroys a window on its owning
        // thread, and disposing an AWT frame whose HWND is still a WS_CHILD of
        // a foreign window wedges AWT's event thread inside
        // EventQueue.invokeAndWait - which hangs this class outright, with no
        // failure and no output. A test that embeds the frame and does not
        // release it is the normal case here (a host detaching is a separate
        // behaviour, tested on its own), so the teardown has to undo it rather
        // than every test remembering to.
        if (fakeHostHwnd >= 0 && Win32TestWindow.exists(fakeHostHwnd)) {
            releaseChildrenOf(fakeHostHwnd);
        }
        if (frame != null) {
            frame.dispose();
        }
        if (fakeHostHwnd >= 0 && Win32TestWindow.exists(fakeHostHwnd)) {
            Win32TestWindow.destroy(fakeHostHwnd);
        }
        if (opaqueClientHwnd >= 0 && Win32TestWindow.exists(opaqueClientHwnd)) {
            Win32TestWindow.destroy(opaqueClientHwnd);
        }
    }

    /**
     * Reparents every direct child of {@code parent} back to the desktop.
     * Walks with {@code GW_CHILD} repeatedly rather than enumerating once:
     * each release removes that window from the parent's child list, so the
     * next call returns the following one. Bounded so a window that refuses to
     * detach can't spin the teardown forever.
     */
    private static void releaseChildrenOf(long parent) {
        HWND parentHandle = new HWND(new Pointer(parent));
        for (int guard = 0; guard < 16; guard++) {
            HWND child = User32.INSTANCE.GetWindow(parentHandle, new DWORD(WinUser.GW_CHILD));
            if (child == null) {
                return;
            }
            Win32Reparent.release(Pointer.nativeValue(child.getPointer()), 0, 0);
        }
    }

    /** {@link EmbedClient#create} dispatches to the Win32 implementation on Windows. */
    @Test
    void factoryReturnsTheWin32ImplementationOnThisPlatform() {
        client = assertInstanceOf(EmbedClientWin32.class, EmbedClient.create());
    }

    @Test
    void receivesTheModalityOpcodesTheHostWrites() throws Exception {
        socketPath = Files.createTempFile("jembetter-client-win32-modal-test-", ".sock");
        Files.delete(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));

        CountDownLatch pidReceived = new CountDownLatch(1);
        AtomicBoolean firstOpcode = new AtomicBoolean();
        AtomicBoolean secondOpcode = new AtomicBoolean();
        CountDownLatch firstDelivered = new CountDownLatch(1);
        CountDownLatch secondDelivered = new CountDownLatch(1);

        Thread fakeHost = new Thread(() -> {
            try (SocketChannel accepted = server.accept()) {
                PidHandshake.receive(accepted);
                pidReceived.countDown();

                ControlMessage.of(ControlMessage.Type.MODALITY, true).writeTo(accepted);
                Thread.sleep(200); // give the reader a chance to dispatch before the next frame
                ControlMessage.of(ControlMessage.Type.MODALITY, false).writeTo(accepted);
                Thread.sleep(500); // keep the channel open past the last assertion
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "fake-host");
        fakeHost.setDaemon(true);
        fakeHost.start();

        client = new EmbedClientWin32();
        client.onModalityChanged(modal -> {
            if (firstDelivered.getCount() > 0) {
                firstOpcode.set(modal);
                firstDelivered.countDown();
            } else {
                secondOpcode.set(modal);
                secondDelivered.countDown();
            }
        });
        client.connect(socketPath);

        assertTrue(pidReceived.await(5, TimeUnit.SECONDS), "the fake host never received the pid handshake");
        assertTrue(firstDelivered.await(5, TimeUnit.SECONDS), "onModalityChanged never fired for the first opcode");
        assertTrue(firstOpcode.get(), "the first opcode (1) was not decoded as modal=true");
        assertTrue(secondDelivered.await(5, TimeUnit.SECONDS), "onModalityChanged never fired for the second opcode");
        assertFalse(secondOpcode.get(), "the second opcode (0) was not decoded as modal=false");
    }

    @Test
    void connectSendsThePidHandshake() throws Exception {
        socketPath = Files.createTempFile("jembetter-client-win32-handshake-test-", ".sock");
        Files.delete(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));

        long expectedPid = ProcessHandle.current().pid();
        CompletableFuture<Long> received = CompletableFuture.supplyAsync(() -> {
            try (SocketChannel accepted = server.accept()) {
                return PidHandshake.receive(accepted);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        client = new EmbedClientWin32();
        client.connect(socketPath);

        assertEquals(expectedPid, received.get(5, TimeUnit.SECONDS), "connect() did not send this process's own pid");
    }

    @Test
    void announceResolvesAndDetectsBeingReparentedByAHost() throws InterruptedException {
        frame = new JFrame("EmbedClientWin32Test announce");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch embedded = new CountDownLatch(1);
        AtomicLong reportedEmbedderWindow = new AtomicLong(-1);
        client = new EmbedClientWin32();
        client.onEmbedded(id -> {
            reportedEmbedderWindow.set(id);
            embedded.countDown();
        });
        client.announce();

        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host");
        Win32Reparent.reparent(ownHwnd, fakeHostHwnd, 0, 0);

        assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after announce()");
        assertEquals(fakeHostHwnd, reportedEmbedderWindow.get());
    }

    @Test
    void onHostDetachedFiresWhenReleasedBackToTheDesktop() throws InterruptedException {
        frame = new JFrame("EmbedClientWin32Test detach");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch embedded = new CountDownLatch(1);
        CountDownLatch detached = new CountDownLatch(1);
        client = new EmbedClientWin32();
        client.onEmbedded(id -> embedded.countDown());
        client.onHostDetached(detached::countDown);
        client.announce();

        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host (detach)");
        Win32Reparent.reparent(ownHwnd, fakeHostHwnd, 0, 0);
        assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked before the release");

        Win32Reparent.release(ownHwnd, 0, 0);

        assertTrue(detached.await(5, TimeUnit.SECONDS), "onHostDetached was never invoked after the release");
    }

    @Test
    void onFocusChangedIsInvokedWhenFocusMovesToTheWatchedWindow() throws InterruptedException {
        frame = new JFrame("EmbedClientWin32Test focus");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch gained = new CountDownLatch(1);
        client = new EmbedClientWin32();
        client.onFocusChanged(focused -> {
            if (focused) {
                gained.countDown();
            }
        });
        client.announce();

        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        Win32Focus.set(ownHwnd);

        assertTrue(gained.await(5, TimeUnit.SECONDS), "onFocusChanged(true) was never invoked after SetFocus");
    }

    @Test
    void onResizedIsInvokedAfterAResize() throws InterruptedException {
        frame = new JFrame("EmbedClientWin32Test resize");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch resized = new CountDownLatch(1);
        AtomicInteger reportedWidth = new AtomicInteger(-1);
        AtomicInteger reportedHeight = new AtomicInteger(-1);
        client = new EmbedClientWin32();
        // The embed below is itself a size change, and the watch is already
        // armed by then, so the callback fires for it too. Both reports are
        // truthful; this test is about the resize that follows, so latch on
        // the size it asks for rather than on whichever arrives first.
        client.onResized((width, height) -> {
            reportedWidth.set(width);
            reportedHeight.set(height);
            if (width == 200 && height == 150) {
                resized.countDown();
            }
        });
        client.announce();

        // Embed at a known size first, the way a host does it in one call, so
        // what follows is a resize of an already-embedded window rather than
        // the embed's own geometry change. Reparenting without a size and
        // resizing afterwards would report the undecorated window's own size
        // in between - a real transition, but not the one under test.
        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host (resize)");
        Win32Reparent.reparent(ownHwnd, fakeHostHwnd, 0, 0, 120, 90);
        Win32WindowGeometry.moveResize(ownHwnd, 0, 0, 200, 150);

        assertTrue(resized.await(5, TimeUnit.SECONDS), "onResized was never invoked after the resize");
        assertEquals(200, reportedWidth.get());
        assertEquals(150, reportedHeight.get());
    }

    @Test
    void requestFocusWritesAFocusRequestFrameToTheControlChannel() throws Exception {
        frame = new JFrame("EmbedClientWin32Test request-focus");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        socketPath = Files.createTempFile("jembetter-client-win32-focus-test-", ".sock");
        Files.delete(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));

        CompletableFuture<ControlMessage.Type> received = CompletableFuture.supplyAsync(() -> {
            try (SocketChannel accepted = server.accept()) {
                PidHandshake.receive(accepted);
                ControlMessage message = ControlMessage.readFrom(accepted);
                if (message == null) {
                    throw new IllegalStateException("Peer closed before writing the focus-request frame");
                }
                return message.type();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        client = new EmbedClientWin32();
        client.announce();
        client.connect(socketPath);
        client.requestFocus();

        assertEquals(ControlMessage.Type.FOCUS_REQUEST, received.get(5, TimeUnit.SECONDS),
                "requestFocus() did not write a FOCUS_REQUEST control frame");
    }

    /**
     * The toolkit-opaque handoff: a client that already holds its own window
     * handle watches it directly, with no pid lookup and no socket, and still
     * sees the whole embed lifecycle — what {@code EmbedSocketWin32#embedOpaque}
     * pairs with on the host side.
     *
     * <p>The watched window is a plain {@link Win32TestWindow}, not a {@link
     * JFrame} like the {@link EmbedClientWin32#announce} tests above use. That
     * matches what the path is for — a window a foreign toolkit owns, which
     * AWT knows nothing about — and it avoids a source of flakiness that has
     * nothing to do with the code under test: under Wine the desktop shell
     * reparents a decorated top-level window into a frame of its own shortly
     * after it becomes visible, which arrives as an embed and consumes the
     * one-shot gate in {@code handleParentChanged} before the host's own
     * {@code SetParent} lands. Measured at 3 failures in 10 runs with a
     * {@code JFrame} here, reporting a shell window's handle in place of the
     * fake host's; a {@code STATIC} window is not decorated and the shell
     * leaves it alone.
     */
    @Test
    void watchOwnWindowDetectsBeingEmbeddedAndReleasedWithoutAnnouncing() throws InterruptedException {
        opaqueClientHwnd = Win32TestWindow.create("EmbedClientWin32Test opaque client");

        CountDownLatch embedded = new CountDownLatch(1);
        CountDownLatch detached = new CountDownLatch(1);
        AtomicLong reportedEmbedderWindow = new AtomicLong(-1);
        client = new EmbedClientWin32();
        client.onEmbedded(id -> {
            reportedEmbedderWindow.set(id);
            embedded.countDown();
        });
        client.onHostDetached(detached::countDown);
        client.watchOwnWindow(opaqueClientHwnd);

        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host (watch-own-window)");
        Win32Reparent.reparent(opaqueClientHwnd, fakeHostHwnd, 0, 0);
        assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after watchOwnWindow()");
        assertEquals(fakeHostHwnd, reportedEmbedderWindow.get());

        Win32Reparent.release(opaqueClientHwnd, 0, 0);
        assertTrue(detached.await(5, TimeUnit.SECONDS), "onHostDetached was never invoked after the release");
    }

    /**
     * A client with a control channel is told about a second embed, not only
     * its first.
     *
     * <p>The reparent alone cannot say whether it was an embed — a desktop
     * shell reparents an ordinary top-level window into a frame of its own —
     * so {@link EmbedClientWin32#onEmbedded} accepts the first reparent after
     * the window is watched and filters the rest. That filter also discards a
     * genuine re-embed after a host releases the client, which a host can do
     * through {@code EmbedSocketWin32#detachClient()} followed by another
     * embed. A {@link ControlMessage.Type#EMBEDDED} frame supplies the bit
     * the filter was standing in for, and this asserts it arrives and is
     * acted on.
     *
     * <p>The second embed is the whole point: the first one would be reported
     * by the reparent watcher with no frame involved at all. Watch which
     * assertion fails before concluding the frame is broken.
     *
     * <p>Also asserts <strong>exactly two</strong> callbacks, because the
     * frame and the reparent are seen on different threads and either can win.
     * Whichever arrives first reports; the other must not report the same
     * embedder again. A test that only counted "at least two" would pass
     * while double-reporting every embed.
     *
     * <p>Uses {@link Win32TestWindow} rather than the {@link JFrame} the
     * announce-path tests use, for two reasons: it is the window kind a shell
     * leaves alone (see {@code watchOwnWindowDetectsBeingEmbeddedAndReleased…}),
     * and {@link EmbedClientWin32#watchOwnWindow} plus {@link
     * EmbedClientWin32#connect} is the only combination that watches a
     * non-AWT window <em>and</em> opens a control channel.
     */
    @Test
    void aSecondEmbedIsReportedWhenTheHostSaysSoOnTheControlChannel() throws Exception {
        opaqueClientHwnd = Win32TestWindow.create("EmbedClientWin32Test re-embed client");
        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host (re-embed)");

        socketPath = Files.createTempFile("jembetter-client-win32-reembed-test-", ".sock");
        Files.delete(socketPath);
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));

        CountDownLatch firstEmbed = new CountDownLatch(1);
        CountDownLatch detached = new CountDownLatch(1);
        CountDownLatch secondEmbed = new CountDownLatch(2);
        AtomicInteger embedCallbacks = new AtomicInteger();
        AtomicLong lastEmbedder = new AtomicLong(-1);

        // Only the socket frames run on this thread. Every window operation
        // stays on the test thread, which created both windows: a cross-thread
        // SetParent sends WM_WINDOWPOSCHANGING to the owning thread and blocks
        // until it pumps, and this test's own thread is sitting on a latch
        // rather than pumping. The tests above reparent from the test thread
        // for the same reason.
        CountDownLatch pidReceived = new CountDownLatch(1);
        CountDownLatch sendFirstFrame = new CountDownLatch(1);
        CountDownLatch sendSecondFrame = new CountDownLatch(1);
        Thread fakeHost = new Thread(() -> {
            try (SocketChannel accepted = server.accept()) {
                PidHandshake.receive(accepted);
                pidReceived.countDown();

                if (sendFirstFrame.await(5, TimeUnit.SECONDS)) {
                    ControlMessage.embedded().writeTo(accepted);
                }
                if (sendSecondFrame.await(10, TimeUnit.SECONDS)) {
                    ControlMessage.embedded().writeTo(accepted);
                }
                // Hold the channel open past the last assertion; closing it
                // here would be indistinguishable from the host dying.
                Thread.sleep(2000);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "fake-host-reembed");
        fakeHost.setDaemon(true);
        fakeHost.start();

        client = new EmbedClientWin32();
        client.onEmbedded(id -> {
            embedCallbacks.incrementAndGet();
            lastEmbedder.set(id);
            firstEmbed.countDown();
            secondEmbed.countDown();
        });
        client.onHostDetached(detached::countDown);
        client.watchOwnWindow(opaqueClientHwnd);
        client.connect(socketPath);
        assertTrue(pidReceived.await(5, TimeUnit.SECONDS), "the fake host never received the pid handshake");

        Win32Reparent.reparent(opaqueClientHwnd, fakeHostHwnd, 0, 0);
        sendFirstFrame.countDown();
        assertTrue(firstEmbed.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked for the first embed");

        Win32Reparent.release(opaqueClientHwnd, 0, 0);
        assertTrue(detached.await(5, TimeUnit.SECONDS), "onHostDetached was never invoked after the release");

        Win32Reparent.reparent(opaqueClientHwnd, fakeHostHwnd, 0, 0);
        sendSecondFrame.countDown();
        assertTrue(secondEmbed.await(5, TimeUnit.SECONDS),
                "onEmbedded was not invoked a second time after the host re-embedded the client and said so "
                        + "on the control channel; the reparent filter discarded it and the EMBEDDED frame "
                        + "did not override that");
        assertEquals(fakeHostHwnd, lastEmbedder.get(), "the second embed reported the wrong embedder handle");

        // Nothing else may arrive: the frame and the reparent race, and only
        // one of them may report each embed.
        Thread.sleep(500);
        assertEquals(2, embedCallbacks.get(),
                "onEmbedded fired more than twice for two embeds - the EMBEDDED frame and the reparent "
                        + "watcher both reported the same one");
    }

    /**
     * {@link EmbedClientWin32#onResized} on the {@link
     * EmbedClientWin32#watchOwnWindow} path — how a toolkit-opaque client
     * learns its on-screen size once the host resizes it, with no handshake
     * to carry the geometry instead.
     */
    @Test
    void watchOwnWindowInvokesOnResizedAfterAResize() throws InterruptedException {
        frame = new JFrame("EmbedClientWin32Test watch-own-window resize");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);
        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());

        CountDownLatch resized = new CountDownLatch(1);
        AtomicInteger reportedWidth = new AtomicInteger(-1);
        AtomicInteger reportedHeight = new AtomicInteger(-1);
        client = new EmbedClientWin32();
        // The embed below is itself a size change, and the watch is already
        // armed by then, so the callback fires for it too. Both reports are
        // truthful; this test is about the resize that follows, so latch on
        // the size it asks for rather than on whichever arrives first.
        client.onResized((width, height) -> {
            reportedWidth.set(width);
            reportedHeight.set(height);
            if (width == 200 && height == 150) {
                resized.countDown();
            }
        });
        client.watchOwnWindow(ownHwnd);

        // Embed at a known size first, for the same reason
        // onResizedIsInvokedAfterAResize does.
        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host (watch-own-window resize)");
        Win32Reparent.reparent(ownHwnd, fakeHostHwnd, 0, 0, 120, 90);
        Win32WindowGeometry.moveResize(ownHwnd, 0, 0, 200, 150);

        assertTrue(resized.await(5, TimeUnit.SECONDS), "onResized was never invoked after the resize");
        assertEquals(200, reportedWidth.get());
        assertEquals(150, reportedHeight.get());
    }

    private static long waitForOwnWindow(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            //noinspection BusyWait
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Client process " + pid + " never published a top-level window");
    }
}
