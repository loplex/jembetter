package cz.loplex.xembed.host;

import cz.loplex.xembed.core.ipc.PidHandshake;
import cz.loplex.xembed.core.x11.WindowFinder;
import cz.loplex.xembed.core.x11.X11Display;
import cz.loplex.xembed.core.xembed.XEmbedInfo;
import cz.loplex.xembed.core.xembed.XEmbedInfoProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.swing.JFrame;
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
        client1 = offerFakeClient(socketPath, pid);
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
        client2 = offerFakeClient(socketPath, pid);
        assertTrue(reEmbedded.await(5, TimeUnit.SECONDS), "second client was never (re-)embedded on the same socket");
    }

    private static JFrame offerFakeClient(Path socketPath, long pid) throws IOException, InterruptedException {
        JFrame frame = new JFrame("EmbedSocketTest fake client");
        frame.setBounds(0, 0, 30, 30);
        frame.setVisible(true);

        try (X11Display display = X11Display.open(null)) {
            long windowId = waitForOwnWindow(display, pid);
            XEmbedInfoProperty.write(display.raw(), windowId,
                    new XEmbedInfoProperty.Value(XEmbedInfo.PROTOCOL_VERSION, XEmbedInfo.MAPPED));
        }

        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            PidHandshake.send(channel, pid);
        }
        return frame;
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
