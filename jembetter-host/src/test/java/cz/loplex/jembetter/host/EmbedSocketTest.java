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

import javax.swing.JFrame;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Frame;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedSocket#listen}'s accept loop against a real window
 * manager: embeds a fake client, "crashes" it (disposing an AWT peer really
 * does destroy its X11 window, generating the same DestroyNotify a killed
 * process would), and confirms a second fake client can embed on the same
 * socket afterward without restarting the host.
 */
@EnabledIfEnvironmentVariable(named = "DISPLAY", matches = ".+")
class EmbedSocketTest {

    private Frame owner;
    private EmbedSocket socket;
    private JFrame client1;
    private JFrame client2;

    @AfterEach
    void cleanup() {
        if (socket != null) {
            socket.close();
        }
        if (client1 != null) {
            client1.dispose();
        }
        if (client2 != null) {
            client2.dispose();
        }
        if (owner != null) {
            owner.dispose();
        }
    }

    @Test
    void reEmbedsANewClientAfterThePreviousOneDetaches() throws IOException, InterruptedException {
        owner = new Frame("EmbedSocketTest owner");
        socket = new EmbedSocket(owner);
        socket.open(0, 0, 100, 100);

        Path socketPath = Files.createTempFile("jembetter-host-test-", ".sock");
        Files.delete(socketPath);

        CountDownLatch firstEmbed = new CountDownLatch(1);
        CountDownLatch detached = new CountDownLatch(1);
        socket.onClientEmbedded(firstEmbed::countDown);
        socket.onClientDetached(detached::countDown);

        socket.listen(socketPath);

        long pid = ProcessHandle.current().pid();
        FakeClient fake1 = offerFakeClient(socketPath, pid);
        client1 = fake1.frame();
        assertTrue(firstEmbed.await(5, TimeUnit.SECONDS), "first client was never embedded");

        client1.dispose();
        assertTrue(detached.await(5, TimeUnit.SECONDS), "first client's detach was never noticed");
        // The window manager prunes its own client list asynchronously,
        // slightly after (and independently of) the DestroyNotify the host
        // itself reacts to above; without waiting for that too, the second
        // client's window lookup (also by this same test JVM's pid) could
        // race and momentarily see both windows.
        try (X11Display probe = X11Display.open(null)) {
            waitForNoOwnWindow(probe, pid);
        }

        CountDownLatch reEmbedded = new CountDownLatch(1);
        socket.onClientEmbedded(reEmbedded::countDown);
        client2 = offerFakeClient(socketPath, pid).frame();
        assertTrue(reEmbedded.await(5, TimeUnit.SECONDS), "second client was never (re-)embedded on the same socket");
    }

    @Test
    void detachClientReleasesTheWindowWithoutFiringOnClientDetached() throws IOException, InterruptedException {
        owner = new Frame("EmbedSocketTest owner");
        socket = new EmbedSocket(owner);
        socket.open(0, 0, 100, 100);

        Path socketPath = Files.createTempFile("jembetter-host-test-", ".sock");
        Files.delete(socketPath);

        CountDownLatch firstEmbed = new CountDownLatch(1);
        CountDownLatch detached = new CountDownLatch(1);
        socket.onClientEmbedded(firstEmbed::countDown);
        socket.onClientDetached(detached::countDown);

        socket.listen(socketPath);

        long pid = ProcessHandle.current().pid();
        client1 = offerFakeClient(socketPath, pid).frame();
        assertTrue(firstEmbed.await(5, TimeUnit.SECONDS), "client was never embedded");

        socket.detachClient();

        try (X11Display probe = X11Display.open(null)) {
            // Throws if the released window never becomes visible to the
            // window manager as an ordinary top-level window again.
            waitForOwnWindow(probe, pid);
        }
        assertFalse(detached.await(500, TimeUnit.MILLISECONDS), "onClientDetached fired for a voluntary detach");

        client1.dispose();
        // The earlier detachClient() call already unwatched this window;
        // disposing it now must not retroactively fire onClientDetached.
        assertFalse(detached.await(500, TimeUnit.MILLISECONDS),
                "onClientDetached fired after disposing an already-detached window");

        CountDownLatch reEmbedded = new CountDownLatch(1);
        socket.onClientEmbedded(reEmbedded::countDown);
        client2 = offerFakeClient(socketPath, pid).frame();
        assertTrue(reEmbedded.await(5, TimeUnit.SECONDS),
                "a new client could not be embedded after the voluntary detach");
    }

