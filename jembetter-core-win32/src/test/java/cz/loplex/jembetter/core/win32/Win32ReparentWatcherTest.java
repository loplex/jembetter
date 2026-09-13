package cz.loplex.jembetter.core.win32;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static cz.loplex.jembetter.core.win32.Win32TestWindows.createTopLevelWindow;
import static cz.loplex.jembetter.core.win32.Win32TestWindows.desktopWindow;
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

    /**
     * Instrumented beyond what it asserts, because it fails on real Windows at
     * a rate (4 of 10 CI iterations on 2026-09-13) reporting a parent of
     * 65548 — a handle low and stable enough across machines to be the desktop
     * window. Three causes would produce that and the bare assertion cannot
     * tell them apart: the poll landing inside {@code Win32Reparent.reparent},
     * between the {@code WS_CHILD} style flip and the {@code SetParent} that
     * follows it, where {@code GetParent} does read the desktop; a second
     * callback arriving after the expected one; or a {@code SetParent} that
     * never took effect, leaving the desktop as the parent for good.
     *
     * <p>So it keeps every reported value rather than only the last, and the
     * failure message carries the child's actual parent both immediately after
     * the reparent and at assertion time, against {@code GetDesktopWindow()}.
     * One failing run then names the cause instead of narrowing it.
     */
    @Test
    void reportsANewParentAfterSetParent() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest child");
        watcher = new Win32ReparentWatcher();

        CountDownLatch reparented = new CountDownLatch(1);
        List<Long> reportedParents = new CopyOnWriteArrayList<>();
        watcher.watch(childHwnd, newParent -> {
            reportedParents.add(newParent);
            reparented.countDown();
        });

        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);
        long parentAfterReparent = Win32Reparent.parentOf(childHwnd);

        assertTrue(reparented.await(5, TimeUnit.SECONDS), "watcher never reported the SetParent");
        assertEquals(parentHwnd, (long) reportedParents.get(reportedParents.size() - 1),
                () -> whatTheWatcherSaw(reportedParents, parentAfterReparent));
    }

    private String whatTheWatcherSaw(List<Long> reportedParents, long parentAfterReparent) {
        return "watcher reported " + reportedParents + ", expected the new parent " + parentHwnd
                + "; GetParent(child) was " + parentAfterReparent + " immediately after the reparent"
                + " and is " + Win32Reparent.parentOf(childHwnd) + " now"
                + "; GetDesktopWindow() is " + desktopWindow();
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
     * (X11 would reparent a released child back to the root, alive). The
     * real-machine {@code ReparentWatcherCheck} covers the cross-process form.
     *
     * <p>Was {@code @Tag("wine-incompatible")} until 2026-09-13, on the
     * grounds that Wine did not replicate the cascade. wine-staging 11.16
     * does: run under the Wine fork with the exclusion lifted
     * ({@code mvn test -Dwine.excludedGroups=}) this passed every time across
     * repeated runs.
     */
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
    /**
     * The case the parent-based callback structurally cannot report: a window
     * embedded into a host and destroyed with it inside a single poll
     * interval. Its parent reads 0 before and 0 after, so nothing appears to
     * have changed — which is exactly the sequence a host that embeds a client
     * and then crashes produces.
     *
     * <p>No sleep between the two operations, on purpose: the point is that
     * the watcher never gets to observe the intermediate state.
     */
    @Test
    void reportsDestructionEvenWhenTheEmbedItselfWasNeverObserved() throws InterruptedException {
        parentHwnd = createTopLevelWindow("Win32ReparentWatcherTest fast-parent");
        childHwnd = createTopLevelWindow("Win32ReparentWatcherTest fast-child");
        watcher = new Win32ReparentWatcher();

        CountDownLatch destroyed = new CountDownLatch(1);
        watcher.watch(childHwnd, parent -> {
        }, destroyed::countDown);

        Win32Reparent.reparent(childHwnd, parentHwnd, 0, 0);
        destroyWindow(parentHwnd);

        assertTrue(destroyed.await(5, TimeUnit.SECONDS),
                "onDestroyed was never invoked for a window destroyed with its parent");
    }

}
