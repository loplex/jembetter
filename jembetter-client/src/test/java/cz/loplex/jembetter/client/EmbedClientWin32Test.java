package cz.loplex.jembetter.client;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        if (frame != null) {
            frame.dispose();
        }
        if (fakeHostHwnd >= 0 && Win32TestWindow.exists(fakeHostHwnd)) {
            Win32TestWindow.destroy(fakeHostHwnd);
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
        assertEquals(false, secondOpcode.get(), "the second opcode (0) was not decoded as modal=false");
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
        client.onResized((width, height) -> {
            reportedWidth.set(width);
            reportedHeight.set(height);
            resized.countDown();
        });
        client.announce();

        // Reparent first, like a real embed would (Win32Reparent.reparent
        // strips the caption/border style bits) - otherwise GetClientRect
        // (what Win32ConfigureWatcher polls) reports less than the window
        // rect moveResize sets, short by the still-decorated JFrame's own
        // title bar/border.
        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        fakeHostHwnd = Win32TestWindow.create("EmbedClientWin32Test fake host (resize)");
        Win32Reparent.reparent(ownHwnd, fakeHostHwnd, 0, 0);
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

    private static long waitForOwnWindow(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<Long> found;
        do {
            found = Win32WindowFinder.findApplicationWindowsByPid(pid);
            if (!found.isEmpty()) {
                return found.get(0);
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Client process " + pid + " never published a top-level window");
    }
}
