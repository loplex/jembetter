package cz.loplex.xembed.host;

import com.sun.jna.platform.unix.X11.Window;
import com.sun.jna.platform.unix.X11.WindowByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import cz.loplex.xembed.common.CanvasNativeHandle;
import cz.loplex.xembed.common.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.x11.X11Ext;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedInfoProperty;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

        Path socketPath = Files.createTempFile("xembed-host-test-", ".sock");
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

        Path socketPath = Files.createTempFile("xembed-host-test-", ".sock");
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

        Path socketPath = Files.createTempFile("xembed-host-test-", ".sock");
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
     * xembed-client.EmbedClient#announce}.
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
            long parent = parentOf(display, current);
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

    private static long parentOf(X11Display display, long windowId) {
        WindowByReference rootReturn = new WindowByReference();
        WindowByReference parentReturn = new WindowByReference();
        PointerByReference childrenReturn = new PointerByReference();
        IntByReference nchildrenReturn = new IntByReference();
        X11Ext.INSTANCE.XQueryTree(display.raw(), new Window(windowId), rootReturn, parentReturn, childrenReturn,
                nchildrenReturn);
        if (childrenReturn.getValue() != null) {
            X11Ext.INSTANCE.XFree(childrenReturn.getValue());
        }
        return parentReturn.getValue().longValue();
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
