package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.destroyWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.isWindow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link Win32ReparentWatcher} against real HWNDs. Gated on {@code
 * OS.WINDOWS} the same way this package's other primitive tests are.
 */
@Tag("windows")
class Win32ReparentWatcherTest {

    private Win32ReparentWatcher watcher;
    private long parentHwnd;
    private long childHwnd;

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
        destroyWindow(childHwnd);
        destroyWindow(parentHwnd);
    }

    @Test
    void reportsANewParentAfterSetParent() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest child");
        watcher = new Win32ReparentWatcher();

        CountDownLatch reparented = new CountDownLatch(1);
        AtomicLong reportedParent = new AtomicLong(-1);
        watcher.watch(childHwnd, newParent -> {
            reportedParent.set(newParent);
            reparented.countDown();
        });

        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);

        assertTrue(reparented.await(5, TimeUnit.SECONDS), "watcher never reported the SetParent");
        assertEquals(parentHwnd, reportedParent.get());
    }

    @Test
    void reportsZeroAfterTheWindowIsDestroyed() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest child");
        watcher = new Win32ReparentWatcher();
        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);

        CountDownLatch detached = new CountDownLatch(1);
        AtomicLong reportedParent = new AtomicLong(-1);
        watcher.watch(childHwnd, newParent -> {
            reportedParent.set(newParent);
            detached.countDown();
        });

        destroyWindow(childHwnd);

        assertTrue(detached.await(5, TimeUnit.SECONDS), "watcher never reported the destroyed window");
        assertEquals(0L, reportedParent.get());
    }

    /**
     * The Win32-vs-X11 asymmetry {@code EmbedPlugWin32#onHostDetached} depends
     * on: destroying a parent HWND destroys its reparented children outright
     * (X11 would reparent a released child back to the root, alive). Wine
     * doesn't replicate that, hence {@code @Tag("wine-incompatible")} — the
     * real-machine {@code ReparentWatcherCheck} covers the cross-process form.
     */
    @Tag("wine-incompatible")
    @Test
    void destroyingTheParentAlsoDestroysTheReparentedChildAndReportsZero() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest asym-parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest asym-child");
        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);
        Thread.sleep(200);

        watcher = new Win32ReparentWatcher();
        CountDownLatch gone = new CountDownLatch(1);
        AtomicLong reportedParent = new AtomicLong(-1);
        watcher.watch(childHwnd, newParent -> {
            reportedParent.set(newParent);
            gone.countDown();
        });

        destroyWindow(parentHwnd);

        assertTrue(gone.await(5, TimeUnit.SECONDS),
                "watcher never reported the child going away with its parent");
        assertEquals(0L, reportedParent.get());
        assertFalse(isWindow(childHwnd),
                "destroying the parent should have destroyed the reparented child");
    }
}
