package cz.loplex.jembetter.client;

import cz.loplex.jembetter.common.ipc.PidHandshake;
import cz.loplex.jembetter.core.win32.Win32Focus;
import cz.loplex.jembetter.core.win32.Win32Reparent;
import cz.loplex.jembetter.core.win32.Win32WindowFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import javax.swing.JFrame;
import java.awt.EventQueue;
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

    /**
     * Calling {@link Win32Reparent#reparent} on a live AWT {@code JFrame}'s
     * own HWND from this test thread — not the AWT toolkit thread that
     * actually owns the window's message queue — was found to hang the
     * process under Wine roughly 2 times out of 3, right after {@code
     * SetParent} returns and before any further AWT/Swing activity; not a
     * plain-Windows issue ({@code windows-ci.yml} runs this test on real
     * {@code windows-latest} with no such hang). The {@link
     * EventQueue#invokeAndWait} call below, round-tripping the AWT event
     * queue once right before the reparent call, brought that down to 0
     * hangs across 10 repeated runs (one unrelated, non-hanging assertion
     * failure on window-handle contamination between rapid Wine-hosted
     * forks) — a test-only mitigation of the race, not a root-caused fix:
     * *why* the round trip closes the window wasn't pinned down. Worth
     * revisiting with real Wine-internals tracing if this flakes again.
     */
    @Test
    void announcesAndDetectsBeingReparentedByAHost() throws Exception {
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
        EventQueue.invokeAndWait(() -> {
        });
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

    /**
     * The socket flow end to end: announce, be embedded by whoever answers,
     * and be told when that host lets go again.
     *
     * <p>Deliberately a host <em>releasing</em> the client rather than dying,
     * which is what this test asserted until 2026-09-13 and never actually
     * exercised. A dying host is supposed to take its embedded children down
     * with it — destroying a parent HWND destroys its children, with no X11
     * save-set equivalent — but the fake host here is a thread of this same
     * JVM, and a thread cannot destroy a window created by another thread.
     * The client window is the {@code JFrame}'s and belongs to AWT's toolkit
     * thread, so {@code DestroyWindow} on the host window left the client
     * alive with a dangling parent handle: the watched parent never changed,
     * the watcher was right to report nothing, and the test failed 10 of 10
     * CI iterations on real Windows for a reason that was never about the
     * code it named.
     *
     * <p>Host death is real between processes, and that is where it is
     * covered: {@code build-tools/win32-real-machine-checks/ReparentWatcherCheck}
     * runs a client window in a separate process and asserts the cascade. It
     * is not part of this suite, so a regression in that path is caught by
     * running that check, not by CI.
     */
    @Test
    void announcesOverAHostSocketAndDetectsTheHostReleasingIt() throws Exception {
        frame = new JFrame("EmbedPlugWin32Test");
        frame.setBounds(0, 0, 50, 50);
        frame.setVisible(true);

        Path socketPath = Files.createTempFile("jembetter-client-win32-facade-test-", ".sock");
        Files.delete(socketPath);
        UnixDomainSocketAddress address = UnixDomainSocketAddress.of(socketPath);

        CountDownLatch hostMayExit = new CountDownLatch(1);
        try {
            CountDownLatch hostReady = new CountDownLatch(1);
            CountDownLatch hostDone = new CountDownLatch(1);
            CountDownLatch releaseHost = new CountDownLatch(1);
            AtomicLong hostWindow = new AtomicLong(-1);
            AtomicLong embeddedClientWindow = new AtomicLong(-1);
            Thread host = new Thread(() -> runFakeHost(address, hostReady, hostDone, releaseHost,
                    hostMayExit, hostWindow, embeddedClientWindow));
            host.setDaemon(true);
            host.start();
            assertTrue(hostReady.await(5, TimeUnit.SECONDS), "fake host never started listening");

            CountDownLatch embedded = new CountDownLatch(1);
            CountDownLatch detached = new CountDownLatch(1);
            AtomicLong reportedEmbedder = new AtomicLong(-1);
            plug = EmbedPlug.create();
            plug.onEmbedded(id -> {
                reportedEmbedder.set(id);
                embedded.countDown();
            });
            plug.onHostDetached(detached::countDown);
            plug.announce(socketPath, null);

            // The client detects embed/detach by polling GetParent (Win32 has no
            // reparent event) - so let it actually observe the embed before the
            // host lets go again, otherwise a fast enough host would go
            // parent=0 -> parent=0 without the poll ever seeing parent=host.
            assertTrue(embedded.await(5, TimeUnit.SECONDS), "onEmbedded was never invoked after announce");
            releaseHost.countDown();

            assertTrue(hostDone.await(5, TimeUnit.SECONDS), "fake host never finished embedding and releasing");
            assertTrue(detached.await(5, TimeUnit.SECONDS),
                    () -> "onHostDetached was never invoked; "
                            + whatTheWindowsLookLike(hostWindow.get(), embeddedClientWindow.get(),
                                    reportedEmbedder.get()));
        } finally {
            hostMayExit.countDown();
            Files.deleteIfExists(socketPath);
        }
    }

    private void runFakeHost(UnixDomainSocketAddress address, CountDownLatch ready, CountDownLatch done,
            CountDownLatch releaseHost, CountDownLatch mayExit, AtomicLong hostWindow,
            AtomicLong embeddedClientWindow) {
        long hostHwnd = Win32TestWindow.create("EmbedPlugWin32Test fake host (socket)");
        hostWindow.set(hostHwnd);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            ready.countDown();

            try (SocketChannel accepted = server.accept()) {
                long clientPid = PidHandshake.receive(accepted);
                long clientHwnd = waitForOwnWindow(clientPid);
                // Published before the reparent, not after: a reparented
                // WS_CHILD window stops being a top-level window of its
                // process, so findApplicationWindowsByPid can no longer name
                // it and the failure message below would have nothing to
                // report on.
                embeddedClientWindow.set(clientHwnd);
                Win32Reparent.reparent(clientHwnd, hostHwnd, 0, 0);
            }
            releaseHost.await(5, TimeUnit.SECONDS);
            // What a host letting go of a client looks like: the client's
            // window goes back to being a top-level window of its own. See
            // this test's Javadoc for why it is this and not the host dying.
            Win32Reparent.release(embeddedClientWindow.get(), 200, 200);
            //
            // Nothing destroys the host window here, deliberately. Destroying
            // it would give the detach a second route on any platform where
            // that reaches a child owned by another thread, and the release
            // above would stop being what the test observes. It goes when this
            // thread does, below.
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }

        // Outlive the assertions on purpose. Windows destroys the windows a
        // thread created when that thread ends, and destroying the host window
        // is a second route to the detach the test is trying to observe - one
        // that reached the client under Wine and left it alone on real
        // Windows, which is how this test came to pass on one platform and
        // fail on the other while testing neither. Measured 2026-09-13: let
        // this thread end here and the test passes with the release removed
        // altogether.
        try {
            mayExit.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What the two windows look like when the detach assertion above fails.
     * Kept from when this test was about a dying host, where "onHostDetached
     * was never invoked" on its own was consistent with four different faults
     * and named none of them - it is what identified the one that turned out
     * to be real. The same three facts still separate the plug failing to
     * report a release (client alive, parent 0) from the release not having
     * happened (client alive, parent still the host) from the plug having
     * watched a window nobody embedded (onEmbedded naming anything else, which
     * it can, since this test process owns more than one window).
     */
    private static String whatTheWindowsLookLike(long hostHwnd, long clientHwnd, long reportedEmbedder) {
        String host = "host window " + hostHwnd + (Win32TestWindow.exists(hostHwnd) ? " still exists" : " is gone");
        String client = "client window " + clientHwnd + (Win32TestWindow.exists(clientHwnd)
                ? " still exists, parent " + Win32Reparent.parentOf(clientHwnd)
                : " is gone");
        return host + "; " + client + "; onEmbedded reported " + reportedEmbedder;
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
