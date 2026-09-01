package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmbedPlug}'s Win32 backend ({@code EmbedPlugWin32})
 * against real HWNDs — mirrors {@link EmbedPlugTest}'s X11 coverage, using
 * {@link Win32Reparent} directly in place of a real host (this module has
 * no Win32 host facade of its own to drive from here) and {@link
 * Win32TestWindow} in place of {@code RawWindow.createOverrideRedirect}.
 */
@Tag("windows")
class EmbedPlugWin32Test {

    private JFrame frame;
    private EmbedPlug plug;
    private long fakeHostHwnd = -1;

    @AfterEach
    void cleanup() {
        if (plug != null) {
            plug.close();
        }
        if (frame != null) {
            frame.dispose();
        }
        if (fakeHostHwnd >= 0 && Win32TestWindow.exists(fakeHostHwnd)) {
            Win32TestWindow.destroy(fakeHostHwnd);
        }
    }

    @Test
    void announcesAndDetectsBeingReparentedByAHost() throws InterruptedException {
        frame = new JFrame("EmbedPlugWin32Test");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch embedded = new CountDownLatch(1);
        AtomicLong reportedEmbedderWindow = new AtomicLong(-1);
        plug = EmbedPlug.create();
        plug.onEmbedded(id -> {
            reportedEmbedderWindow.set(id);
            embedded.countDown();
        });
        plug.announce(null);

        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        fakeHostHwnd = Win32TestWindow.create("EmbedPlugWin32Test fake host");
        Win32Reparent.reparent(ownHwnd, fakeHostHwnd, 0, 0);

        assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after announce(wmClass)");
        assertEquals(fakeHostHwnd, reportedEmbedderWindow.get());
    }

    /**
     * Regression coverage for {@link EmbedPlug#onFocusChanged}: since the
     * 2026-09-01 {@code Win32FocusWatcher} addition, this backend actually
     * delivers it (was previously a documented no-op). {@link Win32Focus#set}
     * here stands in for a host calling it on the client's window (see
     * {@code jembetter-host.Win32EmbedCore#reparentAndWatch}) — {@code
     * Win32FocusWatcher} polls {@code GetGUIThreadInfo} rather than watching
     * a system event, so it doesn't matter that this test calls {@code
     * SetFocus} from the same process rather than a separate host process,
     * and it needs no {@code wine-incompatible} tag: unlike the {@code
     * SetWinEventHook(EVENT_OBJECT_FOCUS, ...)} approach this replaced (see
     * {@code Win32FocusWatcher}'s Javadoc and {@code docs/win32-status.md}),
     * plain {@code GetGUIThreadInfo} polling works fine under Wine too.
     */
    @Test
    void onFocusChangedIsInvokedWhenFocusMovesToTheWatchedWindow() throws InterruptedException {
        frame = new JFrame("EmbedPlugWin32Test focus");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        CountDownLatch gained = new CountDownLatch(1);
        plug = EmbedPlug.create();
        plug.onFocusChanged(focused -> {
            if (focused) {
                gained.countDown();
            }
        });
        plug.announce(null);

        long ownHwnd = waitForOwnWindow(ProcessHandle.current().pid());
        Win32Focus.set(ownHwnd);

        assertTrue(gained.await(5, TimeUnit.SECONDS), "onFocusChanged(true) was never invoked after SetFocus");
    }

    @Test
    void announcesOverAHostSocketAndDetectsHostDeath() throws Exception {
        frame = new JFrame("EmbedPlugWin32Test");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        Path socketPath = Files.createTempFile("jembetter-client-win32-facade-test-", ".sock");
        Files.delete(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        try {
            CountDownLatch hostReady = new CountDownLatch(1);
            CountDownLatch hostDone = new CountDownLatch(1);
            CountDownLatch releaseHost = new CountDownLatch(1);
            Thread host = new Thread(() -> runFakeHost(address, hostReady, hostDone, releaseHost));
            host.setDaemon(true);
            host.start();
            assertTrue(hostReady.await(5, TimeUnit.SECONDS), "fake host never started listening");

            CountDownLatch embedded = new CountDownLatch(1);
            CountDownLatch detached = new CountDownLatch(1);
            plug = EmbedPlug.create();
            plug.onEmbedded(id -> embedded.countDown());
            plug.onHostDetached(detached::countDown);
            plug.announce(socketPath, null);

            // The client detects embed/detach by polling GetParent (Win32 has no
            // reparent event) - so let it actually observe the embed before the
            // host tears the window down, otherwise a fast enough host would go
            // parent=0 -> destroyed without the poll ever seeing parent=host.
            assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after announce");
            releaseHost.countDown();

            assertTrue(hostDone.await(5, TimeUnit.SECONDS), "fake host never finished embedding and dying");
            assertTrue(detached.await(5, TimeUnit.SECONDS), "onHostDetached was never invoked");
        } finally {
            Files.deleteIfExists(socketPath);
        }
    }

    private void runFakeHost(UnixDomainSocketAddress address, CountDownLatch ready, CountDownLatch done,
            CountDownLatch releaseHost) {
        long hostHwnd = Win32TestWindow.create("EmbedPlugWin32Test fake host (socket)");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            ready.countDown();

            try (SocketChannel accepted = server.accept()) {
                long clientPid = PidHandshake.receive(accepted);
                long clientHwnd = waitForOwnWindow(clientPid);
                Win32Reparent.reparent(clientHwnd, hostHwnd, 0, 0);
            }
            releaseHost.await(5, TimeUnit.SECONDS);
            // Destroying the fake host's HWND cascades to destroy the
            // client's own (now-child) HWND too, real Win32 semantics with
            // no X11 save-set equivalent - see EmbedPlugWin32's Javadoc.
            Win32TestWindow.destroy(hostHwnd);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
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