    /**
     * Regression test for the z-order bug reported against the previous,
     * override-redirect-based socket window: a heavyweight host popup could
     * end up underneath the embedded client because the socket was a
     * root-level sibling of the host's own window, not a descendant of it.
     * {@link EmbedSocket#open(Canvas)} fixes this by reparenting the client
     * as a genuine X11 child of the host's placeholder canvas — asserted
     * here structurally via {@code XQueryTree}, since that parent/child
     * relationship (not any particular pixel-level rendering outcome) is
     * what guarantees correct stacking regardless of window manager.
     *
     * <p>The fake client here runs as a genuinely separate JVM process
     * (rather than this test's own in-process {@code JFrame}, as the other
     * tests in this class use): an in-process AWT client shares this test
     * JVM's own AWT toolkit state with the host, which was found to make
     * the window manager immediately re-adopt the reparented window as a
     * fresh top-level client — never observed with two real, separate
     * processes (matching how this library is actually used).
     */
    @Test
    void embedsIntoACanvasAsARealX11ChildOfIt() throws IOException, InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("EmbedSocketTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socket = new EmbedSocket(owner);
        socket.open(canvas);

        Path socketPath = Files.createTempFile("jembetter-host-test-", ".sock");
        Files.delete(socketPath);

        CountDownLatch firstEmbed = new CountDownLatch(1);
        socket.onClientEmbedded(firstEmbed::countDown);
        socket.listen(socketPath);

        Process clientProcess = startFakeClientProcess();
        try {
            long clientPid = clientProcess.pid();
            long clientWindowId;
            try (X11Display display = X11Display.open(null)) {
                clientWindowId = waitForOwnWindow(display, clientPid);
                XEmbedInfoProperty.write(display.raw(), clientWindowId,
                        new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
            }

            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(address);
                PidHandshake.send(channel, clientPid);
            }

            assertTrue(firstEmbed.await(5, TimeUnit.SECONDS), "client was never embedded");

            long canvasWindowId = CanvasNativeHandle.extract(canvas);
            try (X11Display display = X11Display.open(null)) {
                assertTrue(isDescendantOf(display, clientWindowId, canvasWindowId),
                        "embedded client window is not a genuine X11 descendant of the host canvas");
            }
        } finally {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Regression coverage for the known-handle path added alongside {@link
     * EmbedSocket#embedOpaque}: a host that already knows a client's pid
     * directly (e.g. one it spawned itself) can embed it via {@link
     * EmbedSocket#embed(long)} with no {@code listen()}/socket rendezvous at
     * all, as long as the client has published {@code _XEMBED_INFO} itself —
     * the same precondition a socket-handshaking client meets via {@code
     * jembetter-client.EmbedClient#announce}.
     */
    @Test
    void embedsAKnownClientPidWithoutASocketHandshake() throws IOException, InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("EmbedSocketTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socket = new EmbedSocket(owner);
        socket.open(canvas);

        Process clientProcess = startFakeClientProcess();
        try {
            long clientPid = clientProcess.pid();
            long clientWindowId;
            try (X11Display display = X11Display.open(null)) {
                clientWindowId = waitForOwnWindow(display, clientPid);
                XEmbedInfoProperty.write(display.raw(), clientWindowId,
                        new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
            }

            socket.embed(clientPid);

            long canvasWindowId = CanvasNativeHandle.extract(canvas);
            try (X11Display display = X11Display.open(null)) {
                assertTrue(isDescendantOf(display, clientWindowId, canvasWindowId),
                        "known-handle embed did not reparent the client under the host canvas");
            }
        } finally {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Regression coverage for {@link EmbedSocket#embedOpaque}: a client that
     * never writes {@code _XEMBED_INFO} itself (standing in for a toolkit-
     * opaque client whose native connection this process can't read events
     * on, e.g. JavaFX Glass) still ends up genuinely reparented under the
     * host canvas, because {@code embedOpaque} writes the property on the
     * client's behalf and poll-verifies the reparent via {@code
     * XQueryTree} instead of trusting any cooperative signal from it.
     */
    @Test
    void embedsAToolkitOpaqueClientThatNeverPublishesXEmbedInfoItself() throws IOException, InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("EmbedSocketTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socket = new EmbedSocket(owner);
        socket.open(canvas);

        Process clientProcess = startFakeClientProcess();
        try {
            long clientPid = clientProcess.pid();
            long clientWindowId;
            try (X11Display display = X11Display.open(null)) {
                clientWindowId = waitForOwnWindow(display, clientPid);
            }

            socket.embedOpaque(clientWindowId, Duration.ofMillis(20), 100);

            long canvasWindowId = CanvasNativeHandle.extract(canvas);
            try (X11Display display = X11Display.open(null)) {
                assertTrue(isDescendantOf(display, clientWindowId, canvasWindowId),
                        "embedOpaque did not reparent the non-cooperative client under the host canvas");
            }
        } finally {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Regression coverage for a real bug hit embedding a real JavaFX/Glass
     * child into a host whose placeholder {@code Canvas} was still at its
     * default {@code 0x0} size when {@link EmbedSocket#open(Canvas)} ran
     * (e.g. because it lives in a not-yet-active {@code CardLayout} card):
     * {@link cz.loplex.jembetter.core.x11.RawWindow#createChild} used to
     * pass that size straight through to {@code XCreateWindow}, which X11
     * rejects with {@code BadValue} for a zero width or height — Xlib still
     * hands back a client-side-allocated window id regardless, so the
     * socket window {@code EmbedSocket} believed it owned never actually
     * existed on the server. Every later operation against it silently
     * failed, including reparenting a client into it, so {@code
     * embedOpaque} would poll until it gave up and threw, and — because
     * {@link cz.loplex.jembetter.core.x11.Reparenting#reparent} still went
     * on to map the client window regardless of whether the reparent
     * itself took effect — the window manager would immediately re-adopt
     * the client as an ordinary top-level window again.
     */
    @Test
    void embedsAToolkitOpaqueClientIntoAHostCanvasThatWasNotYetLaidOut() throws IOException, InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("EmbedSocketTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);
        // Forces the exact bug condition regardless of layout/WM specifics:
        // a displayable Canvas (native peer already exists) shrunk back to
        // its default 0x0 size, as if its container hadn't laid it out yet.
        canvas.setSize(0, 0);

        socket = new EmbedSocket(owner);
        socket.open(canvas);

        Process clientProcess = startFakeClientProcess();
        try {
            long clientPid = clientProcess.pid();
            long clientWindowId;
            try (X11Display display = X11Display.open(null)) {
                clientWindowId = waitForOwnWindow(display, clientPid);
            }

            socket.embedOpaque(clientWindowId, Duration.ofMillis(20), 100);

            long canvasWindowId = CanvasNativeHandle.extract(canvas);
            try (X11Display display = X11Display.open(null)) {
                assertTrue(isDescendantOf(display, clientWindowId, canvasWindowId),
                        "embedOpaque did not reparent the client under a host canvas that hadn't been laid out yet");
            }
        } finally {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Regression coverage for a real bug found while building the
     * auto-cleanup wiring below: {@link EmbedSocket#close()} used to destroy
     * a still-embedded client's window outright instead of releasing it,
     * because {@code XDestroyWindow} on this socket's own window destroys
     * its whole subtree immediately — X11's save-set only rescues a
     * reparented-in window from that by reparenting it back to root when the
     * *owning connection itself* closes, not when that connection merely
     * issues an explicit destroy on one of its own windows while staying
     * open. {@code close()} now calls {@link EmbedSocket#detachClient()}
     * first for exactly this reason, so a client still embedded when
     * {@code close()} runs ends up released (as {@code detachClient()} — an
     * ordinary top-level window again) instead of destroyed.
     */
    @Test
    void closeReleasesAStillEmbeddedClientInsteadOfDestroyingIt() throws IOException, InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("EmbedSocketTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socket = new EmbedSocket(owner);
        socket.open(canvas);

        Process clientProcess = startFakeClientProcess();
        try {
            long clientPid = clientProcess.pid();
            long clientWindowId;
            try (X11Display display = X11Display.open(null)) {
                clientWindowId = waitForOwnWindow(display, clientPid);
                XEmbedInfoProperty.write(display.raw(), clientWindowId,
                        new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
            }
            socket.embed(clientPid);

            try (X11Display probe = X11Display.open(null); WindowReparentWatcher watcher = new WindowReparentWatcher()) {
                long rootWindowId = probe.defaultRootWindow().longValue();
                CountDownLatch reparentedToRoot = new CountDownLatch(1);
                watcher.watch(clientWindowId, parent -> {
                    if (parent == rootWindowId) {
                        reparentedToRoot.countDown();
                    }
                });

                socket.close();

                assertTrue(reparentedToRoot.await(5, TimeUnit.SECONDS),
                        "close() with a client still embedded did not release it back to root");
            }
        } finally {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Regression coverage for the auto-cleanup wiring added to {@link
     * EmbedSocket#open(Canvas)}: disposing {@code hostCanvas}'s containing
     * {@link Frame} without ever calling {@link EmbedSocket#close()}
     * previously left this socket's own X11 connection — and the two
     * background threads it drives ({@link cz.loplex.jembetter.core.x11.WindowDeathWatcher},
     * {@link cz.loplex.jembetter.core.xembed.XEmbedInboundWatcher}) — running
     * for the rest of the process's life, since nothing ever called {@code
     * close()} to shut them down. (The X11 *window* resources themselves
     * don't actually leak even without this fix: disposing the canvas
     * already destroys its native peer, and this socket's child window — and
     * transitively the still-embedded client's window — go with it as
     * ordinary X11 subtree destruction, before this listener even runs; a
     * {@link java.awt.event.HierarchyListener} fires as a notification, not
     * a hook that can run ahead of that. What only this fix reclaims is the
     * connection/thread pair on the JVM side.) Asserted here via the
     * watcher's own well-known thread name, since {@code EmbedSocket} has no
     * public "am I closed" query — {@link EmbedSocket#close()} stops it (see
     * {@code WindowDeathWatcher#close()}), and nothing else in this test
     * creates a same-named thread to confuse the count. {@link
     * EmbedSocket#close()} itself stays idempotent, so the {@code
     * @AfterEach} cleanup's own {@code socket.close()} call afterward
     * doubles as double-close coverage.
     */
    @Test
    void disposingTheHostFrameWithoutClosingAutoClosesTheSocket() throws IOException, InterruptedException {
        Canvas canvas = new Canvas();
        canvas.setPreferredSize(new Dimension(100, 100));
        owner = new Frame("EmbedSocketTest owner");
        owner.add(canvas);
        owner.pack();
        owner.setVisible(true);
        Thread.sleep(200);

        socket = new EmbedSocket(owner);
        socket.open(canvas);

        Process clientProcess = startFakeClientProcess();
        try {
            long clientPid = clientProcess.pid();
            try (X11Display display = X11Display.open(null)) {
                long clientWindowId = waitForOwnWindow(display, clientPid);
                XEmbedInfoProperty.write(display.raw(), clientWindowId,
                        new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
            }
            socket.embed(clientPid);

            assertTrue(countThreadsNamed("xembed-window-death-watcher") >= 1,
                    "test setup: expected EmbedSocket's own death-watcher thread to be running before disposal");

            assertDoesNotThrow(() -> owner.dispose(),
                    "disposing the host frame must not throw even though close() was never called explicitly");

            assertTrue(waitUntilNoThreadNamed("xembed-window-death-watcher"),
                    "EmbedSocket's own death-watcher thread was still running after disposing the host frame - "
                            + "the socket's own X11 connection was never closed");
            assertTrue(waitUntilNoThreadNamed("xembed-inbound-watcher"),
                    "EmbedSocket's own inbound-watcher thread was still running after disposing the host frame - "
                            + "the socket's own X11 connection was never closed");
        } finally {
            clientProcess.destroy();
            clientProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static long countThreadsNamed(String name) {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> name.equals(t.getName())).count();
    }

    private static boolean waitUntilNoThreadNamed(String name) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (countThreadsNamed(name) == 0) {
                return true;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        return false;
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

    private record FakeClient(JFrame frame, long windowId) {
    }

    private static FakeClient offerFakeClient(Path socketPath, long pid) throws IOException, InterruptedException {
        JFrame frame = new JFrame("EmbedSocketTest fake client");
        frame.setUndecorated(true);
        frame.setBounds(0, 0, 30, 30);
        frame.setVisible(true);

        long windowId;
        try (X11Display display = X11Display.open(null)) {
            windowId = waitForOwnWindow(display, pid);
            XEmbedInfoProperty.write(display.raw(), windowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            PidHandshake.send(channel, pid);
        }
        return new FakeClient(frame, windowId);
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

    private static void waitForNoOwnWindow(X11Display display, long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (WindowFinder.findTopLevelWindowsByPid(display, pid).isEmpty()) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Window manager never dropped the disposed client's window");
    }
}
